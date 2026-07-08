package nl.giejay.mediaslider.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * "Stats for nerds" style overlay for the video player. Shows the stream
 * source (original vs server-side transcode), format, decoder (hardware vs
 * software), dropped frames, buffer health and measured network throughput.
 *
 * The source detection works by requesting the first byte of both
 * `/video/playback` and `/original` and comparing the total sizes reported in
 * Content-Range: Immich serves the transcode under /video/playback when one
 * exists, so equal sizes mean the original is being played.
 */
@UnstableApi
class PlaybackStatsOverlay(
    private val ioScope: CoroutineScope,
    private val dataSourceFactory: DefaultHttpDataSource.Factory
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var textView: TextView? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var mediaUrl: String? = null

    // collected stats
    private var decoderName: String? = null
    private var rawFrameRate: Float = -1f
    private var bandwidthBps: Long = -1
    private var droppedFrames: Int = 0
    private var sourceLabel: String = "checking..."
    private val sourceCache = mutableMapOf<String, String>()

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            this@PlaybackStatsOverlay.decoderName = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            rawFrameRate = format.frameRate
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
            bandwidthBps = bitrateEstimate
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            this@PlaybackStatsOverlay.droppedFrames += droppedFrames
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateText()
            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun attach(player: ExoPlayer, playerView: PlayerView, mediaUrl: String?) {
        detach()
        this.player = player
        this.playerView = playerView
        this.mediaUrl = mediaUrl
        this.decoderName = null
        this.rawFrameRate = -1f
        this.droppedFrames = 0
        this.sourceLabel = sourceCache[mediaUrl] ?: "checking..."

        player.addAnalyticsListener(analyticsListener)
        val container: FrameLayout = playerView.overlayFrameLayout ?: playerView
        textView = buildTextView(container).also { container.addView(it) }
        mainHandler.post(updateRunnable)
        if (mediaUrl != null && !sourceCache.containsKey(mediaUrl)) resolveSource(mediaUrl)
    }

    fun detach() {
        mainHandler.removeCallbacks(updateRunnable)
        player?.removeAnalyticsListener(analyticsListener)
        textView?.let { view -> (view.parent as? FrameLayout)?.removeView(view) }
        textView = null
        player = null
        playerView = null
    }

    val attached: Boolean get() = textView != null

    @SuppressLint("SetTextI18n")
    private fun buildTextView(container: FrameLayout): TextView {
        return TextView(container.context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(pad * 2, pad * 2, 0, 0) }
            text = "stats..."
        }
    }

    private fun updateText() {
        val player = this.player ?: return
        val format = player.videoFormat
        val counters = player.videoDecoderCounters?.apply { ensureUpdated() }

        val video = if (format != null) {
            val fps = if (rawFrameRate > 0) rawFrameRate else format.frameRate
            val codec = format.codecs ?: format.sampleMimeType ?: "?"
            String.format(Locale.US, "%dx%d @%.6g  %s", format.width, format.height, fps, codec)
        } else "?"
        val decoder = (decoderName ?: VideoDecoderRegistry.get(player))?.let { name ->
            val isSw = SW_DECODER_MARKERS.any { name.contains(it, ignoreCase = true) }
            "$name [${if (isSw) "SW" else "HW"}]"
        } ?: "?"
        val dropped = counters?.droppedBufferCount ?: droppedFrames
        val bufferSec = player.totalBufferedDuration / 1000.0
        val network = if (bandwidthBps > 0)
            String.format(Locale.US, "~%.1f Mb/s", bandwidthBps / 1_000_000.0) else "measuring..."

        textView?.text = """
            SOURCE   $sourceLabel
            VIDEO    $video
            DECODER  $decoder
            FRAMES   dropped: $dropped
            BUFFER   ${String.format(Locale.US, "%.1f", bufferSec)} s
            NETWORK  $network
        """.trimIndent()
    }

    private fun resolveSource(url: String) {
        ioScope.launch {
            val label = when {
                url.endsWith("/original") -> "original (forced)"
                !url.contains(PLAYBACK_PATH) -> "unknown"
                else -> {
                    val playbackSize = fetchTotalBytes(url)
                    val originalSize = fetchTotalBytes(url.replace(PLAYBACK_PATH, "/original"))
                    when {
                        playbackSize == null || originalSize == null -> "unknown"
                        playbackSize == originalSize -> "original (${formatSize(originalSize)})"
                        else -> "transcode (${formatSize(playbackSize)}, original ${formatSize(originalSize)})"
                    }
                }
            }
            sourceCache[url] = label
            withContext(Dispatchers.Main) {
                if (url == mediaUrl) sourceLabel = label
            }
        }
    }

    private fun fetchTotalBytes(url: String): Long? = try {
        val dataSource = dataSourceFactory.createDataSource()
        try {
            dataSource.open(DataSpec.Builder().setUri(url).setPosition(0).setLength(1).build())
            dataSource.responseHeaders["Content-Range"]?.firstOrNull()
                ?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: dataSource.responseHeaders["Content-Length"]?.firstOrNull()?.trim()?.toLongOrNull()
        } finally {
            dataSource.close()
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not determine size of $url")
        null
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1 shl 30).toDouble())
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1 shl 20).toDouble())
        else -> "$bytes B"
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1000L
        const val PLAYBACK_PATH = "/video/playback"
        val SW_DECODER_MARKERS = listOf("c2.android.", "OMX.google.", "ffmpeg", ".sw.")
    }
}
