package com.fullskat.watch.richtext.math

/**
 * Tolerant LaTeX math parser. It never throws: input it cannot understand becomes [MError] nodes that are
 * shown literally, and pathological input (huge or deeply nested) collapses into a single error node.
 */
class LatexParser private constructor(private val src: String, private var level: Int) {
    private var pos = 0
    private var stop = Stop.END
    private var curHlines: MutableSet<Int>? = null
    private var curRowIndex = 0
    private var sawAmp = false

    private enum class Stop { END, BRACE, AMP, ROWSEP, ENV_END, RIGHT, MIDDLE }
    private class TooDeep : RuntimeException()

    companion object {
        const val MAX_LENGTH = 6000
        private const val MAX_DEPTH = 40

        fun parse(latex: String): MathNode {
            val text = latex.trim()
            if (text.length > MAX_LENGTH) return MError(text.take(60))
            return try {
                LatexParser(text, 0).parseTop()
            } catch (e: TooDeep) {
                MError(text.take(60))
            } catch (e: StackOverflowError) {
                MError(text.take(60))
            } catch (e: RuntimeException) {
                MError(text.take(60))
            }
        }

        private val NEGATIONS = mapOf(
            "=" to "≠", "<" to "≮", ">" to "≯", "≤" to "≰", "≥" to "≱", "∈" to "∉", "∋" to "∌", "⊂" to "⊄", "⊃" to "⊅",
            "⊆" to "⊈", "⊇" to "⊉", "≡" to "≢", "∼" to "≁", "≃" to "≄", "≅" to "≇", "≈" to "≉", "∥" to "∦", "∣" to "∤",
            "→" to "↛", "←" to "↚", "⇒" to "⇏", "⇐" to "⇍", "⇔" to "⇎", "↔" to "↮", "⊢" to "⊬", "⊨" to "⊭", "∃" to "∄",
            "≺" to "⊀", "≻" to "⊁",
        )

        private val FONT_COMMANDS = mapOf(
            "mathrm" to Variant.ROMAN, "mathit" to Variant.ITALIC, "mathbf" to Variant.BOLD, "boldsymbol" to Variant.BOLD_ITALIC,
            "bm" to Variant.BOLD_ITALIC, "pmb" to Variant.BOLD_ITALIC, "mathbfit" to Variant.BOLD_ITALIC,
            "mathcal" to Variant.SCRIPT, "mathscr" to Variant.SCRIPT, "mathfrak" to Variant.FRAKTUR, "frak" to Variant.FRAKTUR,
            "mathbb" to Variant.DOUBLE_STRUCK, "Bbb" to Variant.DOUBLE_STRUCK, "mathbbm" to Variant.DOUBLE_STRUCK,
            "mathsf" to Variant.SANS, "mathtt" to Variant.MONO, "mathnormal" to Variant.DEFAULT,
        )
        private val TEXT_COMMANDS = mapOf(
            "text" to Variant.ROMAN, "textrm" to Variant.ROMAN, "mbox" to Variant.ROMAN, "hbox" to Variant.ROMAN,
            "textnormal" to Variant.ROMAN, "textup" to Variant.ROMAN, "textsf" to Variant.SANS, "texttt" to Variant.MONO,
            "textbf" to Variant.BOLD, "textit" to Variant.ROMAN, "emph" to Variant.ROMAN, "textsc" to Variant.ROMAN,
        )
        private val SWITCH_VARIANTS = mapOf(
            "bf" to Variant.BOLD, "bfseries" to Variant.BOLD, "rm" to Variant.ROMAN, "rmfamily" to Variant.ROMAN,
            "it" to Variant.ITALIC, "itshape" to Variant.ITALIC, "sf" to Variant.SANS, "tt" to Variant.MONO, "cal" to Variant.SCRIPT,
        )
        private val SWITCH_STYLES = mapOf(
            "displaystyle" to STYLE_DISPLAY, "textstyle" to STYLE_TEXT, "scriptstyle" to STYLE_SCRIPT, "scriptscriptstyle" to STYLE_SCRIPT2,
        )
        private val ACCENT_COMMANDS = setOf(
            "hat", "widehat", "tilde", "widetilde", "bar", "dot", "ddot", "dddot", "vec", "acute", "grave", "check", "breve", "mathring",
            "overline", "underline", "overrightarrow", "overleftarrow", "overleftrightarrow", "underrightarrow", "underleftarrow",
            "underleftrightarrow", "overparen", "underparen",
        )
        private val X_ARROWS = mapOf(
            "xrightarrow" to "→", "xleftarrow" to "←", "xRightarrow" to "⇒", "xLeftarrow" to "⇐", "xleftrightarrow" to "↔",
            "xLeftrightarrow" to "⇔", "xmapsto" to "↦", "xhookrightarrow" to "↪", "xlongequal" to "=", "xtwoheadrightarrow" to "↠",
            "xrightharpoonup" to "⇀", "xrightleftharpoons" to "⇌",
        )
        private val CLASS_COMMANDS = mapOf(
            "mathrel" to Cls.REL, "mathbin" to Cls.BIN, "mathop" to Cls.OP, "mathord" to Cls.ORD, "mathopen" to Cls.OPEN,
            "mathclose" to Cls.CLOSE, "mathpunct" to Cls.PUNCT, "mathinner" to Cls.INNER,
        )
        private val IGNORED_COMMANDS = setOf(
            "label", "nonumber", "notag", "qedhere", "allowbreak", "relax", "protect", "mathstrut", "strut", "nolinebreak", "linebreak",
            "pagebreak", "newline", "break", "special", "hfill", "hfil", "vfill", "cr", "noindent", "centering", "smallskip", "medskip",
            "bigskip", "ignorespaces", "leavevmode", "par", "normalsize", "small", "footnotesize", "tiny",
        )
        private val STOP_NAMES = setOf("\\", "end", "right", "middle")
        private val INFIX = setOf("over", "atop", "choose", "brace", "brack")
        private val LIMIT_COMMANDS = mapOf("limits" to Limits.LIMITS, "nolimits" to Limits.NOLIMITS, "displaylimits" to Limits.AUTO)
        private val ARRAY_ENVS = setOf("array", "tabular", "tabular*", "array*", "subarray", "alignat", "alignedat", "alignat*")
    }

