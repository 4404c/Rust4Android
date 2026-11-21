package dev.rustdroid.ide.ui.editor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.rustdroid.ide.R
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.widget.component.DefaultCompletionItemAdapter
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.lang.completion.CompletionItem

/**
 * R4A: sora's suggestion list shows a letter per kind ("K" for keyword).
 * Same rows, our icons: the Rust gear for Rust keywords, "ab" for names
 * already used in the file — and the small kind text translated.
 */
class R4ACompletionAdapter : EditorCompletionAdapter() {

    private val inner = DefaultCompletionItemAdapter()

    override fun attachValues(window: EditorAutoCompletion, items: MutableList<CompletionItem>) {
        super.attachValues(window, items)
        inner.attachValues(window, items)
    }

    override fun getItemHeight(): Int = inner.itemHeight

    override fun getView(pos: Int, view: View?, parent: ViewGroup?, isCurrentCursorPosition: Boolean): View {
        val v = inner.getView(pos, view, parent)
        val item = getItem(pos)
        val keyword = item.kind == CompletionItemKind.Keyword
        v.findViewById<ImageView>(io.github.rosemoe.sora.R.id.result_item_image)
            ?.setImageDrawable(if (keyword) RustGearDrawable() else NameDrawable())
        v.findViewById<TextView>(io.github.rosemoe.sora.R.id.result_item_desc)?.text =
            context.getString(if (keyword) R.string.completion_keyword else R.string.completion_name)
        return v
    }
}

private const val RUST_ORANGE = 0xFFDE6B35.toInt()
private const val NAME_BLUE = 0xFF6CB6FF.toInt()

/** Gear ring with teeth and a bold "R" — same look as the .rs file icon. */
private class RustGearDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RUST_ORANGE }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f * 0.9f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val tooth = r * 0.22f
        paint.style = Paint.Style.FILL
        repeat(16) { i ->
            canvas.save()
            canvas.rotate(360f / 16 * i, cx, cy)
            canvas.drawRect(cx - tooth / 2, cy - r, cx + tooth / 2, cy - r + tooth + 1f, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.20f
        canvas.drawCircle(cx, cy, r * 0.80f, paint)
        paint.style = Paint.Style.FILL
        centeredText(canvas, "R", cx, cy, r * 1.05f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Rounded outline with "ab": a name you already wrote in this file. */
private class NameDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAME_BLUE }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val half = s * 0.42f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.07f
        canvas.drawRoundRect(RectF(cx - half, cy - half * 0.8f, cx + half, cy + half * 0.8f), s * 0.15f, s * 0.15f, paint)
        paint.style = Paint.Style.FILL
        centeredText(canvas, "ab", cx, cy, s * 0.42f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun centeredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, paint: Paint) {
    paint.textSize = size
    paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    paint.textAlign = Paint.Align.CENTER
    val m = paint.fontMetrics
    canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2f, paint)
}
