package com.fullskat.watch.richtext.math

import kotlin.math.max
import kotlin.math.min

/**
 * Lays a [MathNode] tree out into a display list (see [MathOp]) following TeX's rules: atom-class spacing,
 * fractions, scripts, limits, radicals, stretchy delimiters, matrices. All numbers are em units; the
 * typesetting constants come from the OpenType MATH table of the bundled Noto Sans Math.
 */
class MathLayoutEngine(private val metrics: MathMetrics) {

    private object MC {
        const val AXIS = 0.278f
        const val RULE = 0.071f
        const val FRAC_NUM_UP = 0.529f
        const val FRAC_NUM_UP_D = 0.704f
        const val FRAC_DEN_DOWN = 0.320f
        const val FRAC_DEN_DOWN_D = 0.673f
        const val FRAC_GAP = 0.071f
        const val FRAC_GAP_D = 0.150f
        const val STACK_GAP = 0.178f
        const val STACK_GAP_D = 0.336f
        const val SUP_SHIFT = 0.390f
        const val SUP_SHIFT_CRAMPED = 0.280f
        const val SUP_BOTTOM_MIN = 0.134f
        const val SUP_DROP = 0.240f
        const val SUB_SHIFT = 0.210f
        const val SUB_TOP_MAX = 0.430f
        const val SUB_DROP = 0.140f
        const val SUBSUP_GAP = 0.160f
        const val SUP_BOTTOM_MAX_WITH_SUB = 0.430f
        const val SCRIPT_SPACE = 0.040f
        const val LIMIT_GAP = 0.100f
        const val RADICAL_GAP = 0.089f
        const val RADICAL_GAP_D = 0.190f
        const val OVERBAR_GAP = 0.150f
        const val NULL_DELIM = 0.120f
    }

