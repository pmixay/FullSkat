package com.fullskat.core.richtext.figure

/**
 * A figure the model drew with the `draw` tool (see server/fullskat_server/figures.py): one math-style plane
 * (y up) holding curves, data series and shapes, drawn in [items] order. Parsed leniently: an item that
 * cannot be drawn is skipped, so one bad item never costs the whole picture.
 */
class Figure(
    val title: String,
    val xRange: Range?,
    val yRange: Range?,
    val xLabel: String,
    val yLabel: String,
    val axes: Boolean,
    val grid: Boolean,
    val equal: Boolean,
    val items: List<Item>,
) {
    /** A pie is drawn on its own: no axes, no plane. */
    val pie: Pie? = items.firstOrNull { it is Pie } as Pie?

    /** Bar charts put categories on the x axis. */
    val bars: List<Bars> = items.filterIsInstance<Bars>()

    /** Entries for the legend under the figure: series with a label (curves, data, bars), or a pie's slices. */
    val legend: List<Pair<Int, String>> = pie?.let { p -> p.values.indices.map { Palette.series(it) to p.labels.getOrElse(it) { "" } }.filter { it.second.isNotBlank() } }
        ?: items.filter { it.isSeries && it.style.label.isNotBlank() }.map { it.style.color to it.style.label }

    companion object {
        /** Parses a ```figure block; throws when it is not a figure at all (the block is then shown as code). */
        fun parse(json: String): Figure {
            val root = Json.parse(json) as? Map<*, *> ?: throw Json.Error("a figure is a JSON object")
            val rawItems = root["items"] as? List<*> ?: throw Json.Error("a figure needs items")
            var slot = 0
            val items = rawItems.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                runCatching { item(m) { if (it) slot++ else 0 } }.getOrNull()
            }
            if (items.isEmpty()) throw Json.Error("nothing to draw")
            val axes = root.bool("axes") ?: true
            return Figure(
                title = root.str("title"),
                xRange = root.range("x_range"),
                yRange = root.range("y_range"),
                xLabel = root.str("x_label"),
                yLabel = root.str("y_label"),
                axes = axes,
                grid = root.bool("grid") ?: axes,
                equal = root.bool("equal") ?: !axes,
                items = items,
            )
        }

        /** [nextSlot] hands out series colours in order (true = this item is a series and takes a slot). */
        private fun item(m: Map<*, *>, nextSlot: (Boolean) -> Int): Item? {
            val type = m["type"] as? String ?: return null
            val series = type in SERIES
            val explicit = Palette.named(m["color"] as? String)
            val slot = if (series && explicit == null) nextSlot(true) else 0
            val color = explicit ?: when {
                series -> Palette.series(slot)
                type == "point" || type == "angle" -> Palette.series(0)
                else -> Palette.INK
            }
            val style = Style(color, m.str("label"), m.bool("dash") == true, m.bool("fill") == true)
            return when (type) {
                "function" -> FunctionItem(ExprParser.parse(m.str("expr"), ExprParser.X), m.range("domain"), m.range("area"), style)
                "parametric" -> Parametric(
                    ExprParser.parse(m.str("x"), ExprParser.T), ExprParser.parse(m.str("y"), ExprParser.T),
                    m.range("t") ?: Range(0.0, 2 * Math.PI), style,
                )
                "polar" -> Polar(ExprParser.parse(m.str("expr").ifBlank { m.str("r") }, ExprParser.T), m.range("t") ?: Range(0.0, 2 * Math.PI), style)
                "line", "scatter", "polygon" -> {
                    val pts = m.points("points") ?: return null
                    when (type) {
                        "line" -> if (pts.size >= 4) Polyline(pts, style) else null
                        "scatter" -> if (pts.size >= 2) Scatter(pts, style) else null
                        else -> if (pts.size >= 6) Polygon(pts, style) else null
                    }
                }
                "bars", "pie" -> {
                    val values = (m["values"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble()?.takeIf(Double::isFinite) }?.toDoubleArray()
                        ?: return null
                    if (values.isEmpty()) return null
                    val labels = (m["labels"] as? List<*>)?.map { (it as? String).orEmpty() }.orEmpty()
                    if (type == "bars") Bars(values, labels, style) else if (values.sum() > 0 && values.all { it >= 0 }) Pie(values, labels, style) else null
                }
                "point" -> m.point("at")?.let { PointItem(it.first, it.second, style) }
                "text" -> m.point("at")?.let { TextItem(it.first, it.second, m.str("text").ifBlank { style.label }, style.copy(label = "")) }
                "segment", "arrow" -> {
                    val a = m.point("from") ?: return null
                    val b = m.point("to") ?: return null
                    Segment(a.first, a.second, b.first, b.second, arrow = type == "arrow", style)
                }
                "rect" -> {
                    val a = m.point("from") ?: return null
                    val b = m.point("to") ?: return null
                    Rect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second), style)
                }
                "vline" -> m.num("value")?.let { RuleLine(vertical = true, it, style) }
                "hline" -> m.num("value")?.let { RuleLine(vertical = false, it, style) }
                "circle" -> {
                    val c = m.point("center") ?: return null
                    val r = m.num("r")?.takeIf { it > 0 } ?: return null
                    Circle(c.first, c.second, r, style)
                }
                "arc" -> {
                    val c = m.point("center") ?: return null
                    val r = m.num("r")?.takeIf { it > 0 } ?: return null
                    Arc(c.first, c.second, r, m.num("start") ?: 0.0, m.num("end") ?: return null, style)
                }
                "angle" -> {
                    val v = m.point("at") ?: return null
                    val a = m.point("from") ?: return null
                    val b = m.point("to") ?: return null
                    AngleMark(v.first, v.second, a.first, a.second, b.first, b.second, style)
                }
                "path" -> SvgPath.parse(m.str("d")).takeIf { it.isNotEmpty() }?.let { PathItem(it, style) }
                else -> null
            }
        }

        private val SERIES = setOf("function", "parametric", "polar", "line", "scatter", "bars")

        private fun Map<*, *>.str(key: String): String = (this[key] as? String)?.trim().orEmpty()
        private fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean
        private fun Map<*, *>.num(key: String): Double? = (this[key] as? Number)?.toDouble()?.takeIf(Double::isFinite)

        private fun Map<*, *>.point(key: String): Pair<Double, Double>? {
            val l = this[key] as? List<*> ?: return null
            if (l.size != 2) return null
            val x = (l[0] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
            val y = (l[1] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
            return x to y
        }

        private fun Map<*, *>.range(key: String): Range? = point(key)?.let { (a, b) -> if (a < b) Range(a, b) else null }

        /** [[x, y], ...] as a flat x0, y0, x1, y1, ... array; bad pairs are dropped. */
        private fun Map<*, *>.points(key: String): DoubleArray? {
            val l = this[key] as? List<*> ?: return null
            val out = ArrayList<Double>(l.size * 2)
            for (p in l) {
                val pair = p as? List<*> ?: continue
                if (pair.size != 2) continue
                val x = (pair[0] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: continue
                val y = (pair[1] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: continue
                out += x
                out += y
            }
            return out.toDoubleArray()
        }
    }
}

data class Range(val min: Double, val max: Double) {
    val span: Double get() = max - min
}

data class Style(val color: Int, val label: String, val dash: Boolean, val fill: Boolean)

sealed interface Item {
    val style: Style

    /** Series (curves, data, bars) get palette colours in order and legend entries; shapes are labelled in place. */
    val isSeries: Boolean get() = this is FunctionItem || this is Parametric || this is Polar || this is Polyline || this is Scatter || this is Bars
}

class FunctionItem(val expr: Expr, val domain: Range?, val area: Range?, override val style: Style) : Item
class Parametric(val x: Expr, val y: Expr, val t: Range, override val style: Style) : Item
class Polar(val r: Expr, val t: Range, override val style: Style) : Item

/** [points] is flat: x0, y0, x1, y1, ... */
class Polyline(val points: DoubleArray, override val style: Style) : Item
class Scatter(val points: DoubleArray, override val style: Style) : Item
class Polygon(val points: DoubleArray, override val style: Style) : Item
class Bars(val values: DoubleArray, val labels: List<String>, override val style: Style) : Item
class Pie(val values: DoubleArray, val labels: List<String>, override val style: Style) : Item
class PointItem(val x: Double, val y: Double, override val style: Style) : Item
class TextItem(val x: Double, val y: Double, val text: String, override val style: Style) : Item
class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val arrow: Boolean, override val style: Style) : Item
class Rect(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val style: Style) : Item
class RuleLine(val vertical: Boolean, val value: Double, override val style: Style) : Item
class Circle(val cx: Double, val cy: Double, val r: Double, override val style: Style) : Item

/** Angles in degrees, counterclockwise from the +x axis. */
class Arc(val cx: Double, val cy: Double, val r: Double, val start: Double, val end: Double, override val style: Style) : Item

/** The angle at vertex (vx, vy) between the rays towards (ax, ay) and (bx, by). */
class AngleMark(val vx: Double, val vy: Double, val ax: Double, val ay: Double, val bx: Double, val by: Double, override val style: Style) : Item
class PathItem(val segments: List<PathSeg>, override val style: Style) : Item

/**
 * Colours for figures on the dark bubble: six categorical hues validated as a set against the bubble surface
 * (lightness band, colour-vision-deficiency separation of neighbours, 3:1 contrast), in this fixed order, plus
 * neutral ink for shapes, axes and text. Series always carry a legend or label, so colour is never alone.
 */
object Palette {
    private val SERIES = intArrayOf(
        0xFFC98500.toInt(), // amber
        0xFF3987E5.toInt(), // blue
        0xFFD55181.toInt(), // magenta
        0xFF9085E9.toInt(), // violet
        0xFF199E70.toInt(), // aqua
        0xFFE66767.toInt(), // red
    )
    val INK = 0xFFD9D9D9.toInt()
    val GRAY = 0xFF8A857C.toInt()

    fun series(slot: Int): Int = SERIES[Math.floorMod(slot, SERIES.size)]

    fun named(name: String?): Int? {
        val n = name?.trim()?.lowercase() ?: return null
        return when (n) {
            "amber", "orange", "yellow" -> SERIES[0]
            "blue" -> SERIES[1]
            "magenta", "pink" -> SERIES[2]
            "violet", "purple" -> SERIES[3]
            "aqua", "green", "teal" -> SERIES[4]
            "red" -> SERIES[5]
            "gray", "grey" -> GRAY
            "white", "black" -> INK // black would vanish on the dark bubble
            else -> if (n.length == 7 && n[0] == '#') n.substring(1).toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } else null
        }
    }
}
