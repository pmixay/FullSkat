package com.fullskat.watch.richtext.math

/**
 * Ink extents of a string at font size 1 (everything in this package is measured in em units, so a
 * layout is independent of the display size and can be cached per formula).
 * [ascent] is the ink above the baseline, [descent] the ink below it; either may be negative for
 * glyphs that float above or sink below the baseline (a prime, an underscore).
 */
class GlyphMetrics(
    val advance: Float,
    val ascent: Float,
    val descent: Float,
    /** Horizontal ink extent; combining marks have no advance, so accents are centred on their ink. */
    val inkLeft: Float = 0f,
    val inkRight: Float = advance,
)

/** Measures text in the math font. Implemented with android.graphics.Paint on the watch and with AWT in JVM tests. */
fun interface MathMetrics {
    fun measure(text: String): GlyphMetrics
}

/**
 * Display list produced by the layout. Coordinates are em units, x to the right and y DOWN from the
 * baseline of the formula, so things above the baseline have negative y. [color] is ARGB, null means
 * "the text colour of the surrounding content".
 */
sealed class MathOp {
    abstract fun shifted(dx: Float, dy: Float): MathOp
}

/** A run of glyphs. [sx]/[sy] stretch it around its own x / around [pivotY]; used for big delimiters and wide accents. */
class GlyphOp(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int? = null,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val pivotY: Float = 0f,
) : MathOp() {
    override fun shifted(dx: Float, dy: Float) = GlyphOp(text, x + dx, y + dy, size, color, sx, sy, pivotY + dy)
}

/** A filled rectangle (fraction bars, overlines, matrix rules). */
class RuleOp(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int? = null) : MathOp() {
    override fun shifted(dx: Float, dy: Float) = RuleOp(x + dx, y + dy, w, h, color)
}

/** A stroked polyline: x0,y0,x1,y1,... (radicals, braces, arrows, cancel strokes). */
class PolyOp(val points: FloatArray, val thickness: Float, val color: Int? = null) : MathOp() {
    override fun shifted(dx: Float, dy: Float): PolyOp {
        val p = FloatArray(points.size) { if (it % 2 == 0) points[it] + dx else points[it] + dy }
        return PolyOp(p, thickness, color)
    }
}

/** A laid-out formula: [width] x ([ascent] + [descent]) em, baseline at y = 0. */
class MathLayout(val width: Float, val ascent: Float, val descent: Float, val ops: List<MathOp>) {
    val height: Float get() = ascent + descent
}
