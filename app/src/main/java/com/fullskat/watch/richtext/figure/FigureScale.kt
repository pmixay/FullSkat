package com.fullskat.watch.richtext.figure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/** The visible part of the plane. */
data class View(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
    val w: Double get() = x1 - x0
    val h: Double get() = y1 - y0

    /** Zoomed by [factor] (> 1 zooms in) around the figure point (cx, cy). */
    fun zoom(factor: Double, cx: Double = (x0 + x1) / 2, cy: Double = (y0 + y1) / 2): View =
        View(cx - (cx - x0) / factor, cx + (x1 - cx) / factor, cy - (cy - y0) / factor, cy + (y1 - cy) / factor)

    fun pan(dx: Double, dy: Double) = View(x0 + dx, x1 + dx, y0 + dy, y1 + dy)

    /** Grown so that one unit is as long on both axes for a [widthPx] x [heightPx] plot (circles stay round). */
    fun equalized(widthPx: Float, heightPx: Float): View {
        val sx = widthPx / w
        val sy = heightPx / h
        return if (sx > sy) {
            val nw = widthPx / sy
            val c = (x0 + x1) / 2
            View(c - nw / 2, c + nw / 2, y0, y1)
        } else {
            val nh = heightPx / sx
            val c = (y0 + y1) / 2
            View(x0, x1, c - nh / 2, c + nh / 2)
        }
    }
}

/** A tick: its value and its label. */
data class Tick(val value: Double, val label: String)

object FigureScale {
    private const val DEFAULT_SPAN = 5.0

    /**
     * The view the figure asks for: its x_range/y_range where given; otherwise the bounds of everything in it,
     * padded, with function graphs fitted robustly in y (asymptotes do not flatten the rest) and bars from zero.
     */
    fun fit(f: Figure): View {
        val b = Bounds()
        val bars = f.bars
        if (bars.isNotEmpty()) {
            val n = bars.maxOf { it.values.size }
            b.addX(-0.5); b.addX(n - 0.5)
            b.addY(0.0)
            for (s in bars) for (v in s.values) b.addY(v)
        }
        for (item in f.items) shapeBounds(item, b)
        val curves = f.items.filter { it is Parametric || it is Polar }
        for (c in curves) for (p in curvePoints(c, 240)) if (p.first.isFinite() && p.second.isFinite()) { b.addX(p.first); b.addY(p.second) }

        val functions = f.items.filterIsInstance<FunctionItem>()
        val xr = f.xRange ?: when {
            b.hasX -> pad(b.xMin, b.xMax, if (bars.isNotEmpty()) 0.0 else 0.08)
            else -> Range(-DEFAULT_SPAN, DEFAULT_SPAN)
        }
        val yr = f.yRange ?: run {
            val ys = DoubleArrayList()
            for (fn in functions) {
                val lo = max(xr.min, fn.domain?.min ?: xr.min)
                val hi = min(xr.max, fn.domain?.max ?: xr.max)
                if (hi <= lo) continue
                for (k in 0..400) {
                    val y = fn.expr.eval(lo + (hi - lo) * k / 400)
                    if (y.isFinite()) ys.add(y)
                }
            }
            val fy = robustRange(ys.toArray())
            if (fy != null) { b.addY(fy.min); b.addY(fy.max) }
            when {
                !b.hasY -> Range(-DEFAULT_SPAN, DEFAULT_SPAN)
                bars.isNotEmpty() -> {
                    val r = pad(b.yMin, b.yMax, 0.1)
                    Range(if (b.yMin >= 0) 0.0 else r.min, if (b.yMax <= 0) 0.0 else r.max)
                }
                else -> pad(b.yMin, b.yMax, 0.1)
            }
        }
        return View(xr.min, xr.max, yr.min, yr.max)
    }

    /** Extent of a function's values without its outliers: quartiles ± 3 IQR, cut to what was really seen. */
    fun robustRange(values: DoubleArray): Range? {
        if (values.isEmpty()) return null
        values.sort()
        val q1 = values[(values.size - 1) / 4]
        val q3 = values[(values.size - 1) * 3 / 4]
        val iqr = q3 - q1
        val lo = max(values.first(), q1 - 3 * iqr)
        val hi = min(values.last(), q3 + 3 * iqr)
        return Range(lo, hi)
    }