    private companion object {
        val SIZES = floatArrayOf(1f, 1f, 0.7f, 0.5f)
        val BIG_HEIGHTS = floatArrayOf(1.2f, 1.8f, 2.4f, 3.0f)
        const val ERROR_COLOR = 0xFFFF8A80.toInt()

        // Spacing between atom classes (TeXbook p.170). 0 none, 1 thin, 2 medium, 3 thick; negative = only outside script styles.
        // Row/column order: ORD OP BIN REL OPEN CLOSE PUNCT INNER.
        val SPACING = arrayOf(
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1),
            intArrayOf(1, 1, 9, -3, 0, 0, 0, -1),
            intArrayOf(-2, -2, 9, 9, -2, 9, 9, -2),
            intArrayOf(-3, -3, 9, 0, -3, 0, 0, -3),
            intArrayOf(0, 0, 9, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1),
            intArrayOf(-1, -1, 9, -1, -1, -1, -1, -1),
            intArrayOf(-1, 1, -2, -3, -1, 0, -1, -1),
        )
    }

    private class Cx(val style: Int, val cramped: Boolean, val variant: Variant, val color: Int?) {
        val size: Float get() = SIZES[style]
        fun withStyle(s: Int, c: Boolean = cramped) = Cx(s, c, variant, color)
    }

    private class Box(var w: Float, var a: Float, var d: Float) {
        val ops = ArrayList<MathOp>()
        fun add(b: Box, dx: Float, dy: Float) {
            for (op in b.ops) ops += op.shifted(dx, dy)
        }
    }

    fun layout(node: MathNode, display: Boolean): MathLayout {
        val box = lay(node, Cx(if (display) STYLE_DISPLAY else STYLE_TEXT, false, Variant.DEFAULT, null))
        return MathLayout(box.w, box.a, box.d, box.ops)
    }

    // ------------------------------------------------------------------ dispatch

    private fun lay(node: MathNode, cx: Cx): Box = when (node) {
        is MRow -> layRow(node.items, cx)
        is MAtom -> layAtom(node, cx)
        is MText -> glyphBox(MathSymbols.styled(node.text, node.variant), cx.size, cx.color)
        is MSpace -> Box(node.em * cx.size, 0f, 0f)
        is MScripts -> layScripts(node, cx)
        is MFrac -> layFrac(node, cx)
        is MSqrt -> laySqrt(node, cx)
        is MDelim -> layDelimited(node, cx)
        is MSized -> delimBox(node.delim, 0f, 0f, cx, fixedHeight = BIG_HEIGHTS[(node.level - 1).coerceIn(0, 3)] * cx.size)
        is MAccent -> layAccent(node, cx)
        is MStack -> layStack(node, cx)
        is MArrow -> layArrow(node, cx)
        is MMatrix -> layMatrix(node, cx)
        is MStyled -> {
            var c = Cx(cx.style, cx.cramped, node.variant ?: cx.variant, node.color ?: cx.color)
            if (node.style != null) c = c.withStyle(node.style)
            lay(node.body, c)
        }
        is MPhantom -> {
            val b = lay(node.body, cx)
            Box(if (node.keepWidth) b.w else 0f, if (node.keepHeight) b.a else 0f, if (node.keepHeight) b.d else 0f)
        }
        is MCancel -> layCancel(node, cx)
        is MBoxed -> layBoxed(node, cx)
        is MClass -> lay(node.body, cx)
        is MNot -> layNot(node, cx)
        is MError -> glyphBox(node.source, cx.size, ERROR_COLOR)
    }

    private fun glyphBox(text: String, size: Float, color: Int?): Box {
        if (text.isEmpty()) return Box(0f, 0f, 0f)
        val m = metrics.measure(text)
        val b = Box(m.advance * size, max(0f, m.ascent * size), max(0f, m.descent * size))
        b.ops += GlyphOp(text, 0f, 0f, size, color)
        return b
    }

    private fun scriptOf(style: Int) = if (style <= STYLE_TEXT) STYLE_SCRIPT else STYLE_SCRIPT2
    private fun fracOf(style: Int) = when (style) { STYLE_DISPLAY -> STYLE_TEXT; STYLE_TEXT -> STYLE_SCRIPT; else -> STYLE_SCRIPT2 }

    // ------------------------------------------------------------------ rows and spacing

    private fun clsOf(node: MathNode): Cls? = when (node) {
        is MAtom -> node.cls
        is MScripts -> clsOf(node.base) ?: Cls.ORD
        is MFrac, is MDelim -> Cls.INNER
        is MSized -> node.cls
        is MMatrix -> if (matrixDelims(node.env).let { it.first.isNotEmpty() || it.second.isNotEmpty() }) Cls.INNER else Cls.ORD
        is MClass -> node.cls
        is MArrow -> Cls.REL
        is MSpace -> null
        is MRow -> if (node.items.isEmpty()) null else Cls.ORD
        is MStack -> if (node.base is MRow && node.base.items.size == 1) clsOf(node.base.items[0]) ?: Cls.ORD else Cls.ORD
        else -> Cls.ORD
    }

    private fun layRow(items: List<MathNode>, cx: Cx): Box {
        if (items.isEmpty()) return Box(0f, 0f, 0f)
        val boxes = items.map { lay(it, cx) }
        val classes = items.map { clsOf(it) }.toMutableList()
        var prev: Cls? = null
        for (i in classes.indices) {
            val c = classes[i] ?: continue
            if (c == Cls.BIN) {
                var next: Cls? = null
                for (j in i + 1 until classes.size) if (classes[j] != null) { next = classes[j]; break }
                if (prev == null || prev == Cls.BIN || prev == Cls.OP || prev == Cls.REL || prev == Cls.OPEN || prev == Cls.PUNCT ||
                    next == null || next == Cls.REL || next == Cls.CLOSE || next == Cls.PUNCT
                ) classes[i] = Cls.ORD
            }
            prev = classes[i]
        }
        val out = Box(0f, 0f, 0f)
        var x = 0f
        var last: Cls? = null
        for (i in boxes.indices) {
            val c = classes[i]
            if (c != null) {
                if (last != null) x += space(last, c, cx)
                last = c
            }
            val b = boxes[i]
            out.add(b, x, 0f)
            x += b.w
            out.a = max(out.a, b.a)
            out.d = max(out.d, b.d)
        }
        out.w = x
        return out
    }

    private fun space(l: Cls, r: Cls, cx: Cx): Float {
        val code = SPACING[l.ordinal][r.ordinal]
        if (code == 9 || code == 0) return 0f
        if (code < 0 && cx.style > STYLE_TEXT) return 0f
        return when (kotlin.math.abs(code)) { 1 -> 3f; 2 -> 4f; else -> 5f } / 18f * cx.size
    }

    // ------------------------------------------------------------------ atoms

    private fun layAtom(n: MAtom, cx: Cx): Box {
        val text = if (n.fixed) n.text else MathSymbols.styled(n.text, cx.variant)
        if (!n.large) return glyphBox(text, cx.size, cx.color)
        val display = cx.style == STYLE_DISPLAY
        val k = if (!display) 1f else if (n.limits == Limits.LIMITS) 1.35f else 1.5f
        val size = cx.size * k
        val m = metrics.measure(n.text)
        val a = m.ascent * size
        val d = m.descent * size
        val y = if (k > 1f) -MC.AXIS * cx.size - (d - a) / 2f else 0f
        val b = Box(m.advance * size, max(0f, a - y), max(0f, d + y))
        b.ops += GlyphOp(n.text, 0f, y, size, cx.color)
        return b
    }

    private fun isSimpleChar(n: MathNode) = n is MAtom && !n.large

    // ------------------------------------------------------------------ scripts and limits

    private fun defaultLimits(base: MathNode): Boolean = when (base) {
        is MAtom -> base.cls == Cls.OP && base.limits == Limits.LIMITS
        is MClass -> base.cls == Cls.OP
        else -> false
    }

    private fun layScripts(n: MScripts, cx: Cx): Box {
        val base = n.base
        if (base is MStack && base.brace != Brace.NONE) return layBrace(base, n.sup, n.sub, cx)
        val baseBox = lay(base, cx)
        val limitsHere = when (n.limits) {
            Limits.LIMITS -> true
            Limits.NOLIMITS -> false
            Limits.AUTO -> defaultLimits(base) && cx.style == STYLE_DISPLAY
        }
        return if (limitsHere) layLimits(n, baseBox, cx) else layScriptsSide(n, baseBox, cx)
    }

    private fun layLimits(n: MScripts, base: Box, cx: Cx): Box {
        val s = cx.withStyle(scriptOf(cx.style), cx.cramped)
        val sup = n.sup?.let { lay(it, s) }
        val sub = n.sub?.let { lay(it, cx.withStyle(scriptOf(cx.style), true)) }
        val w = max(base.w, max(sup?.w ?: 0f, sub?.w ?: 0f))
        val out = Box(w, base.a, base.d)
        out.add(base, (w - base.w) / 2f, 0f)
        val gap = MC.LIMIT_GAP * cx.size
        if (sup != null) {
            val rise = base.a + gap + sup.d
            out.add(sup, (w - sup.w) / 2f, -rise)
            out.a = rise + sup.a
        }
        if (sub != null) {
            val drop = base.d + gap + sub.a
            out.add(sub, (w - sub.w) / 2f, drop)
            out.d = drop + sub.d
        }
        return out
    }

    private fun layScriptsSide(n: MScripts, base: Box, cx: Cx): Box {
        val sz = cx.size
        val st = scriptOf(cx.style)
        val ssz = SIZES[st]
        val sup = n.sup?.let { lay(it, cx.withStyle(st, cx.cramped)) }
        val sub = n.sub?.let { lay(it, cx.withStyle(st, true)) }
        val simple = isSimpleChar(n.base)
        var u = if (simple) 0f else base.a - MC.SUP_DROP * ssz
        var v = if (simple) 0f else base.d + MC.SUB_DROP * ssz
        val p = (if (cx.cramped) MC.SUP_SHIFT_CRAMPED else MC.SUP_SHIFT) * sz
        if (sup != null && sub == null) {
            u = max(u, max(p, sup.d + MC.SUP_BOTTOM_MIN * sz))
        } else if (sub != null && sup == null) {
            v = max(v, max(MC.SUB_SHIFT * sz, sub.a - MC.SUB_TOP_MAX * sz))
        } else if (sup != null && sub != null) {
            u = max(u, max(p, sup.d + MC.SUP_BOTTOM_MIN * sz))
            v = max(v, MC.SUB_SHIFT * sz)
            val gap = (u - sup.d) - (sub.a - v)
            val minGap = MC.SUBSUP_GAP * sz
            if (gap < minGap) {
                v += minGap - gap
                val psi = MC.SUP_BOTTOM_MAX_WITH_SUB * sz - (u - sup.d)
                if (psi > 0f) { u += psi; v -= psi }
            }
        }
        val out = Box(base.w, base.a, base.d)
        out.add(base, 0f, 0f)
        // The integral sign is drawn upright, so its limits are tucked: sub to the left of the tail, sup a bit to the right.
        val bigIntegral = n.base is MAtom && n.base.large && cx.style == STYLE_DISPLAY
        val subTuck = if (bigIntegral) 0.2f * sz else 0f
        val supPush = if (bigIntegral) 0.08f * sz else 0f
        var sw = 0f
        if (sup != null) { out.add(sup, base.w + supPush, -u); sw = max(sw, sup.w + supPush); out.a = max(out.a, u + sup.a) }
        if (sub != null) { out.add(sub, base.w - subTuck, v); sw = max(sw, sub.w - subTuck); out.d = max(out.d, v + sub.d) }
        out.w = base.w + sw + MC.SCRIPT_SPACE * sz
        return out
    }

    // ------------------------------------------------------------------ fractions and roots

    private fun layFrac(n: MFrac, cx0: Cx): Box {
        val cx = if (n.style != null) cx0.withStyle(n.style) else cx0
        val display = cx.style == STYLE_DISPLAY
        val sz = cx.size
        val inner = fracOf(cx.style)
        val num = lay(n.num, cx.withStyle(inner, cx.cramped))
        val den = lay(n.den, cx.withStyle(inner, true))
        val w = max(num.w, den.w)
        val pad = MC.NULL_DELIM * sz
        val out = Box(w + 2 * pad, 0f, 0f)
        val axis = MC.AXIS * sz
        var u = (if (display) MC.FRAC_NUM_UP_D else MC.FRAC_NUM_UP) * sz
        var v = (if (display) MC.FRAC_DEN_DOWN_D else MC.FRAC_DEN_DOWN) * sz
        if (n.rule) {
            val theta = MC.RULE * sz
            val gap = (if (display) MC.FRAC_GAP_D else MC.FRAC_GAP) * sz
            u = max(u, num.d + axis + theta / 2f + gap)
            v = max(v, den.a + gap - axis + theta / 2f)
            out.ops += RuleOp(pad, -axis - theta / 2f, w, theta, cx.color)
        } else {
            val gap = (if (display) MC.STACK_GAP_D else MC.STACK_GAP) * sz
            val have = u + v - num.d - den.a
            if (have < gap) { val extra = (gap - have) / 2f; u += extra; v += extra }
        }
        out.add(num, pad + (w - num.w) / 2f, -u)
        out.add(den, pad + (w - den.w) / 2f, v)
        out.a = u + num.a
        out.d = v + den.d
        if (n.left.isEmpty() && n.right.isEmpty()) return out
        val minH = if (display) 1.9f * sz else 1.0f * sz
        val l = delimBox(n.left, out.a, out.d, cx, minHeight = minH)
        val r = delimBox(n.right, out.a, out.d, cx, minHeight = minH)
        return join(listOf(l, out, r))
    }

    private fun join(parts: List<Box>): Box {
        val out = Box(0f, 0f, 0f)
        var x = 0f
        for (p in parts) {
            out.add(p, x, 0f)
            x += p.w
            out.a = max(out.a, p.a)
            out.d = max(out.d, p.d)
        }
        out.w = x
        return out
    }

    private fun laySqrt(n: MSqrt, cx: Cx): Box {
        val sz = cx.size
        val body = lay(n.body, cx.withStyle(cx.style, true))
        val theta = MC.RULE * sz
        val gap = (if (cx.style == STYLE_DISPLAY) MC.RADICAL_GAP_D else MC.RADICAL_GAP) * sz
        val topY = -(body.a + gap + theta / 2f)
        val botY = body.d + 0.04f * sz
        val h = botY - topY
        val ws = 0.62f * sz
        val index = n.index?.let { lay(it, cx.withStyle(STYLE_SCRIPT2, false)) }
        val x0 = if (index != null) max(0f, index.w - 0.45f * ws) else 0f
        val out = Box(0f, body.a + gap + theta, body.d)
        val bodyW = max(body.w, 0.2f * sz)
        val pts = floatArrayOf(
            x0, topY + 0.60f * h,
            x0 + 0.14f * ws, topY + 0.53f * h,
            x0 + 0.42f * ws, botY,
            x0 + ws, topY,
            x0 + ws + bodyW + 0.06f * sz, topY,
        )
        out.ops += PolyOp(pts, theta * 1.05f, cx.color)
        out.add(body, x0 + ws + 0.02f * sz, 0f)
        out.w = x0 + ws + bodyW + 0.06f * sz
        if (index != null) {
            val baseline = topY + 0.47f * h - index.d
            out.add(index, 0f, baseline)
            out.a = max(out.a, -baseline + index.a)
        }
        return out
    }

    // ------------------------------------------------------------------ delimiters

    private fun matrixDelims(env: String): Pair<String, String> = when (env) {
        "pmatrix" -> "(" to ")"
        "bmatrix" -> "[" to "]"
        "Bmatrix" -> "{" to "}"
        "vmatrix" -> "|" to "|"
        "Vmatrix" -> "‖" to "‖"
        "cases", "dcases" -> "{" to ""
        "rcases" -> "" to "}"
        else -> "" to ""
    }

    private fun layDelimited(n: MDelim, cx: Cx): Box {
        val parts = n.parts.map { lay(it, cx) }
        var a = 0f
        var d = 0f
        for (p in parts) { a = max(a, p.a); d = max(d, p.d) }
        val seq = ArrayList<Box>()
        seq += delimBox(n.left, a, d, cx)
        for (i in parts.indices) {
            seq += parts[i]
            if (i < n.middles.size) seq += delimBox(n.middles[i], a, d, cx)
        }
        seq += delimBox(n.right, a, d, cx)
        return join(seq)
    }

    /**
     * A delimiter that spans at least [needA] above and [needD] below the baseline (or exactly [fixedHeight]).
     * Straight ones are drawn from rules, curved ones are the font glyph stretched vertically about the axis.
     */
    private fun delimBox(glyph: String, needA: Float, needD: Float, cx: Cx, fixedHeight: Float? = null, minHeight: Float = 0f): Box {
        val sz = cx.size
        if (glyph.isEmpty()) return Box(MC.NULL_DELIM * sz, 0f, 0f)
        val axis = MC.AXIS * sz
        val half = max(needA - axis, needD + axis)
        val m = metrics.measure(glyph)
        val ga = m.ascent * sz
        val gd = m.descent * sz
        val gh = ga + gd
        val wanted = fixedHeight ?: max(minHeight, max(2f * half * 0.901f, 2f * half - 0.2f * sz))
        if (fixedHeight == null && wanted <= gh * 1.12f) return glyphBox(glyph, sz, cx.color)
        val h = max(wanted, gh)
        val top = -(axis + h / 2f)
        val bottom = h / 2f - axis
        val t = 0.055f * sz
        val color = cx.color
        val out: Box
        when (glyph) {
            "|", "∣" -> {
                out = Box(0.25f * sz, max(0f, -top), max(0f, bottom))
                out.ops += RuleOp(0.1f * sz, top, t, h, color)
            }
            "‖" -> {
                out = Box(0.36f * sz, max(0f, -top), max(0f, bottom))
                out.ops += RuleOp(0.1f * sz, top, t, h, color)
                out.ops += RuleOp(0.1f * sz + 2.2f * t, top, t, h, color)
            }
            "[", "⌈", "⌊" -> {
                out = Box(0.34f * sz, max(0f, -top), max(0f, bottom))
                out.ops += RuleOp(0.1f * sz, top, t, h, color)
                if (glyph != "⌊") out.ops += RuleOp(0.1f * sz, top, 0.2f * sz, t, color)
                if (glyph != "⌈") out.ops += RuleOp(0.1f * sz, bottom - t, 0.2f * sz, t, color)
            }
            "]", "⌉", "⌋" -> {
                out = Box(0.34f * sz, max(0f, -top), max(0f, bottom))
                val x = 0.34f * sz - 0.1f * sz - t
                out.ops += RuleOp(x, top, t, h, color)
                if (glyph != "⌋") out.ops += RuleOp(x - 0.15f * sz, top, 0.15f * sz + t, t, color)
                if (glyph != "⌉") out.ops += RuleOp(x - 0.15f * sz, bottom - t, 0.15f * sz + t, t, color)
            }
            else -> {
                val k = h / max(gh, 0.01f)
                out = Box(m.advance * sz * (1f + 0.06f * (k - 1f).coerceIn(0f, 3f)), max(0f, -top), max(0f, bottom))
                val baseline = -axis - (gd - ga) / 2f
                out.ops += GlyphOp(glyph, 0f, baseline, sz, color, sx = 1f, sy = k, pivotY = -axis)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ accents, stacks, arrows

    private fun layAccent(n: MAccent, cx: Cx): Box {
        val sz = cx.size
        val color = cx.color
        val kind = n.kind
        val under = kind.startsWith("under")
        val body = lay(n.body, if (under) cx else cx.withStyle(cx.style, true))
        val th = MC.RULE * sz
        val out = Box(body.w, body.a, body.d)
        out.add(body, 0f, 0f)
        when (kind) {
            "overline" -> {
                val y = -(body.a + MC.OVERBAR_GAP * sz + th)
                out.ops += RuleOp(0f, y, body.w, th, color)
                out.a = -y + th
            }
            "underline" -> {
                val y = body.d + MC.OVERBAR_GAP * sz
                out.ops += RuleOp(0f, y, body.w, th, color)
                out.d = y + th
            }
            "overrightarrow", "overleftarrow", "overleftrightarrow", "underrightarrow", "underleftarrow", "underleftrightarrow" -> {
                val y = if (under) body.d + 0.14f * sz else -(body.a + 0.14f * sz)
                val w = max(body.w, 0.3f * sz)
                arrowShape(out, 0f, w, y, sz, right = !kind.endsWith("leftarrow"), left = kind.endsWith("leftarrow") || kind.endsWith("leftrightarrow"), color)
                if (under) out.d = y + 0.12f * sz else out.a = -y + 0.12f * sz
            }
            "overparen", "underparen" -> {
                val h = 0.2f * sz
                val yBase = if (under) body.d + 0.08f * sz else -(body.a + 0.08f * sz)
                out.ops += PolyOp(bracePoints(0f, body.w, yBase, h, up = !under), th, color)
                if (under) out.d = yBase + h else out.a = -yBase + h
            }
            "bar" -> {
                val w = max(body.w - 0.12f * sz, 0.3f * sz)
                val y = -(body.a + 0.12f * sz)
                out.ops += RuleOp((body.w - w) / 2f, y, w, th, color)
                out.a = -y + th
            }
            else -> {
                val g = MathSymbols.accents[kind] ?: return out
                val wide = kind == "widehat" || kind == "widetilde"
                val gsz = if (kind == "vec") sz * 0.62f else sz
                val m = metrics.measure(g)
                val inkW = (m.inkRight - m.inkLeft) * gsz
                val sx = if (wide && inkW > 0.01f) (body.w * 0.95f / inkW).coerceIn(1f, 6f) else 1f
                val single = n.body is MAtom || (n.body is MRow && n.body.items.size == 1)
                val centre = (m.inkLeft + m.inkRight) / 2f * gsz * sx
                val x = body.w / 2f - centre + if (single && !wide) 0.05f * sz else 0f
                val baseline = -(max(body.a, 0.2f * sz) + 0.04f * sz) - m.descent * gsz
                out.ops += GlyphOp(g, x, baseline, gsz, color, sx = sx)
                out.a = max(body.a, -(baseline - m.ascent * gsz))
            }
        }
        return out
    }

    private fun layStack(n: MStack, cx: Cx): Box {
        if (n.brace != Brace.NONE) return layBrace(n, null, null, cx)
        val sz = cx.size
        val base = lay(n.base, cx)
        val s = cx.withStyle(scriptOf(cx.style), cx.cramped)
        val over = n.over?.let { lay(it, s) }
        val under = n.under?.let { lay(it, s) }
        val w = max(base.w, max(over?.w ?: 0f, under?.w ?: 0f))
        val out = Box(w, base.a, base.d)
        out.add(base, (w - base.w) / 2f, 0f)
        val gap = 0.1f * sz
        if (over != null) {
            val rise = base.a + gap + over.d
            out.add(over, (w - over.w) / 2f, -rise)
            out.a = rise + over.a
        }
        if (under != null) {
            val drop = base.d + gap + under.a
            out.add(under, (w - under.w) / 2f, drop)
            out.d = drop + under.d
        }
        return out
    }

    /** \overbrace / \underbrace with the label taken from the matching script. */
    private fun layBrace(n: MStack, sup: MathNode?, sub: MathNode?, cx: Cx): Box {
        val sz = cx.size
        val base = lay(n.base, cx)
        val over = n.brace == Brace.OVER
        val label = (if (over) sup else sub)?.let { lay(it, cx.withStyle(scriptOf(cx.style), cx.cramped)) }
        val w = max(base.w, label?.w ?: 0f)
        val out = Box(w, base.a, base.d)
        val bx = (w - base.w) / 2f
        out.add(base, bx, 0f)
        val h = 0.22f * sz
        val th = MC.RULE * sz * 0.9f
        val gap = 0.12f * sz
        if (over) {
            val yBase = -(base.a + gap)
            out.ops += PolyOp(bracePoints(bx, bx + base.w, yBase, h, up = true), th, cx.color)
            out.a = -yBase + h
            if (label != null) {
                val baseline = yBase - h - gap - label.d
                out.add(label, (w - label.w) / 2f, baseline)
                out.a = -baseline + label.a
            }
        } else {
            val yBase = base.d + gap
            out.ops += PolyOp(bracePoints(bx, bx + base.w, yBase, h, up = false), th, cx.color)
            out.d = yBase + h
            if (label != null) {
                val baseline = yBase + h + gap + label.a
                out.add(label, (w - label.w) / 2f, baseline)
                out.d = baseline + label.d
            }
        }
        return out
    }

    /** Polyline of a horizontal curly brace from x0 to x1; the tip points up (above the text) or down. */
    private fun bracePoints(x0: Float, x1: Float, yBase: Float, h: Float, up: Boolean): FloatArray {
        val dir = if (up) -1f else 1f
        val yEnd = yBase
        val yLine = yBase + dir * h / 2f
        val yTip = yBase + dir * h
        val xm = (x0 + x1) / 2f
        val r = min(h / 2f, (x1 - x0) / 4f)
        val pts = ArrayList<Float>()
        fun quad(ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float) {
            for (i in 0..5) {
                val t = i / 5f
                val mt = 1f - t
                pts += mt * mt * ax + 2f * mt * t * cx + t * t * bx
                pts += mt * mt * ay + 2f * mt * t * cy + t * t * by
            }
        }
        quad(x0, yEnd, x0, yLine, x0 + r, yLine)
        pts += xm - r; pts += yLine
        quad(xm - r, yLine, xm, yLine, xm, yTip)
        quad(xm, yTip, xm, yLine, xm + r, yLine)
        pts += x1 - r; pts += yLine
        quad(x1 - r, yLine, x1, yLine, x1, yEnd)
        return pts.toFloatArray()
    }

    private fun arrowShape(out: Box, x0: Float, x1: Float, y: Float, sz: Float, right: Boolean, left: Boolean, color: Int?, double: Boolean = false) {
        val th = 0.05f * sz
        val head = 0.13f * sz
        val off = if (double) 0.045f * sz else 0f
        if (double) {
            out.ops += PolyOp(floatArrayOf(x0, y - off, x1, y - off), th, color)
            out.ops += PolyOp(floatArrayOf(x0, y + off, x1, y + off), th, color)
        } else {
            out.ops += PolyOp(floatArrayOf(x0, y, x1, y), th, color)
        }
        if (right) out.ops += PolyOp(floatArrayOf(x1 - head, y - head * 0.85f, x1, y, x1 - head, y + head * 0.85f), th, color)
        if (left) out.ops += PolyOp(floatArrayOf(x0 + head, y - head * 0.85f, x0, y, x0 + head, y + head * 0.85f), th, color)
    }

    private fun layArrow(n: MArrow, cx: Cx): Box {
        val sz = cx.size
        val s = cx.withStyle(scriptOf(cx.style), cx.cramped)
        val above = n.above?.let { lay(it, s) }
        val below = n.below?.let { lay(it, s) }
        val labelW = max(above?.w ?: 0f, below?.w ?: 0f)
        val len = max(1.2f * sz, labelW + 0.8f * sz)
        val pad = 0.1f * sz
        val out = Box(len + 2 * pad, 0.25f * sz, 0.25f * sz)
        val axis = MC.AXIS * sz
        val y = -axis
        val g = n.glyph
        val double = g == "⇒" || g == "⇐" || g == "⇔" || g == "="
        arrowShape(
            out, pad, pad + len, y, sz,
            right = g == "→" || g == "⇒" || g == "↔" || g == "⇔" || g == "↦" || g == "↪" || g == "↠" || g == "⇀" || g == "⇌",
            left = g == "←" || g == "⇐" || g == "↔" || g == "⇔",
            color = cx.color, double = double,
        )
        if (g == "↦") out.ops += PolyOp(floatArrayOf(pad, y - 0.07f * sz, pad, y + 0.07f * sz), 0.05f * sz, cx.color)
        if (above != null) {
            val baseline = y - 0.09f * sz - above.d - (if (double) 0.045f * sz else 0f)
            out.add(above, pad + (len - above.w) / 2f, baseline)
            out.a = max(out.a, -baseline + above.a)
        }
        if (below != null) {
            val baseline = y + 0.09f * sz + below.a + (if (double) 0.045f * sz else 0f)
            out.add(below, pad + (len - below.w) / 2f, baseline)
            out.d = max(out.d, baseline + below.d)
        }
        return out
    }

    // ------------------------------------------------------------------ matrices

    private fun layMatrix(n: MMatrix, cx0: Cx): Box {
        val env = n.env
        val alignedLike = env in setOf("aligned", "align", "align*", "split", "alignat", "alignat*", "alignedat", "eqnarray", "eqnarray*", "flalign", "flalign*", "multline", "multline*", "gather", "gather*", "gathered", "equation", "equation*")
        val small = env == "smallmatrix"
        val cellCx = when {
            alignedLike -> cx0.withStyle(STYLE_DISPLAY, false)
            small -> cx0.withStyle(STYLE_SCRIPT, false)
            env == "substack" || env == "subarray" -> cx0.withStyle(cx0.style.coerceAtLeast(STYLE_TEXT), cx0.cramped)
            else -> cx0.withStyle(STYLE_TEXT, false)
        }
        val s = cellCx.size
        val pairedCols = env in setOf("aligned", "align", "align*", "split", "alignat", "alignat*", "alignedat", "eqnarray", "eqnarray*", "flalign", "flalign*")
        // amsmath starts every left-aligned cell with an empty ordinary atom, so a leading "=" is spaced as a relation.
        val rows = n.rows.map { r -> r.mapIndexed { c, node -> lay(if (pairedCols && c % 2 == 1) MRow(listOf<MathNode>(MClass(Cls.ORD, MRow(emptyList()))) + (if (node is MRow) node.items else listOf(node))) else node, cellCx) } }
        val ncols = rows.maxOfOrNull { it.size } ?: 0
        if (ncols == 0) return Box(0f, 0f, 0f)
        val twoPerPair = env in setOf("aligned", "align", "align*", "split", "alignat", "alignat*", "alignedat", "eqnarray", "eqnarray*", "flalign", "flalign*")
        val align = CharArray(ncols) { c ->
            when {
                twoPerPair -> if (c % 2 == 0) 'r' else 'l'
                env == "cases" || env == "dcases" || env == "rcases" -> 'l'
                n.colSpec.isNotEmpty() -> n.colSpec.getOrElse(c) { 'c' }
                else -> 'c'
            }
        }
        val colW = FloatArray(ncols)
        for (r in rows) for (c in r.indices) colW[c] = max(colW[c], r[c].w)
        val sepBetween = FloatArray(max(ncols - 1, 0)) { c ->
            when {
                twoPerPair -> if (c % 2 == 0) 0f else 1.0f * s
                small -> 0.5f * s
                else -> 1.0f * s
            }
        }
        val colX = FloatArray(ncols)
        var padL = if (0 in n.vlines) 0.35f * s else 0f
        var x = padL
        for (c in 0 until ncols) {
            colX[c] = x
            x += colW[c] + if (c < ncols - 1) sepBetween[c] else 0f
        }
        val padR = if (ncols in n.vlines) 0.35f * s else 0f
        val totalW = x + padR
        val aMin = 0.84f * s
        val dMin = 0.36f * s
        val gap = (if (alignedLike) 0.2f else 0.1f) * s
        val rowA = FloatArray(rows.size)
        val rowD = FloatArray(rows.size)
        for ((i, r) in rows.withIndex()) {
            rowA[i] = aMin
            rowD[i] = dMin
            for (b in r) { rowA[i] = max(rowA[i], b.a); rowD[i] = max(rowD[i], b.d) }
        }
        var height = 0f
        for (i in rows.indices) height += rowA[i] + rowD[i] + if (i < rows.size - 1) gap else 0f
        val axis = MC.AXIS * s
        val top = -(axis + height / 2f)
        val body = Box(totalW, max(0f, -top), max(0f, height / 2f - axis))
        var y = top
        val rowTop = FloatArray(rows.size + 1)
        for ((i, r) in rows.withIndex()) {
            rowTop[i] = y
            val baseline = y + rowA[i]
            for (c in r.indices) {
                val b = r[c]
                val cx = when (align[c]) { 'l' -> colX[c]; 'r' -> colX[c] + colW[c] - b.w; else -> colX[c] + (colW[c] - b.w) / 2f }
                body.add(b, cx, baseline)
            }
            y += rowA[i] + rowD[i] + gap
        }
        rowTop[rows.size] = y - gap
        val th = 0.04f * s
        for (h in n.hlines) {
            val yy = when {
                h <= 0 -> rowTop[0] - gap / 2f - th
                h >= rows.size -> rowTop[rows.size] + gap / 2f
                else -> rowTop[h] - gap / 2f - th / 2f
            }
            body.ops += RuleOp(0f, yy, totalW, th, cx0.color)
            body.a = max(body.a, -yy)
            body.d = max(body.d, yy + th)
        }
        for (v in n.vlines) {
            val xx = when {
                v <= 0 -> 0.05f * s
                v >= ncols -> totalW - 0.05f * s - th
                else -> colX[v] - sepBetween[v - 1] / 2f - th / 2f
            }
            body.ops += RuleOp(xx, top, th, height, cx0.color)
        }
        padL = 0f
        val (l, r) = matrixDelims(env)
        if (l.isEmpty() && r.isEmpty()) return body
        val left = delimBox(l, body.a, body.d, cx0, minHeight = 0f)
        val right = delimBox(r, body.a, body.d, cx0, minHeight = 0f)
        val parts = ArrayList<Box>()
        parts += left
        if (env == "cases" || env == "dcases") parts += Box(0.1f * s, 0f, 0f)
        parts += body
        if (env == "rcases") parts += Box(0.1f * s, 0f, 0f)
        parts += right
        return join(parts)
    }

    // ------------------------------------------------------------------ decorations

    private fun layCancel(n: MCancel, cx: Cx): Box {
        val sz = cx.size
        val b = lay(n.body, cx)
        val out = Box(b.w, b.a, b.d)
        out.add(b, 0f, 0f)
        val th = 0.05f * sz
        val padX = 0.05f * sz
        val ya = -(b.a + 0.05f * sz)
        val yd = b.d + 0.05f * sz
        if (n.back || n.both) out.ops += PolyOp(floatArrayOf(-padX, ya, b.w + padX, yd), th, cx.color)
        if (!n.back || n.both) out.ops += PolyOp(floatArrayOf(-padX, yd, b.w + padX, ya), th, cx.color)
        return out
    }

    private fun layBoxed(n: MBoxed, cx: Cx): Box {
        val sz = cx.size
        val b = lay(n.body, cx)
        val pad = 0.2f * sz
        val th = 0.05f * sz
        val out = Box(b.w + 2 * pad, b.a + pad, b.d + pad)
        out.add(b, pad, 0f)
        val top = -(b.a + pad)
        val bottom = b.d + pad
        val w = b.w + 2 * pad
        out.ops += RuleOp(0f, top, w, th, cx.color)
        out.ops += RuleOp(0f, bottom - th, w, th, cx.color)
        out.ops += RuleOp(0f, top, th, bottom - top, cx.color)
        out.ops += RuleOp(w - th, top, th, bottom - top, cx.color)
        return out
    }

    private fun layNot(n: MNot, cx: Cx): Box {
        val sz = cx.size
        val b = lay(n.body, cx)
        val out = Box(b.w, b.a, b.d)
        out.add(b, 0f, 0f)
        out.ops += PolyOp(floatArrayOf(b.w * 0.15f, b.d + 0.12f * sz, b.w * 0.85f, -(b.a + 0.08f * sz)), 0.05f * sz, cx.color)
        return out
    }
}
