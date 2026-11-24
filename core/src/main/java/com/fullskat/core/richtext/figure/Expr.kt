package com.fullskat.core.richtext.figure

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** A compiled expression of one variable. Domain errors give NaN (or ±Infinity), never an exception. */
fun interface Expr {
    fun eval(v: Double): Double
}

class ExprError(message: String) : IllegalArgumentException(message)

/**
 * Parser for the expressions the `draw` tool sends (keep in sync with server/fullskat_server/figures.py):
 * numbers, one variable (x, or t/theta/θ for curves), pi/π, e, tau, + - * / % ^ (also **), unary minus
 * (-x^2 = -(x^2)), implicit multiplication (2x, 3(x+1), x sin x), |x|, comparisons (1 or 0) and functions,
 * with or without parentheses (sin x, sin 2x, sin^2(x)). Compiled to closures once, then evaluated per sample.
 */
object ExprParser {
    private fun f(body: (Double) -> Double): (Double) -> Double = body

    private val ONE_ARG: Map<String, (Double) -> Double> = mapOf(
        "sin" to f { sin(it) }, "cos" to f { cos(it) }, "tan" to f { tan(it) },
        "cot" to f { 1.0 / tan(it) }, "sec" to f { 1.0 / cos(it) }, "csc" to f { 1.0 / sin(it) },
        "asin" to f { asin(it) }, "acos" to f { acos(it) }, "atan" to f { atan(it) },
        "arcsin" to f { asin(it) }, "arccos" to f { acos(it) }, "arctan" to f { atan(it) }, "acot" to f { atan(1.0 / it) },
        "sinh" to f { sinh(it) }, "cosh" to f { cosh(it) }, "tanh" to f { tanh(it) },
        "asinh" to f { asinh(it) }, "acosh" to f { acosh(it) }, "atanh" to f { atanh(it) },
        "exp" to f { exp(it) }, "ln" to f { ln(it) }, "log10" to f { log10(it) }, "lg" to f { log10(it) }, "log2" to f { log2(it) },
        "sqrt" to f { sqrt(it) }, "cbrt" to f { cbrt(it) }, "abs" to f { abs(it) }, "sign" to f { sign(it) }, "sgn" to f { sign(it) },
        "floor" to f { floor(it) }, "ceil" to f { ceil(it) }, "round" to f { floor(it + 0.5) },
    )
    private val MULTI = setOf("log", "min", "max", "pow", "mod", "hypot", "atan2", "if")
    private val CONSTANTS = mapOf("pi" to Math.PI, "π" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI, "τ" to 2 * Math.PI, "inf" to Double.POSITIVE_INFINITY)
    val X = setOf("x")
    val T = setOf("t", "theta", "θ")

    fun parse(source: String, variables: Set<String> = X): Expr {
        val p = P(tokenize(source), variables)
        val e = p.expr()
        if (!p.atEnd()) throw ExprError("unexpected '${p.peek()?.text}'")
        return e
    }

    fun isFunction(name: String) = name in ONE_ARG || name in MULTI

    // ---------------------------------------------------------------- tokens

    private enum class K { NUM, ID, OP, END }