    // ---------------------------------------------------------------- top level

    private fun parseTop(): MathNode {
        val first = parseRow()
        if (stop == Stop.BRACE) { // stray closing brace: carry on after it
            val items = ArrayList<MathNode>(first.items)
            while (stop == Stop.BRACE) {
                pos++
                items += parseRow().items
            }
            if (stop != Stop.AMP && stop != Stop.ROWSEP) return MRow(items)
            return finishImplicitEnv(MRow(items))
        }
        if (stop == Stop.AMP || stop == Stop.ROWSEP) return finishImplicitEnv(first)
        return first
    }

    private fun finishImplicitEnv(first: MRow): MathNode {
        val rows = parseRows(null, first)
        val aligned = sawAmp || rows.any { it.size > 1 }
        return MMatrix(if (aligned) "aligned" else "gathered", rows, "", curHlines.orEmpty(), emptySet())
    }

    private fun MutableSet<Int>?.orEmpty(): Set<Int> = this ?: emptySet()

    // ---------------------------------------------------------------- rows

    private fun parseRow(): MRow {
        val items = ArrayList<MathNode>()
        parseItems(items)
        return MRow(items)
    }

    private fun parseItems(items: MutableList<MathNode>) {
        while (true) {
            skipWs()
            if (pos >= src.length) { stop = Stop.END; return }
            val c = src[pos]
            if (c == '}') { stop = Stop.BRACE; return }
            if (c == '&') { stop = Stop.AMP; return }
            if (c == '\\') {
                val name = peekCommand() ?: ""
                if (name == "\\") { stop = Stop.ROWSEP; return }
                if (name == "end") { stop = Stop.ENV_END; return }
                if (name == "right") { stop = Stop.RIGHT; return }
                if (name == "middle") { stop = Stop.MIDDLE; return }
                if (name in INFIX) {
                    consume(name)
                    val num = MRow(ArrayList(items))
                    val den = ArrayList<MathNode>()
                    parseItems(den)
                    items.clear()
                    items += when (name) {
                        "over" -> MFrac(num, MRow(den))
                        "atop" -> MFrac(num, MRow(den), rule = false)
                        "choose" -> MFrac(num, MRow(den), rule = false, left = "(", right = ")")
                        "brace" -> MFrac(num, MRow(den), rule = false, left = "{", right = "}")
                        else -> MFrac(num, MRow(den), rule = false, left = "[", right = "]")
                    }
                    return
                }
                val wrap = switchWrapper(name)
                if (wrap != null) {
                    val rest = ArrayList<MathNode>()
                    parseItems(rest)
                    items += wrap(MRow(rest))
                    return
                }
            }
            val item = parseItem() ?: continue
            items += parseScripts(item)
        }
    }

