package com.fullskat.watch.richtext

import com.fullskat.watch.richtext.math.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexParserTest {
    private fun parse(s: String) = LatexParser.parse(s)
    private fun items(n: MathNode): List<MathNode> = (n as MRow).items
    private fun first(s: String) = items(parse(s)).first()

    @Test fun `fraction and scripts`() {
        val f = first("\\frac{a}{b}") as MFrac
        assertTrue(f.rule)
        val s = first("x^2_i") as MScripts
        assertTrue(s.sup != null && s.sub != null)
        val primes = first("f'") as MScripts
        assertEquals("′", ((primes.sup) as MAtom).text)
    }

    @Test fun `single token arguments`() {
        val f = first("\\frac12") as MFrac
        assertEquals("1", (f.num as MAtom).text)
        assertEquals("2", (f.den as MAtom).text)
        val s = first("x^\\alpha") as MScripts
        assertEquals("α", (s.sup as MAtom).text)
    }

    @Test fun `delimiters`() {
        val d = first("\\left( x \\middle| y \\right]") as MDelim
        assertEquals("(", d.left)
        assertEquals("]", d.right)
        assertEquals(listOf("|"), d.middles)
        assertEquals(2, d.parts.size)
        assertEquals("", (first("\\left. x \\right|") as MDelim).left)
    }

    @Test fun `matrix and cases`() {
        val m = first("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}") as MMatrix
        assertEquals("pmatrix", m.env)
        assertEquals(2, m.rows.size)
        assertEquals(2, m.rows[0].size)
        val cases = first("\\begin{cases} 1 & x>0 \\\\ 0 & \\text{else} \\end{cases}") as MMatrix
        assertEquals(2, cases.rows.size)
        val arr = first("\\begin{array}{|c|c|}\\hline a & b \\\\ \\hline \\end{array}") as MMatrix
        assertEquals(setOf(0, 1), arr.hlines)
        assertEquals(setOf(0, 1, 2), arr.vlines)
    }

    @Test fun `top level alignment markers build an aligned block`() {
        val m = parse("a &= b \\\\ c &= d") as MMatrix
        assertEquals("aligned", m.env)
        assertEquals(2, m.rows.size)
    }

    @Test fun `infix fractions`() {
        val f = first("{n \\choose k}").let { (it as MRow).items.single() as MFrac }
        assertEquals("(", f.left)
        assertTrue(!f.rule)
        assertTrue(first("a \\over b") is MFrac)
    }

    @Test fun `roots accents and text`() {
        val r = first("\\sqrt[3]{x}") as MSqrt
        assertTrue(r.index != null)
        assertEquals("hat", (first("\\hat{x}") as MAccent).kind)
        val t = first("\\text{if } x") as MText
        assertEquals("if ", t.text)
        assertEquals("≠", (first("\\not=") as MAtom).text)
    }

    @Test fun `symbols and named operators`() {
        val sum = first("\\sum_{i=1}^n") as MScripts
        val atom = sum.base as MAtom
        assertTrue(atom.large && atom.limits == Limits.LIMITS)
        val lim = first("\\lim_{x\\to0}") as MScripts
        assertEquals("lim", (lim.base as MAtom).text)
        assertEquals(Cls.REL, ((first("\\leq") as MAtom).cls))
        assertEquals("−", (first("-") as MAtom).text)
    }

    @Test fun `unknown commands become visible errors`() {
        val n = parse("\\foo{x} + 1")
        assertTrue(items(n).any { it is MError && it.source == "\\foo" })
    }

    @Test fun `colors`() {
        val s = first("\\textcolor{red}{x}") as MStyled
        assertTrue(s.color != null)
        assertTrue((first("\\textcolor[HTML]{00FF00}{x}") as MStyled).color == 0xFF00FF00.toInt())
        val group = first("{\\color{blue} x}") as MRow
        assertTrue((group.items.single() as MStyled).color != null)
    }

    @Test fun `malformed input never throws`() {
        for (s in listOf("\\frac{a", "}{", "\\begin{pmatrix} a & ", "\\left( x", "x^", "_", "\\", "\\sqrt[", "{{{{", "\\end{x}", "a & b \\\\", "\\right)", "\\begin{", "\\text{unclosed", "x^^2", "\\\\", "&&&")) {
            parse(s)
        }
    }

    @Test fun `pathological nesting and size collapse into an error`() {
        assertTrue(parse("{".repeat(500) + "x" + "}".repeat(500)) is MError)
        assertTrue(parse("x".repeat(LatexParser.MAX_LENGTH + 1)) is MError)
        parse("\\frac{".repeat(300) + "x" + "}{y}".repeat(300))
    }

    @Test fun `random latexish input never throws`() {
        val rnd = java.util.Random(3)
        val bits = listOf("\\frac", "{", "}", "^", "_", "&", "\\\\", "x", "1", "\\left(", "\\right)", "\\sqrt", "[", "]", "\\begin{pmatrix}", "\\end{pmatrix}", "\\text", "\\sum", "\\int", "+", "=", "\\alpha", " ", "\\over", "\\color{red}", "'", "\\not", "\\middle|", "\\hline", "\\xrightarrow", "\\overbrace", "\\substack", "$", "%", "é")
        repeat(20000) {
            val s = buildString { repeat(rnd.nextInt(30)) { append(bits[rnd.nextInt(bits.size)]) } }
            parse(s)
        }
    }
}