    private class Tok(val k: K, val text: String, val num: Double = 0.0)

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                        val j = if (i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-')) i + 2 else i + 1
                        if (j < s.length && s[j].isDigit()) {
                            i = j
                            while (i < s.length && s[i].isDigit()) i++
                        }
                    }
                    val text = s.substring(start, i)
                    out += Tok(K.NUM, text, text.toDoubleOrNull() ?: throw ExprError("bad number '$text'"))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    i++
                    // Greek letters stand alone (2πx); latin names may carry digits (log10, x2 is not a name).
                    if (c in 'a'..'z' || c in 'A'..'Z' || c == '_') {
                        while (i < s.length && (s[i] in 'a'..'z' || s[i] in 'A'..'Z' || s[i].isDigit() || s[i] == '_')) i++
                    }
                    out += Tok(K.ID, s.substring(start, i).lowercase())
                }
                else -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    when (two) {
                        "**", "<=", ">=", "==", "!=" -> { out += Tok(K.OP, two); i += 2 }
                        else -> {
                            if (c !in "+-*/%^(),|<>·×÷−") throw ExprError("unexpected '$c'")
                            out += Tok(K.OP, when (c) { '·', '×' -> "*"; '÷' -> "/"; '−' -> "-"; else -> c.toString() })
                            i++
                        }
                    }
                }
            }
        }
        out += Tok(K.END, "")
        return out
    }

    // ---------------------------------------------------------------- grammar

    private class P(val toks: List<Tok>, val vars: Set<String>) {
        var i = 0
        var absDepth = 0
        var depth = 0

        fun peek(): Tok? = toks.getOrNull(i)
        fun atEnd() = toks[i].k == K.END
        private fun isOp(t: String) = toks[i].k == K.OP && toks[i].text == t
        private fun take(t: String): Boolean = if (isOp(t)) { i++; true } else false
        private fun expect(t: String) { if (!take(t)) throw ExprError("expected '$t'") }

        fun expr(): Expr {
            if (++depth > 64) throw ExprError("expression nested too deep")
            val e = compare()
            depth--
            return e
        }

        private fun compare(): Expr {
            val a = additive()
            val op = toks[i].text.takeIf { toks[i].k == K.OP && it in setOf("<", ">", "<=", ">=", "==", "!=") } ?: return a
            i++
            val b = additive()
            return when (op) {
                "<" -> Expr { v -> if (a.eval(v) < b.eval(v)) 1.0 else 0.0 }
                ">" -> Expr { v -> if (a.eval(v) > b.eval(v)) 1.0 else 0.0 }
                "<=" -> Expr { v -> if (a.eval(v) <= b.eval(v)) 1.0 else 0.0 }
                ">=" -> Expr { v -> if (a.eval(v) >= b.eval(v)) 1.0 else 0.0 }
                "==" -> Expr { v -> if (abs(a.eval(v) - b.eval(v)) < 1e-9) 1.0 else 0.0 }
                else -> Expr { v -> if (abs(a.eval(v) - b.eval(v)) >= 1e-9) 1.0 else 0.0 }
            }
        }

        private fun additive(): Expr {
            var a = term()
            while (true) {
                a = when {
                    take("+") -> { val l = a; val r = term(); Expr { v -> l.eval(v) + r.eval(v) } }
                    take("-") -> { val l = a; val r = term(); Expr { v -> l.eval(v) - r.eval(v) } }
                    else -> return a
                }
            }
        }

        /** Can the current token start a factor of an implicit product ("2x", "x(x+1)", "2|x|")? */
        private fun startsFactor(): Boolean {
            val t = toks[i]
            return t.k == K.NUM || t.k == K.ID || (t.k == K.OP && (t.text == "(" || (t.text == "|" && absDepth == 0)))
        }

        private fun term(): Expr {
            var a = unary()
            while (true) {
                a = when {
                    take("*") -> { val l = a; val r = unary(); Expr { v -> l.eval(v) * r.eval(v) } }
                    take("/") -> { val l = a; val r = unary(); Expr { v -> l.eval(v) / r.eval(v) } }
                    take("%") -> { val l = a; val r = unary(); Expr { v -> floorMod(l.eval(v), r.eval(v)) } }
                    startsFactor() -> { val l = a; val r = power(); Expr { v -> l.eval(v) * r.eval(v) } }
                    else -> return a
                }
            }
        }

        private fun unary(): Expr = when {
            take("-") -> { val a = unary(); Expr { v -> -a.eval(v) } }
            take("+") -> unary()
            else -> power()
        }

        private fun power(): Expr {
            val base = primary()
            if (take("^") || take("**")) {
                val ex = unary() // right associative: 2^3^2 = 2^9, and 2^-1 works
                return Expr { v -> base.eval(v).pow(ex.eval(v)) }
            }
            return base
        }

        private fun primary(): Expr {
            val t = toks[i]
            when (t.k) {
                K.NUM -> { i++; val n = t.num; return Expr { n } }
                K.ID -> { i++; return identifier(t.text) }
                K.OP -> when (t.text) {
                    "(" -> { i++; val e = expr(); expect(")"); return e }
                    "|" -> {
                        i++
                        absDepth++
                        val e = expr()
                        absDepth--
                        expect("|")
                        return Expr { v -> abs(e.eval(v)) }
                    }
                }
                K.END -> throw ExprError("unexpected end of expression")
            }
            throw ExprError("unexpected '${t.text}'")
        }

        private fun identifier(name: String): Expr {
            if (name in vars) return Expr { v -> v }
            CONSTANTS[name]?.let { c -> return Expr { c } }
            if (name in ONE_ARG || name in MULTI) return call(name)
            throw ExprError("unknown name '$name'")
        }

        private fun call(name: String): Expr {
            // sin^2(x) = (sin x)^2
            var exponent: Expr? = null
            if (isOp("^") && toks[i + 1].k == K.NUM && toks.getOrNull(i + 2)?.let { it.k == K.OP && it.text == "(" } == true) {
                i++
                val n = toks[i++].num
                exponent = Expr { n }
            }
            val args: List<Expr> = if (take("(")) {
                val list = arrayListOf(expr())
                while (take(",")) list += expr()
                expect(")")
                list
            } else {
                if (name in MULTI) throw ExprError("$name needs parentheses")
                listOf(bareArgument())
            }
            val f = apply(name, args)
            val e = exponent ?: return f
            return Expr { v -> f.eval(v).pow(e.eval(v)) }
        }

        /** "sin 2x" = sin(2x), "sin x cos x" = sin(x)·cos(x): an implicit product that stops before the next function. */
        private fun bareArgument(): Expr {
            var a = if (isOp("-")) { i++; val u = power(); Expr { v -> -u.eval(v) } } else power()
            while (startsFactor() && !(toks[i].k == K.ID && isFunction(toks[i].text))) {
                val l = a
                val r = power()
                a = Expr { v -> l.eval(v) * r.eval(v) }
            }
            return a
        }

        private fun apply(name: String, args: List<Expr>): Expr {
            fun need(n: Int) { if (args.size != n) throw ExprError("$name takes $n argument${if (n == 1) "" else "s"}") }
            ONE_ARG[name]?.let { fn ->
                need(1)
                val a = args[0]
                return Expr { v -> fn(a.eval(v)) }
            }
            return when (name) {
                "log" -> when (args.size) {
                    1 -> { val a = args[0]; Expr { v -> ln(a.eval(v)) } }
                    2 -> { val a = args[0]; val b = args[1]; Expr { v -> ln(a.eval(v)) / ln(b.eval(v)) } }
                    else -> throw ExprError("log takes 1 or 2 arguments")
                }
                "min" -> Expr { v -> args.minOf { it.eval(v) } }
                "max" -> Expr { v -> args.maxOf { it.eval(v) } }
                "pow" -> { need(2); val a = args[0]; val b = args[1]; Expr { v -> a.eval(v).pow(b.eval(v)) } }
                "mod" -> { need(2); val a = args[0]; val b = args[1]; Expr { v -> floorMod(a.eval(v), b.eval(v)) } }
                "hypot" -> { need(2); val a = args[0]; val b = args[1]; Expr { v -> hypot(a.eval(v), b.eval(v)) } }
                "atan2" -> { need(2); val a = args[0]; val b = args[1]; Expr { v -> atan2(a.eval(v), b.eval(v)) } }
                "if" -> {
                    need(3)
                    val c = args[0]; val a = args[1]; val b = args[2]
                    Expr { v -> val cv = c.eval(v); if (!cv.isNaN() && cv != 0.0) a.eval(v) else b.eval(v) }
                }
                else -> throw ExprError("unknown function '$name'")
            }
        }
    }

    private fun floorMod(a: Double, b: Double): Double = a - b * floor(a / b)
}
