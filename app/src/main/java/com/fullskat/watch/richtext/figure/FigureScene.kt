package com.fullskat.watch.richtext.figure

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import com.fullskat.watch.richtext.RichEnv
import com.fullskat.watch.richtext.markdown.Code
import com.fullskat.watch.richtext.markdown.Markdown
import com.fullskat.watch.richtext.markdown.Math
import com.fullskat.watch.richtext.markdown.Text
import com.fullskat.watch.richtext.math.MathLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A [Figure] laid out in pixels for one view and size, ready to draw at any moment of its drawing animation.
 *
 * Everything is turned into timed elements once (paths, fills, dots, labels); [draw] then only reveals them:
 * axes first, then each item in the model's order, strokes traced along their length with a pen dot at the
 * tip, fills fading in after their outline, points popping in, bars growing, pie slices sweeping, labels last.
 * [draw] at [durationMs] or later shows the finished figure.
 *
 * [Mode.INLINE] fits the figure into a chat bubble with room for tick labels; [Mode.FULL] is the full-screen
 * viewer on a round display: plane figures bleed to the edges like a magnifier, tick labels stay inside the circle.
 */
class FigureScene(
    val figure: Figure,
    requestedView: View,
    val width: Float,
    val height: Float,
    private val dp: Float,
    private val env: RichEnv,
    val mode: Mode,
    /** Draw time budget; longer schedules are compressed to fit. */
    maxDurationMs: Float = 4200f,
) {
    enum class Mode { INLINE, FULL }

    val plot = RectF()
    val view: View
    var durationMs = 0f
        private set

    private val elements = ArrayList<Element>()
    private val frame = ArrayList<Element>() // axes, grid and tick labels (drawn under the items, not clipped)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * dp; color = TICK; typeface = Typeface.DEFAULT }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * dp; typeface = Typeface.DEFAULT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dp; color = SURFACE; strokeJoin = Paint.Join.ROUND }
    private val dash = DashPathEffect(floatArrayOf(4.5f * dp, 3.5f * dp), 0f)
    private var clock = 0f

    /** Plane figures in the viewer bleed to the screen edges; charts keep a frame inside the circle. */
    private val bleed = mode == Mode.FULL && figure.pie == null && figure.bars.isEmpty() &&
        (!figure.axes || figure.items.any { it is FunctionItem || it is Parametric || it is Polar })

    init {
        // Inline: the whole canvas less a small pad; full screen: the screen (bleed) or the square inside the circle.
        val inset = if (mode == Mode.FULL && !bleed) (width - width * 0.72f) / 2 else 3f * dp
        if (bleed) plot.set(0f, 0f, width, height) else plot.set(inset, if (mode == Mode.FULL) (height - height * 0.72f) / 2 else inset, width - inset, height - (if (mode == Mode.FULL) (height - height * 0.72f) / 2 else inset))
        var v = requestedView
        if (figure.pie == null && figure.axes) {
            // Room for tick labels on the edges where an axis sits on the frame (not through the plane).
            val probeY = FigureScale.ticks(v.y0, v.y1, 4)
            val xOnEdge = !bleed && !insideAxis(v.y0, v.y1, figure.bars.isNotEmpty())
            val yOnEdge = !bleed && (figure.bars.isNotEmpty() || !insideAxis(v.x0, v.x1, false))
            if (yOnEdge) plot.left += (probeY.maxOfOrNull { tickPaint.measureText(it.label) } ?: 0f) + 4f * dp
            if (xOnEdge) plot.bottom -= tickPaint.textSize + 4f * dp
            if (figure.bars.isNotEmpty() && figure.bars.any { b -> b.labels.any { it.isNotBlank() } }) plot.bottom -= 2f * dp
        }
        if (figure.equal && figure.pie == null && figure.bars.isEmpty()) v = v.equalized(plot.width(), plot.height())
        view = v
        build()
        val natural = durationMs
        if (natural > maxDurationMs) {
            val k = maxDurationMs / natural
            for (e in frame + elements) { e.start *= k; e.end *= k }
            durationMs = maxDurationMs
        }
    }

    // ---------------------------------------------------------------- mapping

    fun sx(x: Double): Float = (plot.left + (x - view.x0) / view.w * plot.width()).toFloat()
    fun sy(y: Double): Float = (plot.bottom - (y - view.y0) / view.h * plot.height()).toFloat()
    fun fx(px: Float): Double = view.x0 + (px - plot.left) / plot.width() * view.w
    fun fy(py: Float): Double = view.y0 + (plot.bottom - py) / plot.height() * view.h

    /** Pixels per figure unit, for panning. */
    val unitX: Double get() = plot.width() / view.w
    val unitY: Double get() = plot.height() / view.h

    private fun insideAxis(lo: Double, hi: Double, bars: Boolean): Boolean {
        if (bars) return false
        // the axis through 0 runs inside the plot with room on both sides for its labels
        return lo < 0 && hi > 0 && (0 - lo) / (hi - lo) in 0.12..0.92
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the figure as it looks [timeMs] into its drawing animation. */
    fun draw(canvas: Canvas, timeMs: Float) {
        for (e in frame) e.drawAt(canvas, timeMs)
        canvas.save()
        canvas.clipRect(if (bleed) RectF(0f, 0f, width, height) else RectF(plot.left - 1f, plot.top - 1f, plot.right + 1f, plot.bottom + 1f))
        for (e in elements) if (!e.label) e.drawAt(canvas, timeMs)
        canvas.restore()
        for (e in elements) if (e.label) e.drawAt(canvas, timeMs)
    }

    private abstract inner class Element(var start: Float, var end: Float, val label: Boolean = false) {
        fun drawAt(canvas: Canvas, t: Float) {
            if (t <= start) return
            val p = if (end <= start) 1f else ((t - start) / (end - start)).coerceIn(0f, 1f)
            render(canvas, p)
        }

        abstract fun render(canvas: Canvas, p: Float)
    }

    /** A stroked path revealed along its length, with a pen dot at the tip while it is drawn. */
    private inner class StrokeEl(val path: Path, val color: Int, val widthDp: Float, val dashed: Boolean, start: Float, end: Float, val pen: Boolean = true) : Element(start, end) {
        private val contours = ArrayList<PathMeasure>()
        private val lengths = ArrayList<Float>()
        private val total: Float

        init {
            val pm = PathMeasure(path, false)
            var sum = 0f
            do {
                val len = pm.length
                if (len > 0f) {
                    val c = Path()
                    pm.getSegment(0f, len, c, true)
                    contours += PathMeasure(c, false)
                    lengths += len
                    sum += len
                }
            } while (pm.nextContour())
            total = sum
        }

        private val partial = Path()
        private val pos = FloatArray(2)

        override fun render(canvas: Canvas, p: Float) {
            stroke.color = color
            stroke.strokeWidth = widthDp * dp
            stroke.pathEffect = if (dashed) dash else null
            if (p >= 1f || total <= 0f) {
                canvas.drawPath(path, stroke)
                stroke.pathEffect = null
                return
            }
            var left = total * p
            partial.rewind()
            var tipSet = false
            for (i in contours.indices) {
                val len = lengths[i]
                val take = min(len, left)
                if (take <= 0f) break
                contours[i].getSegment(0f, take, partial, true)
                if (take < len || i == contours.lastIndex || left - take <= 0f) {
                    contours[i].getPosTan(take, pos, null)
                    tipSet = true
                }
                left -= take
            }
            canvas.drawPath(partial, stroke)
            stroke.pathEffect = null
            if (pen && tipSet) {
                fill.color = color
                canvas.drawCircle(pos[0], pos[1], 3.2f * dp, fill)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(pos[0], pos[1], 1.4f * dp, fill)
            }
        }
    }

    private inner class FillEl(val path: Path, val color: Int, val alpha: Float, start: Float, end: Float) : Element(start, end) {
        override fun render(canvas: Canvas, p: Float) {
            fill.color = withAlpha(color, alpha * p)
            canvas.drawPath(path, fill)
        }
    }

    /** A dot that pops in with a little overshoot, with a ring of the bubble colour so overlapping marks stay apart. */
    private inner class DotEl(val x: Float, val y: Float, val r: Float, val color: Int, start: Float, end: Float) : Element(start, end) {
        override fun render(canvas: Canvas, p: Float) {
            val s = if (p >= 1f) 1f else (sin(p * PI * 0.75) / sin(PI * 0.75 * 0.666)).toFloat().coerceAtMost(1.25f)
            fill.color = SURFACE
            canvas.drawCircle(x, y, (r + 1f * dp) * s, fill)
            fill.color = color
            canvas.drawCircle(x, y, r * s, fill)
        }
    }

    /** A bar growing from the baseline. */
    private inner class BarEl(val rect: RectF, val baseline: Float, val color: Int, start: Float, end: Float) : Element(start, end) {
        private val r = RectF()
        private val path = Path()
        override fun render(canvas: Canvas, p: Float) {
            val e = easeOut(p)
            val up = rect.top < baseline
            r.set(rect.left, if (up) baseline - (baseline - rect.top) * e else baseline, rect.right, if (up) baseline else baseline + (rect.bottom - baseline) * e)
            val c = min(2f * dp, r.width() / 2)
            path.rewind()
            // rounded at the data end, square on the baseline
            val radii = if (up) floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f) else floatArrayOf(0f, 0f, 0f, 0f, c, c, c, c)
            path.addRoundRect(r, radii, Path.Direction.CW)
            fill.color = color
            canvas.drawPath(path, fill)
        }
    }

    private inner class SliceEl(val oval: RectF, val from: Float, val sweep: Float, val color: Int, val hole: Float, start: Float, end: Float) : Element(start, end) {
        private val path = Path()
        override fun render(canvas: Canvas, p: Float) {
            val s = sweep * easeOut(p)
            if (s <= 0f) return
            path.rewind()
            val cx = oval.centerX()
            val cy = oval.centerY()
            val r = oval.width() / 2
            val inner = RectF(cx - r * hole, cy - r * hole, cx + r * hole, cy + r * hole)
            path.arcTo(oval, from, s, true)
            path.arcTo(inner, from + s, -s, false)
            path.close()
            fill.color = color
            canvas.drawPath(path, fill)
        }
    }

    /** Text (with $LaTeX$ pieces) that fades in; [ax], [ay] anchor it: 0 = left/top, 0.5 = centre, 1 = right/bottom. */
    private inner class LabelEl(
        val run: LabelRun, val x: Float, val y: Float, val ax: Float, val ay: Float, val color: Int, start: Float, end: Float,
        val withHalo: Boolean = true,
    ) : Element(start, end, label = true) {
        override fun render(canvas: Canvas, p: Float) {
            val left = x - run.width * ax
            val baseline = y - (run.ascent + run.descent) * ay + run.ascent
            run.draw(canvas, left, baseline, withAlpha(color, p), if (withHalo) withAlpha(SURFACE, p * 0.92f) else 0)
        }
    }

    /** A line of text and formulas measured once. */
    private inner class LabelRun(text: String, val paint: Paint) {
        private val pieces = ArrayList<Any>() // String or MathLayout
        var width = 0f
        var ascent = 0f
        var descent = 0f
        private val px = paint.textSize

        init {
            val fm = paint.fontMetrics
            ascent = -fm.ascent * 0.82f
            descent = fm.descent * 0.6f
            for (inline in Markdown.parseInlines(text)) {
                when (inline) {
                    is Text -> { pieces += inline.text; width += paint.measureText(inline.text) }
                    is Code -> { pieces += inline.text; width += paint.measureText(inline.text) }
                    is Math -> {
                        val layout = env.layout(inline.latex, display = false)
                        pieces += layout
                        width += layout.width * px
                        ascent = max(ascent, layout.ascent * px)
                        descent = max(descent, layout.descent * px)
                    }
                    else -> Unit
                }
            }
        }

        fun draw(canvas: Canvas, left: Float, baseline: Float, color: Int, haloColor: Int) {
            var x = left
            for (piece in pieces) {
                if (piece is String) {
                    if (haloColor != 0) {
                        halo.color = haloColor
                        halo.textSize = paint.textSize
                        halo.typeface = paint.typeface
                        canvas.drawText(piece, x, baseline, halo)
                    }
                    paint.color = color
                    canvas.drawText(piece, x, baseline, paint)
                    x += paint.measureText(piece)
                } else {
                    val layout = piece as MathLayout
                    env.draw(canvas, layout, x, baseline, px, color)
                    x += layout.width * px
                }
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        if (figure.pie != null) {
            buildPie(figure.pie)
            return
        }
        if (figure.axes || figure.grid) buildFrame()
        val axesEnd = clock
        clock = if (frame.isEmpty()) 0f else axesEnd * 0.8f
        var slot = 0
        for (item in figure.items) {
            val before = clock
            when (item) {
                is FunctionItem -> buildFunction(item)
                is Parametric, is Polar -> buildCurve(item)
                is Polyline -> buildPolyline(item)
                is Scatter -> buildScatter(item)
                is Bars -> { buildBars(item, slot, figure.bars.size); slot++ }
                is PointItem -> buildPoint(item)
                is TextItem -> buildText(item)
                is Segment -> buildSegment(item)
                is Rect -> buildRect(item)
                is RuleLine -> buildRule(item)
                is Circle -> buildCircle(item)
                is Arc -> buildArc(item)
                is AngleMark -> buildAngle(item)
                is Polygon -> buildPolygon(item)
                is PathItem -> buildPath(item)
                is Pie -> Unit
            }
            // the next item starts a little before this one ends, so the drawing flows
            if (clock > before) clock = max(before, clock - 60f)
        }
        durationMs = max(durationMs, clock + 60f)
    }

    private fun step(duration: Float): Pair<Float, Float> {
        val s = clock
        clock += duration
        durationMs = max(durationMs, clock)
        return s to clock
    }

    private fun label(text: String, x: Float, y: Float, ax: Float, ay: Float, at: Float, color: Int = Palette.INK, paint: Paint = labelPaint) {
        if (text.isBlank()) return
        val run = LabelRun(text, paint)
        // keep it on the canvas
        val left = (x - run.width * ax).coerceIn(1f, max(1f, width - run.width - 1f))
        val h = run.ascent + run.descent
        val top = (y - h * ay).coerceIn(1f, max(1f, height - h - 1f))
        elements += LabelEl(run, left, top, 0f, 0f, color, at, at + 220f)
        durationMs = max(durationMs, at + 220f)
    }

    // ------------------------------------------------ axes and grid

    private fun buildFrame() {
        val bars = figure.bars.isNotEmpty()
        val maxX = max(2, (plot.width() / (if (mode == Mode.FULL) 44f else 38f) / dp).toInt())
        val maxY = max(2, (plot.height() / (if (mode == Mode.FULL) 36f else 26f) / dp).toInt())
        val xTicks = if (bars) emptyList() else FigureScale.ticks(view.x0, view.x1, maxX, pi = FigureScale.prefersPi(figure))
        val yTicks = FigureScale.ticks(view.y0, view.y1, maxY)
        val xInside = !bars && insideAxis(view.y0, view.y1, false)
        val yInside = !bars && insideAxis(view.x0, view.x1, false)
        // Where the axes run: through 0 when it is well inside, else along the frame (or, in the bleeding viewer, a line inside the circle).
        val axisY = when {
            xInside -> sy(0.0)
            bleed -> if (view.y0 >= 0) plot.bottom - plot.height() * 0.2f else plot.top + plot.height() * 0.2f
            bars -> sy(max(view.y0, min(view.y1, 0.0)))
            else -> plot.bottom
        }
        val axisX = when {
            yInside -> sx(0.0)
            bleed -> if (view.x0 >= 0) plot.left + plot.width() * 0.2f else plot.right - plot.width() * 0.2f
            else -> plot.left
        }
        val drawXAxis = xInside || bars || !bleed
        val drawYAxis = yInside || !bleed

        if (figure.grid) {
            val grid = Path()
            for (t in xTicks) { val x = sx(t.value); if (x > plot.left + 1 && x < plot.right - 1) { grid.moveTo(x, plot.top); grid.lineTo(x, plot.bottom) } }
            for (t in yTicks) { val y = sy(t.value); if (y > plot.top + 1 && y < plot.bottom - 1) { grid.moveTo(plot.left, y); grid.lineTo(plot.right, y) } }
            frame += FillLessGrid(grid, 0f, 260f)
        }
        if (!figure.axes) { clock = 260f; durationMs = clock; return }

        val axes = Path()
        if (drawXAxis) { axes.moveTo(plot.left, axisY); axes.lineTo(plot.right, axisY) }
        if (drawYAxis) { axes.moveTo(axisX, plot.bottom); axes.lineTo(axisX, plot.top) }
        if (xInside && yInside && !bars) {
            // small arrowheads at the positive ends, math style
            val a = 4f * dp
            axes.moveTo(plot.right - a, axisY - a * 0.7f); axes.lineTo(plot.right, axisY); axes.lineTo(plot.right - a, axisY + a * 0.7f)
            axes.moveTo(axisX - a * 0.7f, plot.top + a); axes.lineTo(axisX, plot.top); axes.lineTo(axisX + a * 0.7f, plot.top + a)
        }
        frame += AxisEl(axes, 0f, 320f)

        val cx = width / 2
        val cy = height / 2
        val radius = min(width, height) / 2 - 12f * dp
        fun visible(x: Float, y: Float) = mode != Mode.FULL || hypot(x - cx, y - cy) < radius
        val labelsAt = 180f
        val pad = 3f * dp
        for (t in xTicks) {
            if (xInside && abs(t.value) < 1e-12) continue // the origin's 0 sits on the y axis labels
            val x = sx(t.value)
            if (x < plot.left + 2 * dp || x > plot.right - 2 * dp) continue
            val y = if (xInside || bleed) axisY + pad else plot.bottom + pad
            if (visible(x, y)) frameLabel(t.label, x, y, 0.5f, 0f, labelsAt)
        }
        for (t in yTicks) {
            val y = sy(t.value)
            if (y < plot.top + 4 * dp || y > plot.bottom - 2 * dp) continue
            if ((xInside || bleed) && abs(y - axisY) < 4 * dp && t.value != 0.0) continue
            val inside = yInside || bleed
            val x = if (inside) axisX - pad else plot.left - pad
            val label = if (xInside && yInside && t.value == 0.0) "0" else t.label
            if (visible(x, y)) frameLabel(label, x, if (inside && t.value == 0.0 && xInside) axisY + pad else y, 1f, if (inside && t.value == 0.0 && xInside) 0f else 0.5f, labelsAt)
        }
        if (figure.xLabel.isNotBlank()) {
            val y = if (drawXAxis) axisY - 3f * dp else plot.bottom - 3f * dp
            val x = if (mode == Mode.FULL) min(plot.right, cx + radius * 0.8f) else plot.right - 2f * dp
            elements += labelEl(figure.xLabel, x, y, 1f, 1f, labelsAt, TICK, tickPaint)
        }
        if (figure.yLabel.isNotBlank()) {
            val x = if (drawYAxis) axisX + 4f * dp else plot.left + 4f * dp
            val y = if (mode == Mode.FULL) max(plot.top, cy - radius * 0.8f) else plot.top + 1f * dp
            elements += labelEl(figure.yLabel, x, y, 0f, 0f, labelsAt, TICK, tickPaint)
        }
        clock = 340f
        durationMs = max(durationMs, labelsAt + 220f)
    }

    private fun frameLabel(text: String, x: Float, y: Float, ax: Float, ay: Float, at: Float) {
        frame += labelEl(text, x, y, ax, ay, at, TICK, tickPaint)
    }

    private fun labelEl(text: String, x: Float, y: Float, ax: Float, ay: Float, at: Float, color: Int, paint: Paint): Element =
        LabelEl(LabelRun(text, paint), x, y, ax, ay, color, at, at + 200f)

    private inner class FillLessGrid(val path: Path, start: Float, end: Float) : Element(start, end) {
        override fun render(canvas: Canvas, p: Float) {
            stroke.color = withAlpha(0xFFFFFFFF.toInt(), 0.07f * p)
            stroke.strokeWidth = 0.6f * dp
            stroke.pathEffect = null
            canvas.drawPath(path, stroke)
        }
    }

    private inner class AxisEl(path: Path, start: Float, end: Float) : Element(start, end) {
        private val inner = StrokeEl(path, AXIS, 1f, false, start, end, pen = false)
        override fun render(canvas: Canvas, p: Float) = inner.render(canvas, easeOut(p))
    }

    // ------------------------------------------------ curves

    private fun pathOf(points: DoubleArray): Path {
        val path = Path()
        var pen = false
        for (i in 0 until points.size / 2) {
            val x = points[2 * i]
            val y = points[2 * i + 1]
            if (x.isNaN() || y.isNaN()) { pen = false; continue }
            val px = sx(x)
            val py = sy(y)
            if (pen) path.lineTo(px, py) else { path.moveTo(px, py); pen = true }
        }
        return path
    }

    private fun curveWidth() = if (mode == Mode.FULL) 2.2f else 1.9f

    private fun buildFunction(item: FunctionItem) {
        val lo = max(view.x0, item.domain?.min ?: view.x0)
        val hi = min(view.x1, item.domain?.max ?: view.x1)
        if (hi <= lo) return
        val n = (plot.width() * (hi - lo) / view.w).toInt().coerceIn(60, 1400)
        val pts = FigureScale.sampleFunction(item.expr, lo, hi, n, view.y0, view.y1)
        val (s, e) = step(780f)
        elements += StrokeEl(pathOf(pts), item.style.color, curveWidth(), item.style.dash, s, e)
        item.area?.let { area ->
            val a = max(area.min, view.x0 - view.w)
            val b = min(area.max, view.x1 + view.w)
            if (b > a) {
                val m = (plot.width() * (b - a) / view.w).toInt().coerceIn(30, 1000)
                val region = Path()
                val zero = sy(0.0.coerceIn(view.y0 - view.h, view.y1 + view.h))
                region.moveTo(sx(a), zero)
                for (k in 0..m) {
                    val x = a + (b - a) * k / m
                    val y = item.expr.eval(x)
                    region.lineTo(sx(x), if (y.isFinite()) sy(y.coerceIn(view.y0 - view.h, view.y1 + view.h)) else zero)
                }
                region.lineTo(sx(b), zero)
                region.close()
                val (fs, fe) = e - 100f to e + 260f
                elements += FillEl(region, item.style.color, 0.3f, fs, fe)
                durationMs = max(durationMs, fe)
            }
        }
    }

    private fun buildCurve(item: Item) {
        val n = 900
        val pts = FigureScale.sampleCurve(item, n, maxJump = hypot(view.w, view.h) / 3)
        val (s, e) = step(820f)
        elements += StrokeEl(pathOf(pts), item.style.color, curveWidth(), item.style.dash, s, e)
        if (item.style.fill) {
            elements += FillEl(pathOf(pts).apply { close() }, item.style.color, 0.22f, e - 80f, e + 260f)
            durationMs = max(durationMs, e + 260f)
        }
    }

    private fun buildPolyline(item: Polyline) {
        val n = item.points.size / 2
        val (s, e) = step((110f * n).coerceIn(420f, 950f))
        elements += StrokeEl(pathOf(item.points), item.style.color, curveWidth(), item.style.dash, s, e)
        if (n <= 16) {
            for (i in 0 until n) {
                val at = s + (e - s) * i / max(1, n - 1)
                elements += DotEl(sx(item.points[2 * i]), sy(item.points[2 * i + 1]), 2.3f * dp, item.style.color, at, at + 160f)
            }
        }
    }

    private fun buildScatter(item: Scatter) {
        val n = item.points.size / 2
        val gap = (650f / n).coerceIn(8f, 60f)
        val (s, _) = step(gap * n + 160f)
        for (i in 0 until n) {
            val at = s + gap * i
            elements += DotEl(sx(item.points[2 * i]), sy(item.points[2 * i + 1]), 2.6f * dp, item.style.color, at, at + 170f)
        }
    }

    private fun buildBars(item: Bars, series: Int, count: Int) {
        val n = item.values.size
        val slotW = plot.width() / max(1, figure.bars.maxOf { it.values.size })
        val group = slotW * 0.74f
        val gap = 2f * dp
        val barW = ((group - gap * (count - 1)) / count).coerceAtLeast(1.5f * dp)
        val base = sy(max(view.y0, min(view.y1, 0.0)))
        val stagger = (420f / n).coerceIn(30f, 90f)
        val (s, _) = step(stagger * n + 240f)
        for (i in 0 until n) {
            val left = plot.left + slotW * i + (slotW - group) / 2 + (barW + gap) * series
            val top = sy(item.values[i])
            val at = s + stagger * i
            elements += BarEl(RectF(left, min(top, base), left + barW, max(top, base)), base, item.style.color, at, at + 240f)
            if (count == 1 && n <= 8 && barW > 14f * dp) {
                val v = item.values[i]
                val text = FigureScale.format(v, if (abs(v) < 10 && v != kotlin.math.floor(v)) 1 else 0)
                label(text, left + barW / 2, if (v >= 0) top - 2f * dp else top + 2f * dp, 0.5f, if (v >= 0) 1f else 0f, at + 200f, TICK, tickPaint)
            }
            if (series == 0) {
                val name = figure.bars.firstNotNullOfOrNull { it.labels.getOrNull(i)?.takeIf { l -> l.isNotBlank() } } ?: continue
                val cx = plot.left + slotW * i + slotW / 2
                val fitted = ellipsize(name, tickPaint, slotW - 2f * dp)
                frame += labelEl(fitted, cx, plot.bottom + 3f * dp, 0.5f, 0f, 180f, TICK, tickPaint)
            }
        }
    }

    private fun buildPie(pie: Pie) {
        val r = min(plot.width(), plot.height()) / 2 * 0.94f
        val cx = plot.centerX()
        val cy = plot.centerY()
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val total = pie.values.sum()
        var angle = -90f
        val gapDeg = if (pie.values.size > 1) 1.2f else 0f
        clock = 0f
        for ((i, v) in pie.values.withIndex()) {
            val sweep = (v / total * 360.0).toFloat()
            if (sweep <= 0f) continue
            val d = (900f * sweep / 360f).coerceAtLeast(120f)
            val (s, e) = step(d)
            val color = Palette.series(i)
            elements += SliceEl(oval, angle + gapDeg / 2, max(0.5f, sweep - gapDeg), color, 0.56f, s, e)
            if (sweep >= 24f) {
                val mid = java.lang.Math.toRadians((angle + sweep / 2).toDouble())
                val lr = r * 0.78f
                val pct = v / total * 100
                val text = if (pct >= 9.5) "${pct.toInt()}%" else String.format(java.util.Locale.ROOT, "%.1f%%", pct).replace(".0%", "%")
                label(text, cx + (lr * cos(mid)).toFloat(), cy + (lr * sin(mid)).toFloat(), 0.5f, 0.5f, e - 60f, 0xFFFFFFFF.toInt(), tickPaint)
            }
            angle += sweep
        }
    }

    // ------------------------------------------------ shapes

    private fun shapeWidth() = if (mode == Mode.FULL) 1.8f else 1.5f

    private fun buildPoint(item: PointItem) {
        val (s, e) = step(200f)
        val x = sx(item.x)
        val y = sy(item.y)
        elements += DotEl(x, y, 3f * dp, item.style.color, s, e)
        if (item.style.label.isNotBlank()) {
            val right = x < plot.right - 30f * dp
            label(item.style.label, x + if (right) 4f * dp else -4f * dp, y - 3f * dp, if (right) 0f else 1f, 1f, e - 40f)
        }
    }

    private fun buildText(item: TextItem) {
        val (s, _) = step(240f)
        label(item.text, sx(item.x), sy(item.y), 0.5f, 0.5f, s, item.style.color)
    }

    private fun buildSegment(item: Segment) {
        val x1 = sx(item.x1); val y1 = sy(item.y1)
        val x2 = sx(item.x2); val y2 = sy(item.y2)
        val len = hypot(x2 - x1, y2 - y1)
        val (s, e) = step((len / dp * 4f).coerceIn(260f, 520f))
        val path = Path()
        val head = 7f * dp
        if (item.arrow && len > head * 1.5f) {
            val ux = (x2 - x1) / len
            val uy = (y2 - y1) / len
            path.moveTo(x1, y1)
            path.lineTo(x2 - ux * head * 0.8f, y2 - uy * head * 0.8f)
            elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
            val tip = Path().apply {
                val bx = x2 - ux * head
                val by = y2 - uy * head
                moveTo(x2, y2)
                lineTo(bx - uy * head * 0.45f, by + ux * head * 0.45f)
                lineTo(bx + uy * head * 0.45f, by - ux * head * 0.45f)
                close()
            }
            elements += FillEl(tip, item.style.color, 1f, e - 40f, e + 60f)
        } else {
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        }
        if (item.style.label.isNotBlank() && len > 0f) {
            // beside the middle, on the side away from the figure's centre
            val mx = (x1 + x2) / 2
            val my = (y1 + y2) / 2
            var nx = -(y2 - y1) / len
            var ny = (x2 - x1) / len
            if ((mx + nx - plot.centerX()) * nx + (my + ny - plot.centerY()) * ny < 0) { nx = -nx; ny = -ny }
            val off = 6f * dp
            label(item.style.label, mx + nx * off, my + ny * off, if (abs(nx) < 0.3f) 0.5f else if (nx > 0) 0f else 1f, if (abs(ny) < 0.3f) 0.5f else if (ny > 0) 0f else 1f, e - 40f)
        }
    }

    private fun buildRule(item: RuleLine) {
        val path = Path()
        val (s, e) = step(320f)
        if (item.vertical) {
            val x = sx(item.value)
            path.moveTo(x, plot.bottom); path.lineTo(x, plot.top)
            elements += StrokeEl(path, item.style.color, 1.2f, item.style.dash, s, e, pen = false)
            label(item.style.label, x + 3f * dp, plot.top + 2f * dp, 0f, 0f, e - 40f, item.style.color)
        } else {
            val y = sy(item.value)
            path.moveTo(plot.left, y); path.lineTo(plot.right, y)
            elements += StrokeEl(path, item.style.color, 1.2f, item.style.dash, s, e, pen = false)
            label(item.style.label, plot.right - 3f * dp, y - 2f * dp, 1f, 1f, e - 40f, item.style.color)
        }
    }

    private fun buildRect(item: Rect) {
        val r = RectF(sx(item.x1), sy(item.y2), sx(item.x2), sy(item.y1))
        val path = Path().apply { addRoundRect(r, 4f * dp, 4f * dp, Path.Direction.CW) }
        val (s, e) = step(380f)
        elements += FillEl(path, if (item.style.fill) item.style.color else 0xFFFFFFFF.toInt(), if (item.style.fill) 0.22f else 0.06f, s, e)
        elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        if (item.style.label.isNotBlank()) {
            val paint = Paint(labelPaint)
            val run = LabelRun(item.style.label, paint)
            if (run.width > r.width() - 6f * dp) paint.textSize = max(7.5f * dp, paint.textSize * (r.width() - 6f * dp) / run.width)
            val text = ellipsize(item.style.label, paint, r.width() - 4f * dp)
            elements += LabelEl(LabelRun(text, paint), r.centerX(), r.centerY(), 0.5f, 0.5f, Palette.INK, e - 60f, e + 160f, withHalo = false)
            durationMs = max(durationMs, e + 160f)
        }
    }

    private fun buildCircle(item: Circle) {
        val oval = RectF(sx(item.cx - item.r), sy(item.cy + item.r), sx(item.cx + item.r), sy(item.cy - item.r))
        val path = Path().apply { addArc(oval, -90f, 359.99f) }
        val (s, e) = step(520f)
        elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        if (item.style.fill) {
            elements += FillEl(Path().apply { addOval(oval, Path.Direction.CW) }, item.style.color, 0.2f, e - 80f, e + 240f)
            durationMs = max(durationMs, e + 240f)
        }
        val a = PI / 4
        label(item.style.label, oval.centerX() + (oval.width() / 2 * cos(a)).toFloat() + 2f * dp, oval.centerY() - (oval.height() / 2 * sin(a)).toFloat() - 2f * dp, 0f, 1f, e - 40f, item.style.color.takeIf { it != Palette.INK } ?: Palette.INK)
    }

    private fun buildArc(item: Arc) {
        val oval = RectF(sx(item.cx - item.r), sy(item.cy + item.r), sx(item.cx + item.r), sy(item.cy - item.r))
        val sweep = (item.end - item.start).toFloat()
        val path = Path().apply { addArc(oval, -item.start.toFloat(), -sweep) }
        val (s, e) = step((abs(sweep) * 2.2f).coerceIn(240f, 600f))
        elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        val mid = java.lang.Math.toRadians((item.start + item.end) / 2)
        label(item.style.label, oval.centerX() + ((oval.width() / 2 + 6f * dp) * cos(mid)).toFloat(), oval.centerY() - ((oval.height() / 2 + 6f * dp) * sin(mid)).toFloat(), 0.5f, 0.5f, e - 40f)
    }

    private fun buildAngle(item: AngleMark) {
        val vx = sx(item.vx); val vy = sy(item.vy)
        val a1 = atan2((sy(item.ay) - vy).toDouble(), (sx(item.ax) - vx).toDouble())
        val a2 = atan2((sy(item.by) - vy).toDouble(), (sx(item.bx) - vx).toDouble())
        var d = a2 - a1
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        val r = 13f * dp
        val path = Path()
        val right = abs(abs(d) - PI / 2) < java.lang.Math.toRadians(1.0)
        if (right) {
            val s = 8f * dp
            val ux = cos(a1).toFloat(); val uy = sin(a1).toFloat()
            val wx = cos(a2).toFloat(); val wy = sin(a2).toFloat()
            path.moveTo(vx + ux * s, vy + uy * s)
            path.lineTo(vx + (ux + wx) * s, vy + (uy + wy) * s)
            path.lineTo(vx + wx * s, vy + wy * s)
        } else {
            path.addArc(RectF(vx - r, vy - r, vx + r, vy + r), java.lang.Math.toDegrees(a1).toFloat(), java.lang.Math.toDegrees(d).toFloat())
        }
        val (s, e) = step(340f)
        elements += StrokeEl(path, item.style.color, 1.4f, item.style.dash, s, e)
        if (!right && item.style.fill) {
            val wedge = Path().apply { moveTo(vx, vy); arcTo(RectF(vx - r, vy - r, vx + r, vy + r), java.lang.Math.toDegrees(a1).toFloat(), java.lang.Math.toDegrees(d).toFloat()); close() }
            elements += FillEl(wedge, item.style.color, 0.25f, e - 60f, e + 200f)
        }
        val mid = a1 + d / 2
        val lr = r + 8f * dp
        label(item.style.label, vx + (lr * cos(mid)).toFloat(), vy + (lr * sin(mid)).toFloat(), 0.5f, 0.5f, e - 40f, item.style.color)
    }

    private fun buildPolygon(item: Polygon) {
        val path = pathOf(item.points).apply { close() }
        val n = item.points.size / 2
        val (s, e) = step((140f * n).coerceIn(420f, 900f))
        elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        if (item.style.fill) {
            elements += FillEl(path, item.style.color, 0.22f, e - 80f, e + 260f)
            durationMs = max(durationMs, e + 260f)
        }
        if (item.style.label.isNotBlank()) {
            var cx = 0.0; var cy = 0.0
            for (i in 0 until n) { cx += item.points[2 * i]; cy += item.points[2 * i + 1] }
            label(item.style.label, sx(cx / n), sy(cy / n), 0.5f, 0.5f, e - 40f)
        }
    }

    private fun buildPath(item: PathItem) {
        val path = Path()
        for (seg in item.segments) when (seg) {
            is PathSeg.Move -> path.moveTo(sx(seg.x), sy(seg.y))
            is PathSeg.Line -> path.lineTo(sx(seg.x), sy(seg.y))
            is PathSeg.Cubic -> path.cubicTo(sx(seg.x1), sy(seg.y1), sx(seg.x2), sy(seg.y2), sx(seg.x), sy(seg.y))
            PathSeg.Close -> path.close()
        }
        val (s, e) = step(700f)
        elements += StrokeEl(path, item.style.color, shapeWidth(), item.style.dash, s, e)
        if (item.style.fill) {
            elements += FillEl(path, item.style.color, 0.22f, e - 80f, e + 260f)
            durationMs = max(durationMs, e + 260f)
        }
        val b = RectF()
        @Suppress("DEPRECATION") path.computeBounds(b, true)
        label(item.style.label, b.centerX(), b.top - 3f * dp, 0.5f, 1f, e - 40f)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth || text.contains('$')) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

    companion object {
        /** The assistant bubble colour: text halos and dot rings cut marks out of it. */
        val SURFACE = 0xFF1E1F24.toInt()
        val AXIS = 0x80FFFFFF.toInt()
        val TICK = 0xFFA39E95.toInt()

        fun withAlpha(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }

        /** Width/height the inline figure asks for (pies are square, geometry follows its own shape). */
        fun aspect(figure: Figure, view: View): Float = when {
            figure.pie != null -> 0.82f
            figure.equal && figure.bars.isEmpty() -> (view.h / view.w).toFloat().coerceIn(0.55f, 1.05f)
            else -> 0.74f
        }
    }
}
