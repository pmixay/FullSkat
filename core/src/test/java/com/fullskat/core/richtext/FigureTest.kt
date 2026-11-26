package com.fullskat.core.richtext

import com.fullskat.core.richtext.figure.AngleMark
import com.fullskat.core.richtext.figure.Bars
import com.fullskat.core.richtext.figure.ExprError
import com.fullskat.core.richtext.figure.ExprParser
import com.fullskat.core.richtext.figure.Figure
import com.fullskat.core.richtext.figure.FigureScale
import com.fullskat.core.richtext.figure.FunctionItem
import com.fullskat.core.richtext.figure.Json
import com.fullskat.core.richtext.figure.Palette
import com.fullskat.core.richtext.figure.PathSeg
import com.fullskat.core.richtext.figure.Polar
import com.fullskat.core.richtext.figure.Segment
import com.fullskat.core.richtext.figure.SvgPath
import com.fullskat.core.richtext.markdown.CodeBlock
import com.fullskat.core.richtext.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FigureTest {
    private fun ev(src: String, v: Double, vars: Set<String> = ExprParser.X) = ExprParser.parse(src, vars).eval(v)

    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= eps)

    // ---------------------------------------------------------------- JSON

    @Test fun jsonParsesNestedValuesAndEscapes() {
        val v = Json.parse("""{"a": [1, -2.5e1, true, null], "s": "q\"A\n", "o": {}, "t": [1,2,],}""") as Map<*, *>
        assertEquals(listOf(1.0, -25.0, true, null), v["a"])
        assertEquals("q\"A\n", v["s"])
        assertEquals(emptyMap<String, Any>(), v["o"])
        assertEquals(listOf(1.0, 2.0), v["t"]) // trailing commas tolerated
        assertThrows(Json.Error::class.java) { Json.parse("{\"a\": }") }
        assertThrows(Json.Error::class.java) { Json.parse("[1] 2") }
        assertThrows(Json.Error::class.java) { Json.parse("[".repeat(100)) }
    }

    // ---------------------------------------------------------------- expressions

    @Test fun expressionPrecedenceAndImplicitMultiplication() {
        near(-4.0, ev("-x^2", 2.0)) // unary minus binds looser than ^
        near(512.0, ev("2^3^2", 0.0)) // right associative
        near(0.5, ev("2^-1", 0.0))
        near(18.0, ev("2x^2", 3.0))
        near(8.0, ev("2(x+1)", 3.0))
        near(6.0, ev("2|x|", -3.0))
        near(2.0, ev("||x|-1|", -3.0))
        near(2 * PI, ev("2pi", 0.0))
        near(2 * PI, ev("2π", 0.0))
        near(9.0, ev("x**2", 3.0))
        near(1.0, ev("7 % 3", 0.0))
        near(2.0, ev("mod(-1, 3)", 0.0))
        near(1.5, ev("3/2x", 1.0)) // left to right: (3/2)·x
    }

    @Test fun functionsWithAndWithoutParentheses() {
        near(sin(2.0), ev("sin x", 2.0))
        near(sin(4.0), ev("sin 2x", 2.0))
        near(sin(1.0) * kotlin.math.cos(1.0), ev("sin x cos x", 1.0))
        near(sin(1.0) * sin(1.0), ev("sin^2(x)", 1.0))
        near(sin(1.0) * sin(1.0), ev("sin(x)^2", 1.0))
        near(1.0, ev("ln(e)", 0.0))
        near(2.0, ev("log10(100)", 0.0))
        near(3.0, ev("log(8, 2)", 0.0))
        near(E, ev("exp(1)", 0.0))
        near(3.0, ev("max(1, x, 2)", 3.0))
        near(5.0, ev("hypot(3, 4)", 0.0))
        near(3.0, ev("if(x < 0, -x, x)", -3.0))
        near(1.0, ev("x >= 2", 2.0))
        near(3.0, ev("round(2.5)", 0.0))
        near(sqrt(2.0), ev("sqrt 2", 0.0))
    }

    @Test fun domainErrorsAreNaNNotExceptions() {
        assertTrue(ev("sqrt(x)", -1.0).isNaN())
        assertTrue(ev("ln(x)", -1.0).isNaN())
        assertTrue(ev("1/x", 0.0).isInfinite())
    }

    @Test fun curveVariablesAndErrors() {
        near(1.0, ev("1 + cos(θ)", PI / 2, ExprParser.T))
        near(2.0, ev("2t", 1.0, ExprParser.T))
        near(PI, ev("theta", PI, ExprParser.T))
        assertThrows(ExprError::class.java) { ExprParser.parse("sin(t)", ExprParser.X) }
        assertThrows(ExprError::class.java) { ExprParser.parse("erf(x)") }
        assertThrows(ExprError::class.java) { ExprParser.parse("(x + 1") }
        assertThrows(ExprError::class.java) { ExprParser.parse("x +") }
        assertThrows(ExprError::class.java) { ExprParser.parse("x $ 2") }
        assertThrows(ExprError::class.java) { ExprParser.parse("min x") }
        assertThrows(ExprError::class.java) { ExprParser.parse("(".repeat(200) + "x" + ")".repeat(200)) }
    }

    // ---------------------------------------------------------------- figures

    @Test fun figureParsesItemsColoursAndDefaults() {
        val f = Figure.parse(
            """{"title":"Две кривые","x_range":[-6.28319,6.28319],"items":[
               {"type":"function","expr":"sin(x)","label":"${'$'}\\sin x${'$'}"},
               {"type":"function","expr":"cos(x)","color":"magenta","dash":true},
               {"type":"function","expr":"erf(x)"},
               {"type":"segment","from":[0,0],"to":[1,1]},
               {"type":"angle","at":[0,0],"from":[1,0],"to":[0,1]},
               {"type":"teapot"}]}""",
        )
        assertEquals("Две кривые", f.title)
        assertEquals(4, f.items.size) // erf and the teapot are skipped
        val (sine, cosine) = f.items.filterIsInstance<FunctionItem>()
        assertEquals(Palette.series(0), sine.style.color)
        assertEquals(Palette.named("magenta"), cosine.style.color)
        assertTrue(cosine.style.dash)
        assertEquals(Palette.INK, (f.items[2] as Segment).style.color)
        assertTrue(f.items[3] is AngleMark)
        assertTrue(f.axes && f.grid && !f.equal)
        assertEquals(listOf(Palette.series(0) to "\$\\sin x\$"), f.legend)
        assertTrue(FigureScale.prefersPi(f))
    }

    @Test fun geometryDefaultsToEqualScaleWithoutAxes() {
        val f = Figure.parse("""{"axes":false,"items":[{"type":"circle","center":[0,0],"r":1}]}""")
        assertFalse(f.axes)
        assertFalse(f.grid)
        assertTrue(f.equal)
        assertThrows(Json.Error::class.java) { Figure.parse("""{"items":[{"type":"function","expr":"nope("}]}""") }
        assertThrows(Json.Error::class.java) { Figure.parse("""[1, 2]""") }
    }

    @Test fun seriesTakePaletteSlotsInOrder() {
        val f = Figure.parse("""{"items":[{"type":"bars","values":[1,2],"labels":["a","b"],"label":"2025"},{"type":"bars","values":[2,3],"label":"2026"},{"type":"polar","expr":"1"}]}""")
        val bars = f.items.filterIsInstance<Bars>()
        assertEquals(Palette.series(0), bars[0].style.color)
        assertEquals(Palette.series(1), bars[1].style.color)
        assertEquals(Palette.series(2), (f.items[2] as Polar).style.color)
        val view = FigureScale.fit(f)
        assertEquals(-1.0, view.x0, 1e-6) // the unit circle reaches past the first category's slot (-0.5)
        assertEquals(1.5, view.x1, 1e-9) // categories 0..1
        assertTrue(view.y0 < -1.0) // and below the bars' zero line
    }

    @Test fun fitUsesGivenRangesAndIgnoresAsymptotesInY() {
        val f = Figure.parse("""{"x_range":[-6.28319,6.28319],"items":[{"type":"function","expr":"tan(x)"}]}""")
        val v = FigureScale.fit(f)
        assertEquals(-6.28319, v.x0, 1e-9)
        assertTrue("tan fits to a few units, got ${v.y0}..${v.y1}", v.y1 in 2.0..30.0 && v.y0 in -30.0..-2.0)
        val sine = FigureScale.fit(Figure.parse("""{"x_range":[0,10],"items":[{"type":"function","expr":"sin(x)"}]}"""))
        assertEquals(-1.2, sine.y0, 0.05)
        assertEquals(1.2, sine.y1, 0.05)
        val bars = FigureScale.fit(Figure.parse("""{"items":[{"type":"bars","values":[3,5,4]}]}"""))
        assertEquals(0.0, bars.y0, 1e-9) // bars stand on zero
        assertEquals(5.5, bars.y1, 0.01)
    }

    @Test fun ticksAreRoundAndPiTicksAreNamed() {
        assertEquals(listOf("−4", "−2", "0", "2", "4"), FigureScale.ticks(-5.0, 5.0, 5).map { it.label })
        assertEquals(listOf("0", "0.5", "1"), FigureScale.ticks(0.0, 1.0, 3).map { it.label })
        assertEquals(listOf("0", "20k", "40k"), FigureScale.ticks(0.0, 45000.0, 3).map { it.label })
        assertEquals(listOf("−2π", "−π", "0", "π", "2π"), FigureScale.ticks(-2 * PI, 2 * PI, 5, pi = true).map { it.label })
        assertEquals(listOf("0", "π/2", "π", "3π/2", "2π"), FigureScale.ticks(0.0, 2 * PI, 5, pi = true).map { it.label })
        assertEquals("−3π/4", FigureScale.piLabel(-3))
    }

    @Test fun samplingBreaksAtAsymptotesAndFindsDomainEdges() {
        val tan = FigureScale.sampleFunction(ExprParser.parse("tan(x)"), -3.0, 3.0, 300, -5.0, 5.0)
        val breaks = (0 until tan.size / 2).count { tan[2 * it].isNaN() }
        assertEquals(2, breaks) // at -π/2 and π/2
        for (i in 0 until tan.size / 2) if (!tan[2 * i + 1].isNaN()) assertTrue(tan[2 * i + 1] in -15.0..15.0) // pulled into the band
        val root = FigureScale.sampleFunction(ExprParser.parse("sqrt(x)"), -1.0, 1.0, 10, 0.0, 1.0)
        assertEquals(0.0, root[0], 1e-6) // starts exactly at the domain edge, not at the next sample
        assertEquals(1.0, root[root.size - 1], 1e-9)
    }

    // ---------------------------------------------------------------- SVG paths

    @Test fun svgPathCommandsAndArcs() {
        val segs = SvgPath.parse("M0 0 L1 0 h1 v1 l-1,0 Q 0 2 0 1 z m 3 3 a1 1 0 0 1 2 0")
        assertTrue(segs[0] == PathSeg.Move(0.0, 0.0))
        assertTrue(segs[1] == PathSeg.Line(1.0, 0.0))
        assertTrue(segs[2] == PathSeg.Line(2.0, 0.0))
        assertTrue(segs[3] == PathSeg.Line(2.0, 1.0))
        assertTrue(segs[4] == PathSeg.Line(1.0, 1.0))
        assertTrue(segs[5] is PathSeg.Cubic)
        assertTrue(segs[6] == PathSeg.Close)
        assertTrue(segs[7] == PathSeg.Move(3.0, 3.0)) // relative to the subpath start after z
        val arc = segs.drop(8)
        assertEquals(2, arc.size) // a half circle in two 90° pieces
        val end = arc.last() as PathSeg.Cubic
        assertEquals(5.0, end.x, 1e-9)
        assertEquals(3.0, end.y, 1e-9)
        assertTrue(SvgPath.parse("L 1 1").isEmpty()) // must start with a move
        assertEquals(2, SvgPath.parse("M0 0 1 1 foo").size) // implicit line-to, stops at garbage
    }

    // ---------------------------------------------------------------- Markdown

    @Test fun figureBlocksArriveAsCodeBlocks() {
        val blocks = Markdown.parse("Смотри:\n\n```figure\n{\"items\":[{\"type\":\"point\",\"at\":[0,0]}]}\n```\n\nГотово.")
        val code = blocks.filterIsInstance<CodeBlock>().single()
        assertEquals("figure", code.language)
        assertTrue(code.closed)
        assertNull(runCatching { Figure.parse(code.code) }.exceptionOrNull())
    }
}
