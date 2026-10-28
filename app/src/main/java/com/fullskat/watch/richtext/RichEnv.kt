package com.fullskat.watch.richtext

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import com.fullskat.watch.richtext.math.GlyphMetrics
import com.fullskat.watch.richtext.math.GlyphOp
import com.fullskat.watch.richtext.math.LatexParser
import com.fullskat.watch.richtext.math.MathLayout
import com.fullskat.watch.richtext.math.MathLayoutEngine
import com.fullskat.watch.richtext.math.MathMetrics
import com.fullskat.watch.richtext.math.PolyOp
import com.fullskat.watch.richtext.math.RuleOp

/**
 * Everything the rich-text renderer needs from the platform, created once: the bundled math font,
 * glyph measuring on top of it, the layout engine with a cache of laid-out formulas, and the painter.
 * Used from the main thread only (the Paint objects inside are shared).
 */
class RichEnv private constructor(private val typeface: Typeface) {
    private val metrics = PaintMetrics(typeface)
    private val engine = MathLayoutEngine(metrics)
    private val cache = LruCache<String, MathLayout>(256)
    private val painter = MathPainter(typeface)

    /** Parses and lays out [latex]; the result is in em units, so it is independent of the display size. */
    fun layout(latex: String, display: Boolean): MathLayout {
        val key = (if (display) "D" else "I") + latex
        cache.get(key)?.let { return it }
        val layout = engine.layout(LatexParser.parse(latex), display)
        cache.put(key, layout)
        return layout
    }

    fun draw(canvas: Canvas, layout: MathLayout, x: Float, baseline: Float, px: Float, color: Int) =
        painter.draw(canvas, layout, x, baseline, px, color)

    companion object {
        private const val FONT = "fonts/NotoSansMath.ttf"

        @Volatile private var instance: RichEnv? = null

        fun get(context: Context): RichEnv = instance ?: synchronized(this) {
            instance ?: RichEnv(loadTypeface(context.applicationContext)).also { instance = it }
        }

        private fun loadTypeface(context: Context): Typeface = try {
            Typeface.createFromAsset(context.assets, FONT)
        } catch (e: Exception) {
            Log.w("RichEnv", "math font unavailable, using the system font", e)
            Typeface.DEFAULT
        }
    }
}

/** Measures glyph runs with the real font through Paint, at a large reference size so the integer bounds are precise. */
internal class PaintMetrics(typeface: Typeface) : MathMetrics {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = REF
    }
    private val rect = Rect()
    private val cache = HashMap<String, GlyphMetrics>()

    @Synchronized
    override fun measure(text: String): GlyphMetrics {
        cache[text]?.let { return it }
        val advance = paint.measureText(text) / REF
        paint.getTextBounds(text, 0, text.length, rect)
        val m = if (rect.width() == 0 && rect.height() == 0) {
            GlyphMetrics(advance, 0f, 0f)
        } else {
            GlyphMetrics(advance, -rect.top / REF, rect.bottom / REF, rect.left / REF, rect.right / REF)
        }
        if (cache.size > 4096) cache.clear()
        cache[text] = m
        return m
    }

    private companion object {
        const val REF = 400f
    }
}

/** Draws a [MathLayout] display list on an android.graphics.Canvas. */
internal class MathPainter(typeface: Typeface) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** [baseline] is the y of the formula's baseline, [px] the size of 1 em. */
    fun draw(canvas: Canvas, layout: MathLayout, x: Float, baseline: Float, px: Float, color: Int) {
        for (op in layout.ops) {
            when (op) {
                is GlyphOp -> {
                    text.color = op.color ?: color
                    text.textSize = op.size * px
                    if (op.sx == 1f && op.sy == 1f) {
                        canvas.drawText(op.text, x + op.x * px, baseline + op.y * px, text)
                    } else {
                        canvas.save()
                        canvas.translate(x + op.x * px, baseline + op.pivotY * px)
                        canvas.scale(op.sx, op.sy)
                        canvas.drawText(op.text, 0f, (op.y - op.pivotY) * px, text)
                        canvas.restore()
                    }
                }
                is RuleOp -> {
                    fill.color = op.color ?: color
                    val h = maxOf(op.h * px, 1f)
                    val top = baseline + op.y * px - (h - op.h * px) / 2f
                    canvas.drawRect(x + op.x * px, top, x + (op.x + op.w) * px, top + h, fill)
                }
                is PolyOp -> {
                    stroke.color = op.color ?: color
                    stroke.strokeWidth = maxOf(op.thickness * px, 1f)
                    path.rewind()
                    val p = op.points
                    for (i in 0 until p.size / 2) {
                        val px2 = x + p[2 * i] * px
                        val py2 = baseline + p[2 * i + 1] * px
                        if (i == 0) path.moveTo(px2, py2) else path.lineTo(px2, py2)
                    }
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }
}