    /** \color, \displaystyle, \bf ...: they act on the rest of the enclosing group. Returns null if [name] is not one of them. */
    private fun switchWrapper(name: String): ((MathNode) -> MathNode)? {
        SWITCH_VARIANTS[name]?.let { v -> consume(name); return { MStyled(it, variant = v) } }
        SWITCH_STYLES[name]?.let { s -> consume(name); return { MStyled(it, style = s) } }
        if (name == "color") {
            consume(name)
            val color = readColor()
            return { MStyled(it, color = color) }
        }
        return null
    }

    private fun parseScripts(base: MathNode): MathNode {
        var sub: MathNode? = null
        var sup: MathNode? = null
        var limits = Limits.AUTO
        while (true) {
            skipWs()
            if (pos >= src.length) break
            val c = src[pos]
            if (c == '^') {
                pos++
                val arg = parseArg()
                sup = if (sup == null) arg else MRow(listOf(sup, arg))
            } else if (c == '_') {
                pos++
                val arg = parseArg()
                sub = if (sub == null) arg else MRow(listOf(sub, arg))
            } else if (c == '\'') {
                var n = 0
                while (pos < src.length && src[pos] == '\'') { n++; pos++ }
                val prime = MAtom(if (n == 1) "′" else if (n == 2) "″" else if (n == 3) "‴" else "′".repeat(n), Cls.ORD)
                sup = if (sup == null) prime else MRow(listOf(sup, prime))
            } else if (c == '\\') {
                val name = peekCommand() ?: ""
                val lim = LIMIT_COMMANDS[name]
                if (lim != null) { consume(name); limits = lim } else break
            } else break
        }
        return if (sub == null && sup == null) base else MScripts(base, sub, sup, limits)
    }

    // ---------------------------------------------------------------- items and arguments

    private fun parseItem(): MathNode? {
        val c = src[pos]
        return when (c) {
            '\\' -> {
                val name = peekCommand() ?: ""
                consume(name)
                parseCommand(name)
            }
            '{' -> { pos++; parseGroupBody() }
            '^', '_', '\'' -> MRow(emptyList()) // script without a base; parseScripts consumes it
            else -> charAtom()
        }
    }

    private fun parseGroupBody(): MRow {
        enter()
        val row = parseRow()
        if (stop == Stop.BRACE) pos++
        leave()
        return row
    }

    private fun parseArg(): MathNode {
        skipWs()
        if (pos >= src.length) return MRow(emptyList())
        val c = src[pos]
        if (c == '{') { pos++; return parseGroupBody() }
        if (c == '}' || c == '&') return MRow(emptyList())
        if (c == '\\') {
            val name = peekCommand() ?: ""
            if (name in STOP_NAMES) return MRow(emptyList())
            consume(name)
            return parseCommand(name) ?: MRow(emptyList())
        }
        if (c == '^' || c == '_') return MRow(emptyList())
        return charAtom()
    }

    private fun charAtom(): MathNode {
        val cp = src.codePointAt(pos)
        val s = String(Character.toChars(cp))
        pos += s.length
        return when {
            cp == '~'.code -> MSpace(0.25f)
            MathSymbols.isLatinLetter(cp) || MathSymbols.isGreekLetter(cp) || MathSymbols.isDigit(cp) -> MAtom(s, Cls.ORD, fixed = false)
            cp == '+'.code -> MAtom("+", Cls.BIN)
            cp == '-'.code -> MAtom("−", Cls.BIN)
            cp == '*'.code -> MAtom("∗", Cls.BIN)
            cp == '='.code || cp == '<'.code || cp == '>'.code || cp == ':'.code -> MAtom(s, Cls.REL)
            cp == ','.code || cp == ';'.code -> MAtom(s, Cls.PUNCT)
            cp == '('.code || cp == '['.code -> MAtom(s, Cls.OPEN)
            cp == ')'.code || cp == ']'.code -> MAtom(s, Cls.CLOSE)
            else -> MAtom(s, MathSymbols.classOf(cp))
        }
    }

