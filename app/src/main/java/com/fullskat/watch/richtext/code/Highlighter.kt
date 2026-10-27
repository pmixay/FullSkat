package com.fullskat.watch.richtext.code

enum class Tok { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION, CONSTANT, OPERATOR, ATTR, TAG, VARIABLE, INSERT, DELETE }

/** A coloured range [start, end) of the source; everything not covered is plain text. */
class Span(val start: Int, val end: Int, val tok: Tok)

/**
 * A small lexer-based syntax highlighter. It is deliberately not a full parser: strings, comments, numbers,
 * keywords, type-like names and calls are enough to make code readable on a 1.4 inch screen, and it can
 * never fail on malformed or half-streamed code.
 */
object Highlighter {
    private class Lang(
        keywords: String = "",
        types: String = "",
        constants: String = "",
        builtins: String = "",
        val line: List<String> = emptyList(),
        val block: Pair<String, String>? = null,
        val quotes: String = "\"'",
        val triple: Boolean = false,
        val prefixes: Set<String> = emptySet(),
        val annotations: Boolean = false,
        val preprocessor: Boolean = false,
        val shellVars: Boolean = false,
        val dollarIdent: Boolean = false,
        val keyColon: Boolean = false,
        val bangCall: Boolean = false,
        val capitalIsType: Boolean = true,
        val ignoreCase: Boolean = false,
        val css: Boolean = false,
    ) {
        private fun set(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { if (ignoreCase) it.lowercase() else it }.toHashSet()
        val keywords = set(keywords)
        val types = set(types)
        val constants = set(constants)
        val builtins = set(builtins)
    }

    private val C_STYLE_LINE = listOf("//")
    private val C_BLOCK = "/*" to "*/"

    private val kotlin = Lang(
        keywords = "package import class interface object fun val var if else when for while do return break continue in is as try catch finally throw " +
            "override open abstract sealed data enum inner private public protected internal companion init constructor suspend inline noinline crossinline " +
            "lateinit const typealias by where get set this super operator infix tailrec external annotation value reified vararg out",
        types = "Int String Boolean Long Double Float Unit Any List Map Set Nothing Array Char Byte Short Pair Triple Result Sequence Flow MutableList MutableMap MutableSet",
        constants = "true false null",
        builtins = "println print listOf mapOf setOf mutableListOf mutableMapOf arrayOf require check error lazy launch",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"'", triple = true, annotations = true,
    )
    private val java = Lang(
        keywords = "package import class interface enum extends implements new return if else for while do switch case default break continue try catch finally throw throws " +
            "public private protected static final abstract synchronized volatile transient native this super instanceof void var record sealed permits yield assert",
        types = "int long double float boolean char byte short String Integer Long Double Boolean Object List Map Set ArrayList HashMap Optional Stream",
        constants = "true false null",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"'", triple = true, annotations = true,
    )
    private val csharp = Lang(
        keywords = "using namespace class struct interface enum new return if else for foreach while do switch case default break continue try catch finally throw " +
            "public private protected internal static readonly const abstract virtual override sealed partial async await var this base is as in out ref params get set yield lock delegate event",
        types = "int long double float bool char byte short string object decimal void List Dictionary Task IEnumerable",
        constants = "true false null",
        line = C_STYLE_LINE, block = C_BLOCK, annotations = false,
    )
    private val js = Lang(
        keywords = "function const let var if else for while do return break continue switch case default throw try catch finally new delete typeof instanceof in of " +
            "class extends super this import export from as async await yield static get set interface type enum implements public private protected readonly declare namespace abstract void keyof",
        types = "string number boolean any unknown never object symbol bigint Promise Array Map Set Record Partial Date Error",
        constants = "true false null undefined NaN Infinity",
        builtins = "console Math JSON Object parseInt parseFloat setTimeout fetch require",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"'`", annotations = true,
    )
    private val python = Lang(
        keywords = "def class if elif else for while return import from as with try except finally raise pass break continue lambda yield global nonlocal assert del in is not and or async await match case",
        types = "int str float bool list dict set tuple bytes object type",
        constants = "True False None self cls",
        builtins = "print len range open isinstance enumerate zip map filter sorted sum min max abs input super any all round reversed",
        line = listOf("#"), quotes = "\"'", triple = true, prefixes = setOf("f", "r", "b", "u", "rb", "br", "fr", "rf"), annotations = true, capitalIsType = true,
    )
    private val c = Lang(
        keywords = "auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch " +
            "typedef union unsigned void volatile while class namespace template typename public private protected virtual override new delete using bool this operator constexpr noexcept final explicit friend mutable",
        types = "size_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t string vector map set unique_ptr shared_ptr FILE",
        constants = "true false NULL nullptr",
        builtins = "printf scanf malloc free sizeof memcpy strlen std cout cin endl",
        line = C_STYLE_LINE, block = C_BLOCK, preprocessor = true,
    )
    private val go = Lang(
        keywords = "package import func var const type struct interface map chan if else for range switch case default return break continue go defer select fallthrough goto",
        types = "int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 string bool error byte rune any",
        constants = "true false nil iota",
        builtins = "make new len cap append copy delete panic recover print println fmt",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"'`",
    )
    private val rust = Lang(
        keywords = "fn let mut const static struct enum impl trait pub use mod crate self super if else match for while loop return break continue as in ref move async await dyn where unsafe type extern",
        types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc HashMap Self",
        constants = "true false None Some Ok Err",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"", annotations = true, bangCall = true,
    )
    private val swift = Lang(
        keywords = "import class struct enum protocol extension func var let if else guard switch case default for while repeat return break continue in is as try catch throw throws rethrows " +
            "public private fileprivate internal open static final override init deinit self super where async await actor some any mutating lazy weak unowned",
        types = "Int Double Float String Bool Character Array Dictionary Set Optional Any AnyObject Void",
        constants = "true false nil",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"", triple = true, annotations = true,
    )
    private val ruby = Lang(
        keywords = "def class module if elsif else unless case when while until for in do end return break next redo retry begin rescue ensure raise yield require require_relative include extend attr_accessor attr_reader attr_writer and or not then self super lambda proc",
        constants = "true false nil",
        builtins = "puts print p gets",
        line = listOf("#"), quotes = "\"'",
    )
    private val php = Lang(
        keywords = "function class interface trait extends implements new return if else elseif for foreach while do switch case default break continue try catch finally throw namespace use " +
            "public private protected static final abstract echo print require include const var global as instanceof fn match",
        constants = "true false null TRUE FALSE NULL",
        line = listOf("//", "#"), block = C_BLOCK, dollarIdent = true,
    )
    private val lua = Lang(
        keywords = "function local if then else elseif end for while do repeat until return break in and or not goto",
        constants = "true false nil",
        builtins = "print pairs ipairs require tostring tonumber type table string math",
        line = listOf("--"), quotes = "\"'",
    )
    private val bash = Lang(
        keywords = "if then else elif fi for while until do done case esac in function select return exit export local readonly declare unset source alias set shift trap",
        builtins = "echo cd ls cat grep sed awk find xargs sudo apt git docker curl wget chmod chown mkdir rm cp mv tar ssh scp kill ps pip npm yarn make python python3 node java gradle printf read test",
        constants = "true false",
        line = listOf("#"), quotes = "\"'`", shellVars = true, capitalIsType = false,
    )
    private val sql = Lang(
        keywords = "select from where group by order having join inner left right outer full cross on insert into values update set delete create table alter drop index view primary key foreign " +
            "references not null unique default and or in like between limit offset as distinct union all case when then else end exists is asc desc with returning add column constraint check using over partition",
        types = "int integer bigint smallint serial varchar char text boolean bool date time timestamp datetime decimal numeric float double real json jsonb uuid",
        constants = "true false",
        builtins = "count sum avg min max coalesce now length lower upper substr round cast concat row_number rank",
        line = listOf("--"), block = C_BLOCK, quotes = "\"'`", ignoreCase = true, capitalIsType = false,
    )
    private val json = Lang(constants = "true false null", quotes = "\"", capitalIsType = false)
    private val yaml = Lang(constants = "true false null yes no on off", line = listOf("#"), quotes = "\"'", keyColon = true, capitalIsType = false)
    private val toml = Lang(constants = "true false", line = listOf("#"), quotes = "\"'", keyColon = true, capitalIsType = false)
    private val css = Lang(line = emptyList(), block = C_BLOCK, quotes = "\"'", css = true, capitalIsType = false, annotations = true)
    private val generic = Lang(
        keywords = "if else for while return class def fun function var let val const import from export public private static new this try catch throw switch case break continue in is as",
        constants = "true false null nil none True False None",
        line = C_STYLE_LINE, block = C_BLOCK, quotes = "\"",
    )

    private val languages: Map<String, Lang> = buildMap {
        for (n in listOf("kotlin", "kt", "kts")) put(n, kotlin)
        for (n in listOf("java", "groovy", "scala", "dart")) put(n, java)
        for (n in listOf("csharp", "cs", "c#")) put(n, csharp)
        for (n in listOf("javascript", "js", "jsx", "typescript", "ts", "tsx", "mjs", "node", "json5")) put(n, js)
        for (n in listOf("python", "py", "python3", "py3")) put(n, python)
        for (n in listOf("c", "cpp", "c++", "cc", "h", "hpp", "cxx", "objc", "objective-c")) put(n, c)
        for (n in listOf("go", "golang")) put(n, go)
        for (n in listOf("rust", "rs")) put(n, rust)
        put("swift", swift)
        for (n in listOf("ruby", "rb")) put(n, ruby)
        put("php", php)
        put("lua", lua)
        for (n in listOf("bash", "sh", "shell", "zsh", "console", "terminal", "fish")) put(n, bash)
        for (n in listOf("sql", "mysql", "postgres", "postgresql", "sqlite", "plsql")) put(n, sql)
        put("json", json)
        for (n in listOf("yaml", "yml")) put(n, yaml)
        for (n in listOf("toml", "ini", "conf", "properties")) put(n, toml)
        for (n in listOf("css", "scss", "less")) put(n, css)
    }

    private val PLAIN_LANGS = setOf("text", "txt", "plain", "plaintext", "output", "log", "none", "markdown", "md", "csv", "math", "latex", "tex")

    fun highlight(code: String, language: String): List<Span> {
        val lang = language.trim().lowercase().substringBefore(' ').substringBefore('{')
        if (lang in PLAIN_LANGS) return emptyList()
        return when (lang) {
            "diff", "patch" -> diff(code)
            "html", "xml", "svg", "xhtml", "vue", "htm" -> markup(code)
            else -> lex(code, languages[lang] ?: generic)
        }
    }

    // ---------------------------------------------------------------- diff and markup

    private fun diff(code: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < code.length) {
            val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
            val tok = when {
                code.startsWith("@@", i) -> Tok.ANNOTATION
                code.startsWith("+++", i) || code.startsWith("---", i) -> Tok.KEYWORD
                code[i] == '+' -> Tok.INSERT
                code[i] == '-' -> Tok.DELETE
                else -> null
            }
            if (tok != null && end > i) out += Span(i, end, tok)
            i = end + 1
        }
        return out
    }