    private fun pad(lo: Double, hi: Double, share: Double): Range {
        var a = lo
        var b = hi
        if (b - a < 1e-9) { val m = max(abs(a) * 0.5, 1.0); a -= m; b += m }
        val p = (b - a) * share
        return Range(a - p, b + p)
    }

    private fun shapeBounds(item: Item, b: Bounds) {
        when (item) {
            is Polyline -> b.addPoints(item.points)
            is Scatter -> b.addPoints(item.points)
            is Polygon -> b.addPoints(item.points)
            is PointItem -> { b.addX(item.x); b.addY(item.y) }
            is TextItem -> { b.addX(item.x); b.addY(item.y) }
            is Segment -> { b.addX(item.x1); b.addX(item.x2); b.addY(item.y1); b.addY(item.y2) }
            is Rect -> { b.addX(item.x1); b.addX(item.x2); b.addY(item.y1); b.addY(item.y2) }
            is Circle -> { b.addX(item.cx - item.r); b.addX(item.cx + item.r); b.addY(item.cy - item.r); b.addY(item.cy + item.r) }
            is Arc -> { b.addX(item.cx - item.r); b.addX(item.cx + item.r); b.addY(item.cy - item.r); b.addY(item.cy + item.r) }
            is AngleMark -> { b.addX(item.vx); b.addY(item.vy); b.addX(item.ax); b.addY(item.ay); b.addX(item.bx); b.addY(item.by) }
            is RuleLine -> if (item.vertical) b.addX(item.value) else b.addY(item.value)
            is PathItem -> for (s in item.segments) when (s) {
                is PathSeg.Move -> { b.addX(s.x); b.addY(s.y) }
                is PathSeg.Line -> { b.addX(s.x); b.addY(s.y) }
                is PathSeg.Cubic -> { b.addX(s.x); b.addY(s.y); b.addX(s.x1); b.addY(s.y1); b.addX(s.x2); b.addY(s.y2) }
                PathSeg.Close -> Unit
            }
            is FunctionItem -> item.domain?.let { b.addX(it.min); b.addX(it.max) }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- ticks

    /** Ticks for [lo, hi] at most [maxTicks] of them, at 1/2/5 x 10^k steps, or at multiples of π/k when [pi]. */
    fun ticks(lo: Double, hi: Double, maxTicks: Int, pi: Boolean = false): List<Tick> {
        if (!(hi > lo) || maxTicks < 1) return emptyList()
        if (pi) piTicks(lo, hi, maxTicks)?.let { return it }
        val step = niceStep((hi - lo) / maxTicks)
        val decimals = max(0, -floor(log10(step) + 1e-9).toInt())
        val out = ArrayList<Tick>()
        var k = ceil(lo / step - 1e-9).toLong()
        while (true) {
            val v = k * step
            if (v > hi + step * 1e-9) break
            out += Tick(v, format(v, decimals))
            k++
            if (out.size > 50) break
        }
        return out
    }

    private fun niceStep(raw: Double): Double {
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val m = raw / base
        val nice = when {
            m <= 1.0 -> 1.0
            m <= 2.0 -> 2.0
            m <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    private fun piTicks(lo: Double, hi: Double, maxTicks: Int): List<Tick>? {
        // steps of π/4, π/2, π, 2π, 4π ... (as multiples of π/4)
        var quarters = 1L
        while ((hi - lo) / (quarters * PI / 4) > maxTicks) {
            quarters *= 2
            if (quarters > 1L shl 20) return null
        }
        val step = quarters * PI / 4
        val out = ArrayList<Tick>()
        var k = ceil(lo / step - 1e-9).toLong()
        while (k * step <= hi + 1e-9) {
            out += Tick(k * step, piLabel(k * quarters))
            k++
        }
        return out
    }

    /** "π/4", "π/2", "3π/4", "π", "−2π" for [q] quarters of π. */
    fun piLabel(q: Long): String {
        if (q == 0L) return "0"
        var num = abs(q)
        var den = 4L
        while (den > 1 && num % 2 == 0L) { num /= 2; den /= 2 }
        val sign = if (q < 0) "−" else ""
        val coef = if (num == 1L) "" else num.toString()
        return if (den == 1L) "$sign${coef}π" else "$sign${coef}π/$den"
    }

    /** Whether x ticks read better as multiples of π: the model gave the x range in π units (e.g. [−2π, 2π]). */
    fun prefersPi(f: Figure): Boolean {
        val r = f.xRange ?: return false
        val q = PI / 4
        fun onPi(v: Double) = v == 0.0 || abs(v / q - (v / q).roundToLong()) < 2e-3 * maxOf(1.0, abs(v / q))
        return onPi(r.min) && onPi(r.max) && maxOf(abs(r.min), abs(r.max)) > 1e-9
    }

    /** A tick label: no trailing zeros, a real minus sign, k/M for big round numbers. */
    fun format(v: Double, decimals: Int): String {
        if (abs(v) < 1e-12) return "0"
        val a = abs(v)
        val sign = if (v < 0) "−" else ""
        if (a >= 10_000 && decimals == 0) {
            return when {
                a >= 1e9 -> sign + trim(a / 1e9, 1) + "G"
                a >= 1e6 -> sign + trim(a / 1e6, 1) + "M"
                else -> sign + trim(a / 1e3, 1) + "k"
            }
        }
        return sign + trim(a, decimals.coerceAtMost(6))
    }

    private fun trim(v: Double, decimals: Int): String {
        val s = String.format(java.util.Locale.ROOT, "%.${decimals}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    // ---------------------------------------------------------------- sampling

    /**
     * Samples y = f(x) across [lo, hi] at [n] steps for a view [y0, y1]: a flat x, y array with NaN pairs where
     * the curve breaks (outside the domain, at a jump across the view such as tan's asymptotes). Domain edges
     * are found by bisection so sqrt(x) starts exactly at 0; points far outside the view are pulled to its margin
     * along the segment, so steep parts keep their direction.
     */
    fun sampleFunction(e: Expr, lo: Double, hi: Double, n: Int, y0: Double, y1: Double): DoubleArray {
        val span = y1 - y0
        val sink = Sink(y0 - span, y1 + span)
        var px = 0.0
        var py = Double.NaN
        for (k in 0..n) {
            val x = lo + (hi - lo) * k / n
            val y = e.eval(x)
            val finite = y.isFinite()
            val prevFinite = py.isFinite()
            when {
                k == 0 -> if (finite) sink.start(x, y)
                prevFinite && finite -> {
                    val jump = abs(y - py) > span * 1.2
                    val mid = e.eval((px + x) / 2)
                    val between = mid.isFinite() && mid >= min(py, y) - span * 0.1 && mid <= max(py, y) + span * 0.1
                    if (jump && !between) sink.start(x, y) else sink.lineTo(px, py, x, y) // an asymptote: two branches
                }
                prevFinite -> { // the domain ends between px and x
                    val (ex, ey) = edge(e, px, x, aFinite = true)
                    if (ey.isFinite()) sink.lineTo(px, py, ex, ey)
                    sink.brk()
                }
                finite -> { // the domain starts between px and x
                    val (ex, ey) = edge(e, px, x, aFinite = false)
                    if (ey.isFinite() && ex < x) { sink.start(ex, ey); sink.lineTo(ex, ey, x, y) } else sink.start(x, y)
                }
            }
            px = x
            py = y
        }
        return sink.out.toArray()
    }

    /** Collects a polyline with NaN breaks, keeping it inside the band [lo, hi]: segments leaving it are cut at its edge. */
    private class Sink(val lo: Double, val hi: Double) {
        val out = DoubleArrayList()
        private fun put(x: Double, y: Double) { out.add(x); out.add(y) }

        fun brk() { if (out.size > 0 && !out.last().isNaN()) { out.add(Double.NaN); out.add(Double.NaN) } }

        /** A new branch at (x, y); outside the band it only breaks: the next segment enters at the band's edge. */
        fun start(x: Double, y: Double) { brk(); if (y in lo..hi) put(x, y) }

        /** Continues from (x1, y1), already added (possibly pulled to the band), to (x2, y2). */
        fun lineTo(x1: Double, y1: Double, x2: Double, y2: Double) {
            val out1 = y1 < lo || y1 > hi
            val out2 = y2 < lo || y2 > hi
            when {
                !out1 && !out2 -> put(x2, y2)
                out1 && out2 -> if ((y1 < lo) != (y2 < lo)) { // crosses the whole band between two samples
                    val e1 = if (y1 < lo) lo else hi
                    val e2 = if (y2 < lo) lo else hi
                    brk(); put(cross(x1, y1, x2, y2, e1), e1); put(cross(x1, y1, x2, y2, e2), e2); brk()
                } else brk()
                out1 -> { val edge = if (y1 < lo) lo else hi; brk(); put(cross(x1, y1, x2, y2, edge), edge); put(x2, y2) }
                else -> { val edge = if (y2 < lo) lo else hi; put(cross(x1, y1, x2, y2, edge), edge); brk() }
            }
        }

        private fun cross(x1: Double, y1: Double, x2: Double, y2: Double, y: Double): Double =
            if (y2 == y1) x2 else x1 + (x2 - x1) * (y - y1) / (y2 - y1)
    }

    /** Bisects between a finite and a non-finite sample for the last finite point. */
    private fun edge(e: Expr, a: Double, b: Double, aFinite: Boolean): Pair<Double, Double> {
        var good = if (aFinite) a else b
        var bad = if (aFinite) b else a
        repeat(24) {
            val m = (good + bad) / 2
            if (e.eval(m).isFinite()) good = m else bad = m
        }
        return good to e.eval(good)
    }

    /** Points of a parametric or polar curve over its t range (NaN pairs where it is undefined). */
    fun curvePoints(item: Item, n: Int): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(n + 1)
        when (item) {
            is Parametric -> for (k in 0..n) {
                val t = item.t.min + item.t.span * k / n
                out += item.x.eval(t) to item.y.eval(t)
            }
            is Polar -> for (k in 0..n) {
                val t = item.t.min + item.t.span * k / n
                val r = item.r.eval(t)
                out += r * cos(t) to r * sin(t)
            }
            else -> Unit
        }
        return out
    }

    /** A curve as a flat array with NaN breaks where it is undefined or jumps further than [maxJump] (figure units). */
    fun sampleCurve(item: Item, n: Int, maxJump: Double): DoubleArray {
        val out = DoubleArrayList()
        var lastX = Double.NaN
        var lastY = Double.NaN
        for ((x, y) in curvePoints(item, n)) {
            if (!x.isFinite() || !y.isFinite()) {
                if (out.size > 0 && !out.last().isNaN()) { out.add(Double.NaN); out.add(Double.NaN) }
                lastX = Double.NaN
                continue
            }
            if (lastX.isFinite() && hypot(x - lastX, y - lastY) > maxJump && out.size > 0) { out.add(Double.NaN); out.add(Double.NaN) }
            out.add(x); out.add(y)
            lastX = x
            lastY = y
        }
        return out.toArray()
    }

    private class Bounds {
        var xMin = Double.POSITIVE_INFINITY; var xMax = Double.NEGATIVE_INFINITY
        var yMin = Double.POSITIVE_INFINITY; var yMax = Double.NEGATIVE_INFINITY
        val hasX get() = xMin <= xMax
        val hasY get() = yMin <= yMax
        fun addX(v: Double) { if (v.isFinite()) { xMin = min(xMin, v); xMax = max(xMax, v) } }
        fun addY(v: Double) { if (v.isFinite()) { yMin = min(yMin, v); yMax = max(yMax, v) } }
        fun addPoints(p: DoubleArray) { for (i in 0 until p.size / 2) { addX(p[2 * i]); addY(p[2 * i + 1]) } }
    }
}

/** A growable primitive double list (sampling makes many points; boxing them would be wasteful). */
class DoubleArrayList(capacity: Int = 256) {
    private var a = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun last(): Double = a[size - 1]
    fun toArray(): DoubleArray = a.copyOf(size)
}