    // ---------------------------------------------------------------- commands

    private fun parseCommand(name: String): MathNode? {
        enter()
        try {
            return parseCommandInner(name)
        } finally {
            leave()
        }
    }

    private fun parseCommandInner(name: String): MathNode? {
        MathSymbols.greekLower[name]?.let { return MAtom(it, Cls.ORD, fixed = false) }
        MathSymbols.greekUpper[name]?.let { return MAtom(it, Cls.ORD, fixed = false) }
        MathSymbols.largeOps[name]?.let { (glyph, lim) -> return MAtom(glyph, Cls.OP, large = true, limits = if (lim) Limits.LIMITS else Limits.NOLIMITS) }
        MathSymbols.namedOps[name]?.let { (text, lim) -> return MAtom(text, Cls.OP, limits = if (lim) Limits.LIMITS else Limits.NOLIMITS) }
        FONT_COMMANDS[name]?.let { return MStyled(parseArg(), variant = it) }
        TEXT_COMMANDS[name]?.let { return MText(textFromRaw(readBraceText()), it) }
        CLASS_COMMANDS[name]?.let { return MClass(it, parseArg()) }
        MathSymbols.spaces[name]?.let { return MSpace(it) }
        MathSymbols.dots[name]?.let { return MAtom(it, Cls.INNER) }
        if (name in ACCENT_COMMANDS) return MAccent(name, parseArg())
        X_ARROWS[name]?.let { glyph ->
            val below = readBracketMath()
            return MArrow(glyph, parseArg(), below)
        }
        when (name) {
            "iff", "implies", "impliedby" -> {
                val glyph = MathSymbols.relation.getValue(name)
                return MRow(listOf(MSpace(0.28f), MAtom(glyph, Cls.REL), MSpace(0.28f)))
            }
            "frac", "cfrac" -> return MFrac(parseArg(), parseArg())
            "dfrac" -> return MFrac(parseArg(), parseArg(), style = STYLE_DISPLAY)
            "tfrac" -> return MFrac(parseArg(), parseArg(), style = STYLE_TEXT)
            "binom" -> return MFrac(parseArg(), parseArg(), rule = false, left = "(", right = ")")
            "dbinom" -> return MFrac(parseArg(), parseArg(), rule = false, style = STYLE_DISPLAY, left = "(", right = ")")
            "tbinom" -> return MFrac(parseArg(), parseArg(), rule = false, style = STYLE_TEXT, left = "(", right = ")")
            "sqrt" -> {
                val index = readBracketMath()
                return MSqrt(parseArg(), index)
            }
            "left" -> return parseDelimited()
            "big", "Big", "bigg", "Bigg", "bigl", "Bigl", "biggl", "Biggl", "bigr", "Bigr", "biggr", "Biggr", "bigm", "Bigm", "biggm", "Biggm" -> {
                val lvl = when (name.lowercase().removeSuffix("l").removeSuffix("r").removeSuffix("m")) {
                    "big" -> 1
                    "bigg" -> 3
                    else -> if (name.startsWith("B")) (if (name.startsWith("Bigg")) 4 else 2) else 1
                }
                val level = if (name.startsWith("Bigg")) 4 else if (name.startsWith("bigg")) 3 else if (name.startsWith("Big")) 2 else 1
                val delim = readDelimiter()
                val cls = when {
                    name.endsWith("l") -> Cls.OPEN
                    name.endsWith("r") -> Cls.CLOSE
                    delim in listOf("(", "[", "{", "⟨", "⌊", "⌈") -> Cls.OPEN
                    delim in listOf(")", "]", "}", "⟩", "⌋", "⌉") -> Cls.CLOSE
                    else -> Cls.ORD
                }
                check(lvl > 0)
                return MSized(delim, level, cls)
            }
            "begin" -> return parseEnv()
            "overbrace" -> return MStack(parseArg(), null, null, Brace.OVER)
            "underbrace" -> return MStack(parseArg(), null, null, Brace.UNDER)
            "overset", "stackrel" -> { val over = parseArg(); return MStack(parseArg(), over, null) }
            "underset" -> { val under = parseArg(); return MStack(parseArg(), null, under) }
            "substack" -> { skipWs(); if (pos < src.length && src[pos] == '{') { pos++; return substackBody() }; return MRow(emptyList()) }
            "cancel" -> return MCancel(parseArg(), both = false, back = false)
            "bcancel" -> return MCancel(parseArg(), both = false, back = true)
            "xcancel" -> return MCancel(parseArg(), both = true, back = false)
            "boxed", "fbox", "fcolorbox" -> return MBoxed(parseArg())
            "phantom" -> return MPhantom(parseArg(), keepWidth = true, keepHeight = true)
            "hphantom" -> return MPhantom(parseArg(), keepWidth = true, keepHeight = false)
            "vphantom" -> return MPhantom(parseArg(), keepWidth = false, keepHeight = true)
            "smash" -> return MPhantom(parseArg(), keepWidth = true, keepHeight = false).let { MStyled(parseNothing(it)) }
            "not" -> {
                val body = parseArg()
                if (body is MAtom) NEGATIONS[body.text]?.let { return MAtom(it, Cls.REL) }
                return MNot(body)
            }
            "operatorname", "operatorname*" -> {
                val star = name.endsWith("*") || (pos < src.length && src[pos] == '*').also { if (it) pos++ }
                return MAtom(operatorText(readBraceText()), Cls.OP, limits = if (star) Limits.LIMITS else Limits.NOLIMITS)
            }
            "pmod" -> return MRow(listOf(MSpace(1f), MAtom("(", Cls.OPEN), MAtom("mod", Cls.OP), MSpace(0.28f), parseArg(), MAtom(")", Cls.CLOSE)))
            "pod" -> return MRow(listOf(MSpace(1f), MAtom("(", Cls.OPEN), parseArg(), MAtom(")", Cls.CLOSE)))
            "mod" -> return MRow(listOf(MSpace(1f), MAtom("mod", Cls.OP), MSpace(0.28f)))
            "hspace", "hspace*", "mkern", "kern", "hskip", "mspace", "enskip" -> {
                val raw = if (name == "kern" || name == "hskip" || name == "mkern") readDimensionToken() else readBraceText()
                return MSpace(dimension(raw))
            }
            "textcolor" -> {
                val color = readColor()
                return MStyled(parseArg(), color = color)
            }
            "colorbox" -> { readColor(); return parseArg() }
            "tag", "tag*" -> {
                val label = textFromRaw(readBraceText())
                return MRow(listOf(MSpace(1f), MText("($label)", Variant.ROMAN)))
            }
            "ensuremath" -> return parseArg()
            "hline", "midrule", "toprule", "bottomrule", "Hline" -> { curHlines?.add(curRowIndex); return null }
            "cline", "cmidrule" -> { readBraceText(); return null }
            "multicolumn" -> { readBraceText(); readBraceText(); return parseArg() }
            "limits", "nolimits", "displaylimits" -> return null
            "{" -> return MAtom("{", Cls.OPEN)
            "}" -> return MAtom("}", Cls.CLOSE)
            "|" -> return MAtom("‖", Cls.ORD)
            "$", "%", "&", "#", "_" -> return MAtom(name, Cls.ORD)
            "\\" -> return null
        }
        if (name in IGNORED_COMMANDS) return null
        MathSymbols.binary[name]?.let { return MAtom(it, Cls.BIN) }
        MathSymbols.relation[name]?.let { return MAtom(it, Cls.REL) }
        MathSymbols.opens[name]?.let { return MAtom(it, Cls.OPEN) }
        MathSymbols.closes[name]?.let { return MAtom(it, Cls.CLOSE) }
        MathSymbols.ordinary[name]?.let { return if (name == "colon") MAtom(it, Cls.PUNCT) else MAtom(it, Cls.ORD) }
        return MError("\\$name")
    }

