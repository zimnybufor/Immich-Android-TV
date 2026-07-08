package nl.giejay.android.tv.immich.card

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import java.util.WeakHashMap
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.presenter.AbstractPresenter
import timber.log.Timber


open class CardPresenter(context: Context, style: Int = R.style.DefaultCardTheme) :
    AbstractPresenter<ImageCardView, ICard>(ContextThemeWrapper(context, style)) {

    private val videoBadges = WeakHashMap<ImageCardView, VideoBadgeDrawable>()

    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        loadImage(card, cardView)

        cardView.tag = card
        cardView.titleText = card.title
        if (card.description != "") {
            cardView.contentText = card.description
        }
        updateVideoBadge(card, cardView)
        setSelected(cardView, card.selected)
    }

    private fun updateVideoBadge(card: ICard, cardView: ImageCardView) {
        val imageView = cardView.mainImageView ?: return
        val badge = videoBadges.getOrPut(cardView) {
            VideoBadgeDrawable(context.resources.displayMetrics.density).also {
                it.setBounds(0, 0, 1, 1)
                imageView.overlay.add(it)
            }
        }
        badge.label = if (card.isVideo) {
            listOfNotNull(card.durationLabel, card.resolutionLabel)
                .joinToString(" \u00B7 ")
                .let { "\u25B6 " + it }
        } else ""
        imageView.invalidate()
    }

    /** Small "play + duration" chip drawn over the bottom-right of the thumbnail. */
    private class VideoBadgeDrawable(private val density: Float) : Drawable() {
        var label: String = ""
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11 * density
        }

        override fun draw(canvas: Canvas) {
            if (label.isBlank()) return
            val padH = 5 * density
            val padV = 2.5f * density
            val margin = 4 * density
            val textWidth = textPaint.measureText(label.trim())
            val textHeight = textPaint.descent() - textPaint.ascent()
            val right = canvas.width - margin
            val bottom = canvas.height - margin
            val rect = RectF(right - textWidth - 2 * padH, bottom - textHeight - 2 * padV, right, bottom)
            canvas.drawRoundRect(rect, 3 * density, 3 * density, bgPaint)
            canvas.drawText(label.trim(), rect.left + padH, rect.bottom - padV - textPaint.descent(), textPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        super.onUnbindViewHolder(viewHolder)
        if(context is Activity && context.isFinishing){
            return
        }
        try {
            Glide.with(context).clear((viewHolder.view as ImageCardView).mainImageView!!)
        } catch (e: IllegalArgumentException){
            Timber.e(e)
        }
    }

    open fun loadImage(card: ICard, cardView: ImageCardView) {
        card.thumbnailUrl?.let {
            if(it.startsWith("http")){
                Glide.with(context)
                    .asBitmap()
                    .centerInside()
                    .load(it)
                    .into(cardView.mainImageView!!)
            } else {
                cardView.mainImageView!!.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val resourceId = context.resources.getIdentifier(it, "drawable",
                    context.packageName);
                cardView.mainImageView!!.setImageResource(resourceId)
            }
//                .addListener(object : RequestListener<Bitmap> {
//                    override fun onLoadFailed(
//                        e: GlideException?,
//                        model: Any?,
//                        target: Target<Bitmap>?,
//                        isFirstResource: Boolean
//                    ): Boolean {
//                        return false
//                    }
//
//                    override fun onResourceReady(
//                        resource: Bitmap?,
//                        model: Any?,
//                        target: Target<Bitmap>?,
//                        dataSource: DataSource?,
//                        isFirstResource: Boolean
//                    ): Boolean {
//                        Timber.i("Loaded card image for: ${card.id}")
//                        return false
//                    }
//
//                })
        }
    }

    override fun onCreateView(): ImageCardView {
        return ImageCardView(context)
    }

    private fun setSelected(imageCardView: ImageCardView, selected: Boolean) {
        if(selected){
            imageCardView.mainImageView!!.background = context.getDrawable(R.drawable.border)
        } else {
            imageCardView.mainImageView!!.background = null
        }
    }
}