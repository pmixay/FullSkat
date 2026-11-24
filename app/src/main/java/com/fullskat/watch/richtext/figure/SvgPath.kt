package com.fullskat.watch.richtext.figure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** One piece of a path in figure coordinates. Quadratics and arcs are turned into cubics when parsed. */
sealed interface PathSeg {
    data class Move(val x: Double, val y: Double) : PathSeg
    data class Line(val x: Double, val y: Double) : PathSeg
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathSeg
    data object Close : PathSeg
}

/**
 * SVG path data (M L H V C S Q T A Z, absolute and relative) to [PathSeg]s. The coordinates are the figure's
 * own (math-style, y up); mapping to the screen happens later, so arcs are converted here, before any scaling.
 * Malformed data ends the path where it breaks.
 */
object SvgPath {
    private const val MAX_SEGMENTS = 2000

    fun parse(d: String): List<PathSeg> {
        val out = ArrayList<PathSeg>()
        val t = Tokens(d)
        var cx = 0.0; var cy = 0.0 // current point
        var sx = 0.0; var sy = 0.0 // subpath start
        var lastCtrlX = 0.0; var lastCtrlY = 0.0
        var lastCmd = ' '
        var cmd = ' '
        while (out.size < MAX_SEGMENTS) {
            val next = t.command()
            if (next != null) cmd = next else if (!t.hasNumber() || cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0
            val oy = if (rel) cy else 0.0
            try {
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        cx = ox + t.num(); cy = oy + t.num()
                        sx = cx; sy = cy
                        out += PathSeg.Move(cx, cy)
                        cmd = if (rel) 'l' else 'L' // further pairs are line-tos
                    }
                    'L' -> { cx = ox + t.num(); cy = oy + t.num(); out += PathSeg.Line(cx, cy) }
                    'H' -> { cx = ox + t.num(); out += PathSeg.Line(cx, cy) }
                    'V' -> { cy = oy + t.num(); out += PathSeg.Line(cx, cy) }
                    'C' -> {
                        val x1 = ox + t.num(); val y1 = oy + t.num()
                        val x2 = ox + t.num(); val y2 = oy + t.num()
                        cx = ox + t.num(); cy = oy + t.num()
                        out += PathSeg.Cubic(x1, y1, x2, y2, cx, cy)
                        lastCtrlX = x2; lastCtrlY = y2
                    }
                    'S' -> {
                        val smooth = lastCmd.uppercaseChar() in "CS"
                        val x1 = if (smooth) 2 * cx - lastCtrlX else cx
                        val y1 = if (smooth) 2 * cy - lastCtrlY else cy
                        val x2 = ox + t.num(); val y2 = oy + t.num()
                        cx = ox + t.num(); cy = oy + t.num()
                        out += PathSeg.Cubic(x1, y1, x2, y2, cx, cy)
                        lastCtrlX = x2; lastCtrlY = y2
                    }
                    'Q', 'T' -> {
                        val qx: Double
                        val qy: Double
                        if (cmd.uppercaseChar() == 'Q') {
                            qx = ox + t.num(); qy = oy + t.num()
                        } else {
                            val smooth = lastCmd.uppercaseChar() in "QT"
                            qx = if (smooth) 2 * cx - lastCtrlX else cx
                            qy = if (smooth) 2 * cy - lastCtrlY else cy
                        }
                        val x = ox + t.num(); val y = oy + t.num()
                        out += PathSeg.Cubic(cx + 2.0 / 3 * (qx - cx), cy + 2.0 / 3 * (qy - cy), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y)
                        cx = x; cy = y
                        lastCtrlX = qx; lastCtrlY = qy
                    }
                    'A' -> {
                        val rx = abs(t.num()); val ry = abs(t.num())
                        val rot = t.num()
                        val large = t.flag(); val sweep = t.flag()
                        val x = ox + t.num(); val y = oy + t.num()
                        arc(out, cx, cy, rx, ry, rot, large, sweep, x, y)
                        cx = x; cy = y
                    }
                    'Z' -> { out += PathSeg.Close; cx = sx; cy = sy }
                    else -> break
                }
            } catch (_: NumberFormatException) {
                break
            }
            lastCmd = cmd
            if (cmd.uppercaseChar() == 'Z') cmd = ' ' // Z takes no numbers
        }
        return if (out.firstOrNull() is PathSeg.Move) out else emptyList()
    }

    /** SVG endpoint arc to cubic Béziers (SVG 1.1 implementation notes, F.6.5), at most 90° per piece. */
    private fun arc(out: MutableList<PathSeg>, x1: Double, y1: Double, rxIn: Double, ryIn: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
        if (x1 == x2 && y1 == y2) return
        var rx = rxIn
        var ry = ryIn
        if (rx == 0.0 || ry == 0.0) { out += PathSeg.Line(x2, y2); return }
        val phi = rotDeg * PI / 180
        val cosP = cos(phi)
        val sinP = sin(phi)
        val dx = (x1 - x2) / 2
        val dy = (y1 - y2) / 2
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2
        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var delta = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - theta1
        if (sweep && delta < 0) delta += 2 * PI else if (!sweep && delta > 0) delta -= 2 * PI
        val pieces = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = delta / pieces
        val k = 4.0 / 3 * tan(step / 4)
        var a = theta1
        repeat(pieces) {
            val b = a + step
            val (ax, ay) = ellipsePoint(cx, cy, rx, ry, cosP, sinP, a)
            val (bx, by) = ellipsePoint(cx, cy, rx, ry, cosP, sinP, b)
            // derivatives at a and b
            val dax = -rx * sin(a) * cosP - ry * cos(a) * sinP
            val day = -rx * sin(a) * sinP + ry * cos(a) * cosP
            val dbx = -rx * sin(b) * cosP - ry * cos(b) * sinP
            val dby = -rx * sin(b) * sinP + ry * cos(b) * cosP
            out += PathSeg.Cubic(ax + k * dax, ay + k * day, bx - k * dbx, by - k * dby, bx, by)
            a = b
        }
    }

    private fun ellipsePoint(cx: Double, cy: Double, rx: Double, ry: Double, cosP: Double, sinP: Double, t: Double): Pair<Double, Double> =
        (cx + rx * cos(t) * cosP - ry * sin(t) * sinP) to (cy + rx * cos(t) * sinP + ry * sin(t) * cosP)

    private class Tokens(val s: String) {
        var i = 0

        private fun skip() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun command(): Char? {
            skip()
            if (i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E') return s[i++]
            return null
        }

        fun hasNumber(): Boolean {
            skip()
            return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.')
        }

        fun num(): Double {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && !dot))) { if (s[i] == '.') dot = true; i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val v = s.substring(start, i).toDoubleOrNull() ?: throw NumberFormatException("bad number at $start")
            if (!v.isFinite()) throw NumberFormatException("not finite")
            return v
        }

        /** Arc flags may be written without separators ("a1 1 0 01 2 2"). */
        fun flag(): Boolean {
            skip()
            if (i < s.length && (s[i] == '0' || s[i] == '1')) return s[i++] == '1'
            throw NumberFormatException("bad flag at $i")
        }
    }
}