    private fun markup(code: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        val n = code.length
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                val e = code.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                out += Span(i, e, Tok.COMMENT); i = e; continue
            }
            if (code[i] == '<' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '/' || code[i + 1] == '!' || code[i + 1] == '?')) {
                var j = i + 1
                if (code[j] == '/' || code[j] == '!' || code[j] == '?') j++
                val nameStart = j
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == ':' || code[j] == '.')) j++
                out += Span(i, j, Tok.TAG)
                if (nameStart == j) { i = j; continue }
                // attributes up to '>'
                while (j < n && code[j] != '>') {
                    val ch = code[j]
                    if (ch == '"' || ch == '\'') {
                        val e = code.indexOf(ch, j + 1).let { if (it < 0) n else it + 1 }
                        out += Span(j, e, Tok.STRING); j = e
                    } else if (ch.isLetter() || ch == '@' || ch == ':') {
                        val s = j
                        while (j < n && (code[j].isLetterOrDigit() || code[j] in "-_:.@")) j++
                        out += Span(s, j, Tok.ATTR)
                    } else j++
                }
                if (j < n) { out += Span(j - if (code[j - 1] == '/') 1 else 0, j + 1, Tok.TAG); j++ }
                i = j; continue
            }
            if (code[i] == '&') {
                val e = code.indexOf(';', i).let { if (it in i + 1..i + 8) it + 1 else -1 }
                if (e > 0) { out += Span(i, e, Tok.CONSTANT); i = e; continue }
            }
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- generic lexer

    private fun isIdentStart(ch: Char) = ch.isLetter() || ch == '_'
    private fun isIdentPart(ch: Char) = ch.isLetterOrDigit() || ch == '_'
    private const val OPERATOR_CHARS = "+-*/%=<>!&|^~?:"

    private fun lex(code: String, lang: Lang): List<Span> {
        val out = ArrayList<Span>()
        val n = code.length
        var i = 0
        var lineStart = true
        fun nextNonSpace(from: Int): Char {
            var k = from
            while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
            return if (k < n) code[k] else '\u0000'
        }
        while (i < n) {
            val ch = code[i]
            if (ch == '\n') { lineStart = true; i++; continue }
            if (ch == ' ' || ch == '\t' || ch == '\r') { i++; continue }
            val atLineStart = lineStart
            lineStart = false

            // comments
            var handled = false
            for (starter in lang.line) {
                if (code.startsWith(starter, i)) {
                    val e = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Span(i, e, Tok.COMMENT); i = e; handled = true; break
                }
            }
            if (handled) continue
            val block = lang.block
            if (block != null && code.startsWith(block.first, i)) {
                val e = code.indexOf(block.second, i + block.first.length).let { if (it < 0) n else it + block.second.length }
                out += Span(i, e, Tok.COMMENT); i = e; continue
            }

            // C preprocessor directives
            if (lang.preprocessor && ch == '#' && atLineStart) {
                var j = i + 1
                while (j < n && code[j] == ' ') j++
                while (j < n && code[j].isLetter()) j++
                out += Span(i, j, Tok.ANNOTATION); i = j; continue
            }

            // strings
            if (lang.quotes.indexOf(ch) >= 0) {
                i = string(code, i, i, ch, lang, out); continue
            }

            // numbers
            if (ch.isDigit() || (ch == '.' && i + 1 < n && code[i + 1].isDigit() && (i == 0 || !isIdentPart(code[i - 1])))) {
                var j = i
                if (ch == '0' && j + 1 < n && (code[j + 1] == 'x' || code[j + 1] == 'X' || code[j + 1] == 'b' || code[j + 1] == 'B')) {
                    j += 2
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                } else {
                    while (j < n && (code[j].isDigit() || code[j] == '_')) j++
                    if (j < n && code[j] == '.' && j + 1 < n && code[j + 1].isDigit()) {
                        j++
                        while (j < n && (code[j].isDigit() || code[j] == '_')) j++
                    }
                    if (j < n && (code[j] == 'e' || code[j] == 'E')) {
                        var k = j + 1
                        if (k < n && (code[k] == '+' || code[k] == '-')) k++
                        if (k < n && code[k].isDigit()) {
                            j = k
                            while (j < n && code[j].isDigit()) j++
                        }
                    }
                    val numEnd = j
                    while (j < n && code[j].isLetter() && j - numEnd < 3) j++ // suffixes: f, L, ul, px, em
                    if (lang.css) while (j < n && (code[j].isLetter() || code[j] == '%')) j++
                }
                out += Span(i, j, Tok.NUMBER); i = j; continue
            }

            // annotations / decorators
            if (lang.annotations && ch == '@' && i + 1 < n && isIdentStart(code[i + 1])) {
                var j = i + 1
                while (j < n && (isIdentPart(code[j]) || code[j] == '.')) j++
                out += Span(i, j, Tok.ANNOTATION); i = j; continue
            }

            // shell / php variables
            if ((lang.shellVars || lang.dollarIdent) && ch == '$') {
                var j = i + 1
                if (j < n && code[j] == '{') {
                    val e = code.indexOf('}', j).let { if (it < 0) n else it + 1 }
                    out += Span(i, e, Tok.VARIABLE); i = e; continue
                }
                while (j < n && (isIdentPart(code[j]) || (lang.shellVars && code[j] in "?#@*!$"))) j++
                if (j > i + 1) { out += Span(i, j, Tok.VARIABLE); i = j; continue }
            }

            // css selectors: .class and #id
            if (lang.css && (ch == '.' || ch == '#') && i + 1 < n && (isIdentStart(code[i + 1]) || code[i + 1].isDigit() || code[i + 1] == '-')) {
                var j = i + 1
                while (j < n && (isIdentPart(code[j]) || code[j] == '-')) j++
                val hex = ch == '#' && (j - i - 1) in setOf(3, 4, 6, 8) && code.substring(i + 1, j).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                out += Span(i, j, if (hex) Tok.NUMBER else Tok.TYPE); i = j; continue
            }

            // identifiers and keywords
            if (isIdentStart(ch) || (lang.css && ch == '-' && i + 1 < n && isIdentStart(code[i + 1]))) {
                var j = i + 1
                while (j < n && (isIdentPart(code[j]) || (lang.css && code[j] == '-'))) j++
                val word = code.substring(i, j)
                val key = if (lang.ignoreCase) word.lowercase() else word
                if (word in lang.prefixes && j < n && (code[j] == '"' || code[j] == '\'')) {
                    i = string(code, i, j, code[j], lang, out); continue
                }
                val after = nextNonSpace(j)
                val tok: Tok? = when {
                    key in lang.keywords -> Tok.KEYWORD
                    key in lang.constants -> Tok.CONSTANT
                    key in lang.types -> Tok.TYPE
                    key in lang.builtins -> Tok.FUNCTION
                    lang.keyColon && after == ':' && j < n && (code[j] == ':' || nextNonSpace(j) == ':') -> Tok.ATTR
                    lang.css && after == ':' -> Tok.ATTR
                    after == '(' -> Tok.FUNCTION
                    lang.bangCall && j < n && code[j] == '!' -> Tok.FUNCTION
                    lang.capitalIsType && word.length > 1 && word[0].isUpperCase() && !word.all { it.isUpperCase() || it.isDigit() || it == '_' } -> Tok.TYPE
                    lang.capitalIsType && word.length > 2 && word.all { it.isUpperCase() || it.isDigit() || it == '_' } -> Tok.CONSTANT
                    else -> null
                }
                if (tok != null) out += Span(i, j, tok)
                i = j; continue
            }

            // operators
            if (OPERATOR_CHARS.indexOf(ch) >= 0) {
                var j = i + 1
                while (j < n && OPERATOR_CHARS.indexOf(code[j]) >= 0) j++
                out += Span(i, j, Tok.OPERATOR); i = j; continue
            }
            i++
        }
        // JSON / YAML style keys: a string directly followed by a colon is a key
        if (lang === json || lang === yaml || lang === toml) {
            for (k in out.indices) {
                val s = out[k]
                if (s.tok == Tok.STRING) {
                    var e = s.end
                    while (e < n && (code[e] == ' ' || code[e] == '\t')) e++
                    if (e < n && code[e] == ':') out[k] = Span(s.start, s.end, Tok.ATTR)
                }
            }
        }
        return out
    }

    /** Scans a string literal starting at the quote at [q] (the span itself begins at [start]); returns the index after it. */
    private fun string(code: String, start: Int, q: Int, quote: Char, lang: Lang, out: MutableList<Span>): Int {
        val n = code.length
        var j = q + 1
        if (lang.triple && code.startsWith("$quote$quote$quote", q)) {
            val closer = "$quote$quote$quote"
            val e = code.indexOf(closer, q + 3).let { if (it < 0) n else it + 3 }
            out += Span(start, e, Tok.STRING)
            return e
        }
        val multiline = quote == '`'
        while (j < n) {
            val c = code[j]
            if (c == '\\' && j + 1 < n) { j += 2; continue }
            if (c == quote) { j++; break }
            if (c == '\n' && !multiline) break
            j++
        }
        out += Span(start, j.coerceAtMost(n), Tok.STRING)
        return j.coerceAtMost(n)
    }
}
