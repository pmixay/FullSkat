package com.fullskat.watch.richtext

import com.fullskat.watch.richtext.markdown.Align
import com.fullskat.watch.richtext.markdown.Block
import com.fullskat.watch.richtext.markdown.Break
import com.fullskat.watch.richtext.markdown.Code
import com.fullskat.watch.richtext.markdown.CodeBlock
import com.fullskat.watch.richtext.markdown.DisplayMath
import com.fullskat.watch.richtext.markdown.Heading
import com.fullskat.watch.richtext.markdown.ListItem
import com.fullskat.watch.richtext.markdown.Markdown
import com.fullskat.watch.richtext.markdown.Math
import com.fullskat.watch.richtext.markdown.Paragraph
import com.fullskat.watch.richtext.markdown.Quote
import com.fullskat.watch.richtext.markdown.Rule
import com.fullskat.watch.richtext.markdown.Table
import com.fullskat.watch.richtext.markdown.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun blocks(s: String): List<Block> = Markdown.parse(s)
    private fun inlines(s: String) = Markdown.parseInlines(s)

    @Test fun `fenced code keeps language and body`() {
        val b = blocks("Run:\n```python\nprint(1)\n  x = 2\n```\nDone")
        assertEquals(3, b.size)
        val code = b[1] as CodeBlock
        assertEquals("python", code.language)
        assertEquals("print(1)\n  x = 2", code.code)
        assertTrue(code.closed)
    }

    @Test fun `unclosed fence is still a code block while streaming`() {
        val code = blocks("```kotlin\nfun main() {").single() as CodeBlock
        assertFalse(code.closed)
        assertEquals("fun main() {", code.code)
    }

    @Test fun `display math in all spellings`() {
        assertEquals(DisplayMath("a^2 + b^2"), blocks("$$ a^2 + b^2 $$").single())
        assertEquals(DisplayMath("x = 1"), blocks("$$\nx = 1\n$$").single())
        assertEquals(DisplayMath("\\frac{1}{2}"), blocks("\\[\\frac{1}{2}\\]").single())
        val env = blocks("\\begin{align}\na &= b \\\\\nc &= d\n\\end{align}").single() as DisplayMath
        assertTrue(env.latex.startsWith("\\begin{align}") && env.latex.endsWith("\\end{align}"))
    }

    @Test fun `unclosed display math stays literal text until it closes`() {
        val b = blocks("Look: \n$$ x^2 +")
        assertTrue(b.all { it is Paragraph })
    }

    @Test fun `inline math but not currency`() {
        val math = inlines("area is \$\\pi r^2\$ ok")
        assertEquals(listOf("area is ", "\\pi r^2", " ok"), math.map { if (it is Text) it.text else (it as Math).latex })
        assertTrue(inlines("it costs \$5 and \$10 total").none { it is Math })
        assertEquals(listOf<Any>(Math("x_1")), inlines("\\(x_1\\)"))
    }

    @Test fun `bold italic strike and code`() {
        val l = inlines("**bold *both* x** and ~~gone~~ and `code *no*` and _it_")
        val texts = l.filterIsInstance<Text>()
        assertTrue(texts.any { it.text == "both" && it.bold && it.italic })
        assertTrue(texts.any { it.text == "gone" && it.strike })
        assertEquals("code *no*", l.filterIsInstance<Code>().single().text)
        assertTrue(texts.any { it.text == "it" && it.italic && !it.bold })
    }

    @Test fun `snake case and unclosed markers stay literal`() {
        assertEquals(listOf<Any>(Text("use snake_case_names here")), inlines("use snake_case_names here"))
        assertEquals(listOf<Any>(Text("2 * 3 = 6 and **open")), inlines("2 * 3 = 6 and **open"))
    }

    @Test fun `escapes and links`() {
        assertEquals(listOf<Any>(Text("*not italic* \$5")), inlines("\\*not italic\\* \\\$5"))
        assertEquals(listOf<Any>(Text("see docs now")), inlines("see [docs](http://x.y/z) now"))
    }

    @Test fun `newline inside a paragraph is a line break`() {
        assertEquals(listOf(Text("a"), Break, Text("b")), inlines("a\nb"))
    }

    @Test fun `headings quotes and rules`() {
        val b = blocks("## Title\n> quoted\n> more\n\n---\ntext")
        assertEquals(Heading(2, listOf(Text("Title"))), b[0])
        assertTrue(b[1] is Quote)
        assertEquals(Rule, b[2])
        assertTrue(b[3] is Paragraph)
    }

    @Test fun `lists nest and keep numbers`() {
        val b = blocks("1. one\n2. two\n   - sub a\n   - sub b\n3. three\n\n- [x] done\n- [ ] todo")
        val items = b.filterIsInstance<ListItem>()
        assertEquals(listOf("1.", "2.", "3.", "☑", "☐"), items.map { it.marker })
        val two = items[1]
        assertEquals(3, two.blocks.size) // its text and its two nested bullets
        assertTrue(two.blocks.filterIsInstance<ListItem>().map { it.marker } == listOf("•", "•"))
    }

    @Test fun `tables`() {
        val t = blocks("| Name | Val |\n|:--|--:|\n| a | \$x^2\$ |\n| b | 2 |").single() as Table
        assertEquals(2, t.header.size)
        assertEquals(listOf(Align.START, Align.END), t.align)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows[0][1].single() is Math)
    }

    @Test fun `deeply nested input cannot overflow the stack`() {
        blocks(">".repeat(20000) + " x")
        blocks("- ".repeat(5000) + "x")
        inlines("*".repeat(20000))
        inlines("**a ".repeat(5000) + "**".repeat(5000))
        inlines("[".repeat(20000))
    }

    @Test fun `never throws on junk`() {
        val rnd = java.util.Random(7)
        val alphabet = "abc \n\n*_`~\$\\[]()<>|#>-+1.:=\"'{}^!&%"
        repeat(3000) {
            val s = buildString { repeat(rnd.nextInt(80)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            blocks(s)
        }
    }
}
