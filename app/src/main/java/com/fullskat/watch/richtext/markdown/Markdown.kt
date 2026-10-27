package com.fullskat.watch.richtext.markdown

sealed interface Block
data class Paragraph(val inlines: List<Inline>) : Block
data class Heading(val level: Int, val inlines: List<Inline>) : Block

/** [closed] is false while the closing fence has not streamed in yet. */
data class CodeBlock(val language: String, val code: String, val closed: Boolean) : Block
data class DisplayMath(val latex: String) : Block
data class ListItem(val marker: String, val blocks: List<Block>) : Block
data class Quote(val blocks: List<Block>) : Block
data object Rule : Block
data class Table(val header: List<List<Inline>>, val align: List<Align>, val rows: List<List<List<Inline>>>) : Block

enum class Align { START, CENTER, END }

sealed interface Inline
data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false) : Inline
data class Code(val text: String) : Inline
data class Math(val latex: String) : Inline
data object Break : Inline

/**
 * Markdown for chat answers: paragraphs, headings, lists, quotes, tables, fenced code, display math
 * ($$ $$, \[ \], \begin{align}) and inline styles with $inline math$. It tolerates half-streamed text:
 * an unclosed code fence is still a code block, an unclosed $$ or ** stays literal until it is closed.
 */
object Markdown {
    private val MATH_ENVS = setOf(
        "equation", "equation*", "align", "align*", "aligned", "gather", "gather*", "gathered", "multline", "multline*", "eqnarray", "eqnarray*",
        "split", "cases", "array", "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "math", "displaymath", "flalign", "flalign*",
    )
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`\s{]*)[^`]*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private val LIST = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
    private val BEGIN_ENV = Regex("""^\s*\\begin\{([A-Za-z*]+)\}""")

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return parseBlocks(lines)
    }

    private fun parseBlocks(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) {
                blocks += Paragraph(parseInlines(para.joinToString("\n")))
                para.clear()
            }
        }
        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { flush(); i++; continue }

            // fenced code
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val indent = line.length - line.trimStart().length
                val body = ArrayList<String>()
                var closed = false
                i++
                while (i < n) {
                    val l = lines[i]
                    val t = l.trim()
                    if (t.length >= marker.length && t.all { it == marker[0] }) { closed = true; i++; break }
                    body += if (indent > 0 && l.length >= indent && l.substring(0, indent).isBlank()) l.substring(indent) else l
                    i++
                }
                blocks += CodeBlock(lang, body.joinToString("\n"), closed)
                continue
            }

            // display math: $$ ... $$ and \[ ... \]
            val display = displayMathAt(lines, i)
            if (display != null) {
                flush()
                blocks += DisplayMath(display.first)
                i = display.second
                continue
            }

            val env = BEGIN_ENV.find(line)
            if (env != null && env.groupValues[1] in MATH_ENVS) {
                val name = env.groupValues[1]
                val end = "\\end{$name}"
                var j = i
                val sb = StringBuilder()
                var found = false
                while (j < n) {
                    sb.append(lines[j]).append('\n')
                    if (lines[j].contains(end)) { found = true; j++; break }
                    j++
                }
                if (found) {
                    flush()
                    blocks += DisplayMath(sb.toString().trim())
                    i = j
                    continue
                }
            }

            // heading
            val h = HEADING.matchEntire(line)
            if (h != null) {
                flush()
                blocks += Heading(h.groupValues[1].length, parseInlines(h.groupValues[2]))
                i++
                continue
            }

            // horizontal rule
            if (RULE.matches(line)) {
                flush()
                blocks += Rule
                i++
                continue
            }

            // block quote
            if (trimmed.startsWith(">")) {
                flush()
                val inner = ArrayList<String>()
                while (i < n && lines[i].trim().startsWith(">")) {
                    inner += lines[i].trim().removePrefix(">").removePrefix(" ")
                    i++
                }
                blocks += Quote(parseBlocks(inner))
                continue
            }

            // list item
            val li = LIST.matchEntire(line)
            if (li != null && !RULE.matches(line)) {
                flush()
                val markerIndent = li.groupValues[1].length
                var marker = li.groupValues[2]
                var first = li.groupValues[3]
                if (marker.length == 1) {
                    marker = "•"
                    when {
                        first.startsWith("[ ] ") -> { marker = "☐"; first = first.substring(4) }
                        first.startsWith("[x] ") || first.startsWith("[X] ") -> { marker = "☑"; first = first.substring(4) }
                    }
                }
                val content = arrayListOf(first)
                i++
                while (i < n) {
                    val l = lines[i]
                    if (l.isBlank()) {
                        // a blank line continues the item only when the next non-blank line is indented deeper
                        var k = i + 1
                        while (k < n && lines[k].isBlank()) k++
                        if (k < n && indentOf(lines[k]) > markerIndent && LIST.matchEntire(lines[k]) == null) {
                            content += ""
                            i++
                            continue
                        }
                        break
                    }
                    val ind = indentOf(l)
                    val nested = LIST.matchEntire(l)
                    if (ind > markerIndent) {
                        content += l.substring(minOf(l.length, markerIndent + 2).coerceAtMost(ind))
                        i++
                    } else if (nested == null && !FENCE.matches(l) && !HEADING.matches(l) && !l.trim().startsWith(">") && displayMathAt(lines, i) == null) {
                        content += l.trim() // lazy continuation of the item's paragraph
                        i++
                    } else break
                }
                blocks += ListItem(marker, parseBlocks(content))
                continue
            }

            // table
            if (line.contains('|') && i + 1 < n && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val header = splitRow(line)
                val sep = splitRow(lines[i + 1])
                if (header.size == sep.size || header.size > 1) {
                    flush()
                    val align = sep.map { s ->
                        val t = s.trim()
                        when {
                            t.startsWith(":") && t.endsWith(":") -> Align.CENTER
                            t.endsWith(":") -> Align.END
                            else -> Align.START
                        }
                    }
                    i += 2
                    val rows = ArrayList<List<List<Inline>>>()
                    while (i < n && lines[i].isNotBlank() && lines[i].contains('|')) {
                        rows += splitRow(lines[i]).map { parseInlines(it.trim()) }
                        i++
                    }
                    blocks += Table(header.map { parseInlines(it.trim()) }, align, rows)
                    continue
                }
            }

            para += line.trimEnd()
            i++
        }
        flush()
        return blocks
    }

    private fun indentOf(s: String): Int {
        var n = 0
        for (c in s) { if (c == ' ') n++ else if (c == '\t') n += 4 else break }
        return n
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inCode = false
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') { sb.append('|'); i += 2; continue }
            if (c == '`') inCode = !inCode
            if (c == '|' && !inCode) { cells += sb.toString(); sb.clear() } else sb.append(c)
            i++
        }
        cells += sb.toString()
        return cells
    }

    /** If a display-math block starts at [start], returns its LaTeX and the index of the line after it. */
    private fun displayMathAt(lines: List<String>, start: Int): Pair<String, Int>? {
        val first = lines[start].trim()
        val (open, close) = when {
            first.startsWith("$$") -> "$$" to "$$"
            first.startsWith("\\[") -> "\\[" to "\\]"
            else -> return null
        }
        val rest = first.substring(open.length)
        val sameLine = rest.indexOf(close)
        if (sameLine >= 0) {
            if (rest.substring(sameLine + close.length).isNotBlank()) return null // text follows: let it be inline math
            return rest.substring(0, sameLine).trim() to start + 1
        }
        val sb = StringBuilder(rest)
        var j = start + 1
        while (j < lines.size) {
            val l = lines[j]
            val k = l.indexOf(close)
            if (k >= 0) {
                if (l.substring(k + close.length).isNotBlank()) return null
                sb.append('\n').append(l, 0, k)
                return sb.toString().trim() to j + 1
            }
            sb.append('\n').append(l)
            j++
        }
        return null // not closed yet (still streaming)
    }

    // ---------------------------------------------------------------- inlines

    fun parseInlines(source: String): List<Inline> {
        val out = ArrayList<Inline>()
        parseInto(source, out, bold = false, italic = false, strike = false)
        return merge(out)
    }

    private fun merge(inlines: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>()
        for (x in inlines) {
            val last = out.lastOrNull()
            if (x is Text && last is Text && x.bold == last.bold && x.italic == last.italic && x.strike == last.strike) {
                out[out.size - 1] = last.copy(text = last.text + x.text)
            } else if (x is Text && x.text.isEmpty()) {
                continue
            } else out += x
        }
        return out
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    private fun parseInto(s: String, out: MutableList<Inline>, bold: Boolean, italic: Boolean, strike: Boolean) {
        val buf = StringBuilder()
        fun text() {
            if (buf.isNotEmpty()) { out += Text(buf.toString(), bold, italic, strike); buf.clear() }
        }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val d = s[i + 1]
                    if (d == '(') {
                        val e = s.indexOf("\\)", i + 2)
                        if (e > i + 2) { text(); out += Math(s.substring(i + 2, e).trim()); i = e + 2; continue }
                    }
                    if (d == '[') {
                        val e = s.indexOf("\\]", i + 2)
                        if (e > i + 2) { text(); out += Math(s.substring(i + 2, e).trim()); i = e + 2; continue }
                    }
                    if (d in "\\`*_{}[]()#+-.!|~$<>\"'&%") { buf.append(d); i += 2; continue }
                    buf.append(c); i++
                }
                c == '`' -> {
                    var run = 1
                    while (i + run < n && s[i + run] == '`') run++
                    val closer = "`".repeat(run)
                    val e = s.indexOf(closer, i + run)
                    if (e > 0) {
                        text()
                        out += Code(s.substring(i + run, e).trim().ifEmpty { s.substring(i + run, e) })
                        i = e + run
                    } else { buf.append(closer); i += run }
                }
                c == '$' -> {
                    val m = mathAt(s, i)
                    if (m != null) { text(); out += Math(m.first); i = m.second } else { buf.append(c); i++ }
                }
                c == '\n' -> { text(); out += Break; i++ }
                (c == '*' || c == '_') && i + 1 < n && s[i + 1] == c -> {
                    val e = findCloser(s, i + 2, "$c$c")
                    val canOpen = i + 2 < n && !s[i + 2].isWhitespace() && (c == '*' || i == 0 || !isWordChar(s[i - 1]))
                    if (canOpen && e > 0) {
                        text(); parseInto(s.substring(i + 2, e), out, true, italic, strike); i = e + 2
                    } else { buf.append(c).append(c); i += 2 }
                }
                (c == '*' || c == '_') -> {
                    val canOpen = i + 1 < n && !s[i + 1].isWhitespace() && (c == '*' || i == 0 || !isWordChar(s[i - 1]))
                    val e = if (canOpen) findCloser(s, i + 1, c.toString()) else -1
                    if (e > 0) {
                        text(); parseInto(s.substring(i + 1, e), out, bold, true, strike); i = e + 1
                    } else { buf.append(c); i++ }
                }
                c == '~' && i + 1 < n && s[i + 1] == '~' -> {
                    val e = findCloser(s, i + 2, "~~")
                    if (e > 0 && i + 2 < n && !s[i + 2].isWhitespace()) {
                        text(); parseInto(s.substring(i + 2, e), out, bold, italic, true); i = e + 2
                    } else { buf.append("~~"); i += 2 }
                }
                c == '!' && i + 1 < n && s[i + 1] == '[' -> {
                    val link = linkAt(s, i + 1)
                    if (link != null) { buf.append(link.first); i = link.second } else { buf.append(c); i++ }
                }
                c == '[' -> {
                    val link = linkAt(s, i)
                    if (link != null) { text(); parseInto(link.first, out, bold, italic, strike); i = link.second } else { buf.append(c); i++ }
                }
                c == '<' -> {
                    val br = Regex("""^<br\s*/?>""", RegexOption.IGNORE_CASE).find(s.substring(i, minOf(n, i + 6)))
                    if (br != null) { text(); out += Break; i += br.value.length }
                    else {
                        val auto = Regex("""^<((?:https?://|mailto:)[^>\s]+)>""").find(s.substring(i))
                        if (auto != null) { buf.append(auto.groupValues[1]); i += auto.value.length } else { buf.append(c); i++ }
                    }
                }
                else -> { buf.append(c); i++ }
            }
        }
        text()
    }

    /** Index of the closing delimiter [delim] at or after [from], skipping code spans and math; -1 if there is none. */
    private fun findCloser(s: String, from: Int, delim: String): Int {
        var i = from
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '`') {
                var run = 1
                while (i + run < n && s[i + run] == '`') run++
                val e = s.indexOf("`".repeat(run), i + run)
                i = if (e > 0) e + run else i + run
                continue
            }
            if (c == '$') {
                val m = mathAt(s, i)
                if (m != null) { i = m.second; continue }
            }
            if (s.startsWith(delim, i) && i > from && !s[i - 1].isWhitespace()) {
                val after = i + delim.length
                // A single-char closer that is really the start of "**" belongs to a nested bold: step over it.
                if (delim.length == 1 && after < n && s[after] == delim[0]) { i += 2; continue }
                val intraword = delim[0] == '_' && after < n && isWordChar(s[after])
                if (!intraword) return i
            }
            i++
        }
        return -1
    }

    /** Inline math starting at the '$' at [i]: returns the LaTeX and the index after the closing delimiter. */
    private fun mathAt(s: String, i: Int): Pair<String, Int>? {
        val n = s.length
        if (s.startsWith("$$", i)) {
            val e = s.indexOf("$$", i + 2)
            if (e > i + 2) return s.substring(i + 2, e).trim() to e + 2
            return null
        }
        if (i + 1 >= n || s[i + 1].isWhitespace() || s[i + 1] == '$') return null
        var j = i + 1
        while (j < n) {
            val c = s[j]
            if (c == '\n') return null
            if (c == '\\') { j += 2; continue }
            if (c == '$') {
                if (s[j - 1].isWhitespace()) return null
                if (j + 1 < n && s[j + 1].isDigit()) return null // "$5 and $10": currency, not math
                if (j - i - 1 > 1500) return null
                return s.substring(i + 1, j) to j + 1
            }
            j++
        }
        return null
    }

    private fun linkAt(s: String, i: Int): Pair<String, Int>? {
        if (s[i] != '[') return null
        var depth = 0
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') { j += 2; continue }
            if (c == '[') depth++
            if (c == ']') { depth--; if (depth == 0) break }
            j++
        }
        if (j >= s.length || j + 1 >= s.length || s[j + 1] != '(') return null
        val close = s.indexOf(')', j + 2)
        if (close < 0) return null
        return s.substring(i + 1, j) to close + 1
    }
}