    private fun parseNothing(n: MathNode): MathNode = n

    private fun operatorText(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\') {
                i++
                while (i < raw.length && raw[i].isLetter()) i++
                if (i < raw.length && !raw[i].isLetter() && raw[i - 1] == '\\') i++
                continue
            }
            if (ch != '{' && ch != '}' && ch != '$') sb.append(ch)
            i++
        }
        return sb.toString().trim()
    }

    /** \left ... \middle ... \right. */
    private fun parseDelimited(): MathNode {
        val left = readDelimiter()
        val parts = ArrayList<MathNode>()
        val middles = ArrayList<String>()
        var right = ""
        enter()
        while (true) {
            parts += parseRow()
            when (stop) {
                Stop.RIGHT -> { consume("right"); right = readDelimiter(); break }
                Stop.MIDDLE -> { consume("middle"); middles += readDelimiter() }
                Stop.AMP, Stop.ROWSEP, Stop.ENV_END, Stop.END, Stop.BRACE -> break
            }
        }
        leave()
        return MDelim(left, right, parts, middles)
    }

    private fun readDelimiter(): String {
        skipWs()
        if (pos >= src.length) return ""
        val c = src[pos]
        if (c == '.') { pos++; return "" }
        if (c == '\\') {
            val name = peekCommand() ?: ""
            consume(name)
            return MathSymbols.delimiters["\\$name"] ?: ""
        }
        pos++
        return MathSymbols.delimiters[c.toString()] ?: c.toString()
    }

    // ---------------------------------------------------------------- environments

    private fun parseEnv(): MathNode {
        val env = readBraceText().trim()
        var spec = ""
        if (env in ARRAY_ENVS) {
            skipWs()
            if (pos < src.length && src[pos] == '[') readBracketRaw()
            skipWs()
            if (pos < src.length && src[pos] == '{') spec = readBraceText()
        }
        val savedHlines = curHlines
        val savedIndex = curRowIndex
        curHlines = HashSet()
        curRowIndex = 0
        enter()
        val rows = parseRows(env, null)
        leave()
        val hl = curHlines.orEmpty()
        curHlines = savedHlines
        curRowIndex = savedIndex
        val vlines = HashSet<Int>()
        var col = 0
        for (ch in spec) {
            if (ch == '|') vlines += col else if (ch in "lcrpmb") col++
        }
        return MMatrix(env, rows, spec.filter { it in "lcr" }, hl, vlines)
    }

    private fun substackBody(): MathNode {
        val savedHlines = curHlines
        val savedIndex = curRowIndex
        curHlines = HashSet()
        curRowIndex = 0
        enter()
        val rows = parseRows(null, null, untilBrace = true)
        leave()
        curHlines = savedHlines
        curRowIndex = savedIndex
        return MMatrix("substack", rows, "", emptySet(), emptySet())
    }

    private fun parseRows(env: String?, first: MRow?, untilBrace: Boolean = false): List<List<MathNode>> {
        val rows = ArrayList<List<MathNode>>()
        var cells = ArrayList<MathNode>()
        var cell: MathNode = first ?: parseRow()
        while (true) {
            cells += cell
            when (stop) {
                Stop.AMP -> { pos++; sawAmp = true }
                Stop.ROWSEP -> {
                    pos += 2
                    skipWs()
                    if (pos < src.length && src[pos] == '*') pos++
                    skipWs()
                    if (pos < src.length && src[pos] == '[') readBracketRaw()
                    rows += cells
                    cells = ArrayList()
                    curRowIndex = rows.size
                }
                Stop.ENV_END -> {
                    consume("end")
                    readBraceText()
                    rows += cells
                    break
                }
                Stop.BRACE -> {
                    pos++
                    if (untilBrace) { rows += cells; break }
                }
                Stop.END -> { rows += cells; break }
                Stop.RIGHT -> { consume("right"); readDelimiter() }
                Stop.MIDDLE -> { consume("middle"); readDelimiter() }
            }
            cell = parseRow()
        }
        if (rows.size > 1 && rows.last().all { it is MRow && it.items.isEmpty() }) rows.removeAt(rows.size - 1)
        return rows
    }

    // ---------------------------------------------------------------- raw readers

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peekCommand(): String? {
        if (pos >= src.length || src[pos] != '\\') return null
        val i = pos + 1
        if (i >= src.length) return ""
        val c = src[i]
        if (c.isLetter()) {
            var j = i
            while (j < src.length && src[j].isLetter()) j++
            return src.substring(i, j)
        }
        return if (Character.isHighSurrogate(c) && i + 1 < src.length) src.substring(i, i + 2) else c.toString()
    }

    private fun consume(name: String) {
        pos += 1 + name.length
    }

    /** The raw text of a {balanced} argument, without the outer braces; a single token when there are none. */
    private fun readBraceText(): String {
        skipWs()
        if (pos >= src.length) return ""
        if (src[pos] != '{') {
            if (src[pos] == '\\') {
                val n = peekCommand() ?: ""
                consume(n)
                return "\\$n"
            }
            return src[pos++].toString()
        }
        var depth = 0
        val start = pos + 1
        var i = pos
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') { i += 2; continue }
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) {
                    pos = i + 1
                    return src.substring(start, i)
                }
            }
            i++
        }
        pos = src.length
        return src.substring(start.coerceAtMost(src.length))
    }

    private fun readBracketRaw(): String {
        if (pos >= src.length || src[pos] != '[') return ""
        var depth = 0
        val start = pos + 1
        var i = pos
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') { i += 2; continue }
            if (c == '{') depth++
            else if (c == '}') depth--
            else if (c == ']' && depth <= 0) {
                pos = i + 1
                return src.substring(start, i)
            }
            i++
        }
        pos = src.length
        return src.substring(start.coerceAtMost(src.length))
    }

    /** Optional [math] argument. */
    private fun readBracketMath(): MathNode? {
        skipWs()
        if (pos >= src.length || src[pos] != '[') return null
        val raw = readBracketRaw()
        enter()
        val node = LatexParser(raw, level).parseTop()
        leave()
        return node
    }

    private fun readDimensionToken(): String {
        skipWs()
        val start = pos
        if (pos < src.length && src[pos] == '{') return readBraceText()
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == '-' || src[pos] == '+')) pos++
        while (pos < src.length && src[pos].isLetter()) pos++
        return src.substring(start, pos)
    }

    private fun dimension(raw: String): Float {
        val m = Regex("""(-?\d*\.?\d+)\s*(em|ex|pt|mu|mm|cm|in|px|bp)?""").find(raw.trim()) ?: return 0f
        val v = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) {
            "ex" -> v * 0.43f
            "pt", "px", "bp" -> v / 10f
            "mu" -> v / 18f
            "mm" -> v / 3.5f
            "cm" -> v * 2.85f
            "in" -> v * 7.2f
            else -> v
        }.coerceIn(-5f, 12f)
    }

    private fun readColor(): Int? {
        skipWs()
        var model = ""
        if (pos < src.length && src[pos] == '[') model = readBracketRaw().trim().lowercase()
        val raw = readBraceText().trim()
        return parseColor(model, raw)
    }

    private fun parseColor(model: String, raw: String): Int? {
        if (model == "rgb") {
            val p = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (p.size == 3) return argb(p[0], p[1], p[2])
        }
        val hex = raw.removePrefix("#")
        if ((model == "html" || raw.startsWith("#") || hex.length == 6) && hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return (0xFF000000L or hex.toLong(16)).toInt()
        }
        return MathSymbols.colors[raw.lowercase()]
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Plain text of a \text{...} argument: escapes resolved, macros dropped. */
    private fun textFromRaw(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val n = raw[i + 1]
                    if (n.isLetter()) {
                        var j = i + 1
                        while (j < raw.length && raw[j].isLetter()) j++
                        when (raw.substring(i + 1, j)) {
                            "textbackslash" -> sb.append('\\')
                            "ldots", "dots", "textellipsis" -> sb.append('…')
                            "quad", "qquad" -> sb.append(' ')
                        }
                        i = j
                        if (i < raw.length && raw[i] == ' ') i++
                    } else {
                        when (n) {
                            '$', '%', '&', '_', '#', '{', '}' -> sb.append(n)
                            ' ', ',', ';', ':', '\\' -> sb.append(' ')
                        }
                        i += 2
                    }
                }
                c == '{' || c == '}' || c == '$' -> i++
                c == '~' -> { sb.append(' '); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun enter() {
        if (++level > MAX_DEPTH) throw TooDeep()
    }

    private fun leave() {
        level--
    }
}
