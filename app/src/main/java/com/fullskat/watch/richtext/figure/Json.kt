package com.fullskat.watch.richtext.figure

/**
 * A small JSON reader for figure blocks, in pure Kotlin so the figure code runs in plain JVM tests
 * (android's org.json is a stub there). Objects become [Map], arrays [List], numbers [Double]; it tolerates
 * trailing commas, which a model writing a block by hand sometimes leaves. Nesting is capped.
 */
object Json {
    class Error(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val value = p.value(0)
        p.ws()
        if (p.i != text.length) throw Error("trailing characters at ${p.i}")
        return value
    }

    private const val MAX_DEPTH = 32

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw Error("nested too deep")
            if (i >= s.length) throw Error("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Error("unexpected '$c' at $i")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Error("bad literal at $i")
            i += w.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            while (true) {
                ws()
                if (i < s.length && s[i] == '}') { i++; return m }
                if (i >= s.length || s[i] != '"') throw Error("expected a key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Error("expected ':' at $i")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                throw Error("expected ',' or '}' at $i")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            while (true) {
                ws()
                if (i < s.length && s[i] == ']') { i++; return a }
                a += value(depth + 1)
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return a }
                throw Error("expected ',' or ']' at $i")
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw Error("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e) // \" \\ \/
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw Error("unterminated string")
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw Error("bad number at $start")
        }
    }
}
