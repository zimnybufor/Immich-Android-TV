package nl.giejay.mediaslider.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import nl.giejay.mediaslider.player.VideoDecoderRegistry
import androidx.media3.ui.PlayerView
import com.zeuskartik.mediaslider.R
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import nl.giejay.mediaslider.config.MediaSliderConfiguration

class ExoPlayerView @JvmOverloads constructor(context: Context, resourceId: Int,  attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val playerView: PlayerView
    private val playBtn: ImageButton
    private val muteBtn: ImageButton
    private val forwardBtn: ImageButton
    private val rewindBtn: ImageButton
    private val slideshowBtn: ImageButton
    private var player: ExoPlayer? = null

    init {
        LayoutInflater.from(context).inflate(resourceId, this, true)
        playerView = findViewById(R.id.video_view)
        playBtn = playerView.findViewById(R.id.exo_pause)
        muteBtn = playerView.findViewById(R.id.exo_mute)
        forwardBtn = playerView.findViewById(R.id.exo_forward)
        rewindBtn = playerView.findViewById(R.id.exo_rewind)
        slideshowBtn = playerView.findViewById(R.id.exo_slideshow)
    }

    @OptIn(UnstableApi::class)
    fun setupPlayer(
        config: MediaSliderConfiguration,
        renderersFactory: NextRenderersFactory,
        listener: ExoPlayerListener,
        onButtonClick: (Int) -> Unit,
        onPlayerError: (ExoPlayer, Exception) -> Boolean
    ) {
        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(createLoadControl(config))
            .build()
        player!!.let { p ->
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    VideoDecoderRegistry.record(p, decoderName)
                }
            })
        }
        playerView.player = player
        if (!config.isVideoSoundEnable) player?.volume = 0f

        playBtn.setOnClickListener {
            onButtonClick(R.id.exo_pause)
            player?.let {
                if (it.isPlaying) it.pause()
                else {
                    if (it.currentPosition >= it.contentDuration) it.seekToDefaultPosition()
                    it.play()
                }
            }
        }
        muteBtn.setOnClickListener {
            player?.let {
                if (it.volume == 0f) {
                    it.volume = 1f
                    muteBtn.setImageResource(R.drawable.unmute_icon)
                    config.isVideoSoundEnable = true
                } else {
                    it.volume = 0f
                    muteBtn.setImageResource(R.drawable.mute_icon)
                    config.isVideoSoundEnable = false
                }
            }
        }
        forwardBtn.setOnClickListener { onButtonClick(R.id.exo_forward) }
        rewindBtn.setOnClickListener { onButtonClick(R.id.exo_rewind) }
        slideshowBtn.setOnClickListener { onButtonClick(R.id.exo_slideshow) }
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                listener.onPlaybackStateChanged(playbackState)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener.onIsPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // if not handled, let listener handle it
                if(!onPlayerError(player!!, error)){
                    listener.onPlayerError(error)
                }
            }
        })
    }

    fun releasePlayer() {
        player?.release()
        player = null
    }

    fun getPlayerView(): PlayerView {
        return playerView
    }

    fun getPlayer(): ExoPlayer? {
        return player
    }

    fun isReady(): Boolean {
        return player != null
    }

    @OptIn(UnstableApi::class)
    private fun createLoadControl(config: MediaSliderConfiguration): DefaultLoadControl {
        val builder = DefaultLoadControl.Builder()
        if (config.useLargeVideoBuffer) {
            builder
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setTargetBufferBytes(getSafetyBufferBytes())
                // false = the byte cap is a HARD bound. With true, ExoPlayer chases
                // MAX_BUFFER_MS and ignores the byte cap, so a high-bitrate DV original
                // (~80 Mbps) filled the 384 MB ART heap in ~40 s and OOM-crashed.
                .setPrioritizeTimeOverSizeThresholds(false)
        } else {
            builder.setPrioritizeTimeOverSizeThresholds(false)
        }
        return builder.build()
    }

    private fun getSafetyBufferBytes(): Int {
        // ExoPlayer's DefaultAllocator buffers are byte[] on the ART heap, not native/RAM.
        // Size the cap from maxMemory() (the app's hard heap limit, ~384 MB on this Chromecast),
        // NOT from device totalMem (~2 GB) — otherwise the cap exceeds the heap and OOM is certain.
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val safeMb = (maxHeapMb * SAFETY_MEMORY_FRACTION).toInt().coerceIn(MIN_SAFETY_MB, MAX_SAFETY_MB)
        return safeMb * 1024 * 1024
    }

    private companion object {
        const val MIN_BUFFER_MS = 10_000
        // Secondary ceiling only; the byte cap (prioritize=false) is the real bound.
        // 300_000 (5 min) was absurd for high-bitrate originals.
        const val MAX_BUFFER_MS = 60_000
        const val BUFFER_FOR_PLAYBACK_MS = 4_000
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000

        // Fraction of the app's ART heap (~384 MB) allowed for the video buffer.
        // 0.40 -> ~153 MB, leaving headroom for UI, bitmaps and decoders.
        const val SAFETY_MEMORY_FRACTION = 0.40
        const val MIN_SAFETY_MB = 64
        const val MAX_SAFETY_MB = 192
    }
}
