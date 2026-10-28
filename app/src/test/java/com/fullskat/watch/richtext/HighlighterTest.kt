package com.fullskat.watch.richtext

import com.fullskat.watch.richtext.code.Highlighter
import com.fullskat.watch.richtext.code.Tok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterTest {
    private fun toks(code: String, lang: String): List<Pair<String, Tok>> =
        Highlighter.highlight(code, lang).map { code.substring(it.start, it.end) to it.tok }

    @Test fun `kotlin`() {
        val t = toks("fun add(a: Int): Int = a + 1 // sum\nval s = \"hi \$a\"", "kotlin")
        assertTrue("fun" to Tok.KEYWORD in t)
        assertTrue("add" to Tok.FUNCTION in t)
        assertTrue("Int" to Tok.TYPE in t)
        assertTrue("1" to Tok.NUMBER in t)
        assertTrue("// sum" to Tok.COMMENT in t)
        assertTrue("\"hi \$a\"" to Tok.STRING in t)
    }

    @Test fun `python triple quotes decorators and prefixes`() {
        val code = "@app.route('/')\ndef f(x):\n    \"\"\"doc\n    string\"\"\"\n    return f\"{x}\" # c"
        val t = toks(code, "py")
        assertTrue("@app.route" to Tok.ANNOTATION in t)
        assertTrue("def" to Tok.KEYWORD in t)
        assertTrue("\"\"\"doc\n    string\"\"\"" to Tok.STRING in t)
        assertTrue("f\"{x}\"" to Tok.STRING in t)
        assertTrue("# c" to Tok.COMMENT in t)
    }

    @Test fun `json keys differ from string values`() {
        val t = toks("{\"name\": \"x\", \"n\": 1.5e3, \"ok\": true}", "json")
        assertTrue("\"name\"" to Tok.ATTR in t)
        assertTrue("\"x\"" to Tok.STRING in t)
        assertTrue("1.5e3" to Tok.NUMBER in t)
        assertTrue("true" to Tok.CONSTANT in t)
    }

    @Test fun `bash variables and comments`() {
        val t = toks("echo \"\$HOME\" \${PATH} # hi\nls -la", "bash")
        assertTrue("echo" to Tok.FUNCTION in t)
        assertTrue("\${PATH}" to Tok.VARIABLE in t)
        assertTrue("# hi" to Tok.COMMENT in t)
    }

    @Test fun `html tags attributes and strings`() {
        val t = toks("<div class=\"a\" id=b>x</div><!-- c -->", "html")
        assertTrue("<div" to Tok.TAG in t)
        assertTrue("class" to Tok.ATTR in t)
        assertTrue("\"a\"" to Tok.STRING in t)
        assertTrue("<!-- c -->" to Tok.COMMENT in t)
    }

    @Test fun `diff lines`() {
        val t = toks("@@ -1 +1 @@\n-old\n+new\n same", "diff")
        assertEquals(listOf(Tok.ANNOTATION, Tok.DELETE, Tok.INSERT), t.map { it.second })
    }

    @Test fun `sql is case insensitive`() {
        val t = toks("SELECT count(*) FROM users WHERE id = 3 -- x", "sql")
        assertTrue("SELECT" to Tok.KEYWORD in t)
        assertTrue("count" to Tok.FUNCTION in t)
        assertTrue("-- x" to Tok.COMMENT in t)
    }

    @Test fun `plain languages and unknown ones`() {
        assertTrue(Highlighter.highlight("anything", "text").isEmpty())
        assertTrue(toks("if (x) return \"s\" // c", "") .size >= 3) // generic fallback still colours
    }

    @Test fun `spans are sorted, disjoint and inside the text for arbitrary input`() {
        val rnd = java.util.Random(11)
        val alphabet = "abcXYZ019 \n\t\"'`/*#@\$<>!=-+(){}[]:;,.\\_"
        val langs = listOf("kotlin", "python", "js", "c", "bash", "sql", "json", "yaml", "css", "html", "diff", "rust", "go", "", "unknown")
        repeat(4000) {
            val s = buildString { repeat(rnd.nextInt(120)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            for (l in langs) {
                var prev = 0
                for (sp in Highlighter.highlight(s, l)) {
                    assertTrue("$l: $sp in '$s'", sp.start >= prev && sp.end > sp.start && sp.end <= s.length)
                    prev = sp.end
                }
            }
        }
    }
}
