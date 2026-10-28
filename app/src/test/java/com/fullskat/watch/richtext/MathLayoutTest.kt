package com.fullskat.watch.richtext

import com.fullskat.watch.richtext.math.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathLayoutTest {
    // A fixed-pitch fake font: every code point is 0.5 em wide, 0.7 above and 0.2 below the baseline.
    private val metrics = MathMetrics { text -> GlyphMetrics(0.5f * text.codePointCount(0, text.length), 0.7f, 0.2f) }
    private val engine = MathLayoutEngine(metrics)
    private fun layout(s: String, display: Boolean = true) = engine.layout(LatexParser.parse(s), display)
    private fun glyphs(l: MathLayout) = l.ops.filterIsInstance<GlyphOp>()

    @Test fun `fraction stacks numerator over denominator around a rule`() {
        val l = layout("\\frac{a}{b}")
        val g = glyphs(l)
        assertEquals(2, g.size)
        val (num, den) = g.sortedBy { it.y }
        assertTrue(num.y < 0f && den.y > 0f)
        val rule = l.ops.filterIsInstance<RuleOp>().single()
        assertTrue(rule.y < 0.05f && rule.y > -0.5f)
        assertTrue(l.width >= 0.5f * 0.7f)
    }

    @Test fun `superscripts are smaller and raised, subscripts lowered`() {
        val l = layout("x^2_i", display = false)
        val g = glyphs(l).sortedBy { it.y }
        assertEquals(3, g.size)
        val (sup, base, sub) = g
        assertTrue(sup.y < base.y && sub.y > base.y)
        assertTrue(sup.size < base.size && sub.size < base.size)
        assertTrue(sup.x >= base.x + 0.4f)
    }

    @Test fun `display limits go above and below, inline limits go to the side`() {
        val d = glyphs(layout("\\sum_{i}^{n}", display = true))
        val sumD = d.maxByOrNull { it.size }!!
        assertTrue(d.any { it !== sumD && it.y < 0f } && d.any { it !== sumD && it.y > 0f })
        val t = glyphs(layout("\\sum_{i}^{n}", display = false))
        val sumT = t.maxByOrNull { it.size }!!
        assertTrue(t.filter { it !== sumT }.all { it.x >= sumT.x + 0.4f })
    }

    @Test fun `binary and relation atoms get spacing, unary minus does not`() {
        val binary = layout("a+b", display = false)
        val plain = layout("ab", display = false)
        assertTrue(binary.width > plain.width + 0.5f) // "+" itself plus two medium spaces
        val unary = layout("-b", display = false)
        assertEquals(1.0f, unary.width, 0.001f) // the minus became an ordinary atom: no spacing
    }

    @Test fun `delimiters grow with their content`() {
        val small = layout("\\left( x \\right)")
        val tall = layout("\\left( \\frac{\\frac{a}{b}}{\\frac{c}{d}} \\right)")
        val stretched = glyphs(tall).filter { it.text == "(" || it.text == ")" }
        assertEquals(2, stretched.size)
        assertTrue(stretched.all { it.sy > 1.2f })
        assertTrue(glyphs(small).filter { it.text == "(" }.all { it.sy == 1f })
    }

    @Test fun `radicals and braces are drawn as polylines`() {
        assertEquals(1, layout("\\sqrt{x}").ops.filterIsInstance<PolyOp>().size)
        assertTrue(layout("\\overbrace{abc}^{n}").ops.filterIsInstance<PolyOp>().isNotEmpty())
    }

    @Test fun `matrices lay out a grid`() {
        val l = layout("\\begin{matrix} a & b \\\\ c & d \\end{matrix}")
        val g = glyphs(l)
        assertEquals(4, g.size)
        assertEquals(2, g.map { it.y }.distinct().size)
        assertEquals(2, g.map { it.x }.distinct().size)
    }

    @Test fun `colors are carried onto glyphs`() {
        val g = glyphs(layout("\\textcolor{red}{x} y"))
        assertTrue(g.any { it.color != null } && g.any { it.color == null })
    }

    @Test fun `all example formulas lay out to finite, sane boxes`() {
        val all = MathExamples.display.map { it to true } + MathExamples.inline.map { it to false }
        for ((src, display) in all) {
            val l = layout(src, display)
            val nums = listOf(l.width, l.ascent, l.descent)
            assertTrue("$src: $nums", nums.all { it.isFinite() } && l.width > 0f && l.height > 0f && l.height < 40f && l.width < 400f)
            for (op in l.ops) when (op) {
                is GlyphOp -> assertTrue("$src glyph", op.x.isFinite() && op.y.isFinite() && op.size > 0f)
                is RuleOp -> assertTrue("$src rule", op.x.isFinite() && op.w >= 0f && op.h >= 0f)
                is PolyOp -> assertTrue("$src poly", op.points.all { it.isFinite() } && op.points.size % 2 == 0)
            }
        }
    }

    @Test fun `random input never breaks the layout`() {
        val rnd = java.util.Random(5)
        val bits = listOf("\\frac", "{", "}", "^", "_", "&", "\\\\", "x", "1", "\\left(", "\\right)", "\\sqrt", "\\begin{pmatrix}", "\\end{pmatrix}", "\\sum", "\\int", "+", "=", "\\alpha", "\\over", "\\xrightarrow", "\\overbrace", "\\hat", "\\binom", "\\substack", "\\text{ab}", "\\cancel", "\\boxed", "\\underset", "\\begin{array}{c|c}", "\\hline", "\\end{array}")
        repeat(8000) {
            val s = buildString { repeat(rnd.nextInt(24)) { append(bits[rnd.nextInt(bits.size)]) } }
            val l = layout(s, rnd.nextBoolean())
            assertTrue(s, l.width.isFinite() && l.ascent.isFinite() && l.descent.isFinite())
        }
    }
}
