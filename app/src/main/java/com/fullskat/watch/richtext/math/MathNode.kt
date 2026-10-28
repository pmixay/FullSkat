package com.fullskat.watch.richtext.math

/** TeX atom classes; they decide the spacing between neighbours. */
enum class Cls { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER }

/** Math alphabets. DEFAULT is the TeX math italic for Latin and lowercase Greek, upright for everything else. */
enum class Variant { DEFAULT, ROMAN, ITALIC, BOLD, BOLD_ITALIC, SCRIPT, FRAKTUR, DOUBLE_STRUCK, SANS, MONO }

enum class Limits { AUTO, LIMITS, NOLIMITS }

enum class Brace { NONE, OVER, UNDER }

/** Math style numbers: 0 display, 1 text, 2 script, 3 scriptscript. */
const val STYLE_DISPLAY = 0
const val STYLE_TEXT = 1
const val STYLE_SCRIPT = 2
const val STYLE_SCRIPT2 = 3

sealed interface MathNode

data class MRow(val items: List<MathNode>) : MathNode

/**
 * One glyph run. [fixed] glyphs are drawn as they are (symbols, operator names); the others are letters
 * or digits whose alphabet depends on the surrounding \mathbf, \mathbb, ... [large] marks \sum-like operators.
 */
data class MAtom(
    val text: String,
    val cls: Cls,
    val fixed: Boolean = true,
    val large: Boolean = false,
    val limits: Limits = Limits.NOLIMITS,
) : MathNode

data class MScripts(val base: MathNode, val sub: MathNode?, val sup: MathNode?, val limits: Limits = Limits.AUTO) : MathNode

/** [style] forces the style of a \dfrac / \tfrac (STYLE_DISPLAY / STYLE_TEXT), null keeps the surrounding one. */
data class MFrac(
    val num: MathNode,
    val den: MathNode,
    val rule: Boolean = true,
    val style: Int? = null,
    val left: String = "",
    val right: String = "",
) : MathNode

data class MSqrt(val body: MathNode, val index: MathNode?) : MathNode

/** \left ... \middle ... \right. [middles] has one entry less than [parts]. Empty delimiter strings are invisible. */
data class MDelim(val left: String, val right: String, val parts: List<MathNode>, val middles: List<String>) : MathNode

/** \big, \Big, \bigg, \Bigg delimiters: [level] 1..4. */
data class MSized(val delim: String, val level: Int, val cls: Cls) : MathNode

data class MAccent(val kind: String, val body: MathNode) : MathNode

/** \overset / \underset / \stackrel / \overbrace / \underbrace. */
data class MStack(val base: MathNode, val over: MathNode?, val under: MathNode?, val brace: Brace = Brace.NONE) : MathNode

/** \xrightarrow[below]{above} and friends. [glyph] is the arrow character used for the head direction. */
data class MArrow(val glyph: String, val above: MathNode?, val below: MathNode?) : MathNode

/** Matrices, cases, aligned blocks and arrays. [hlines] holds row indexes that have a rule above them (rows.size = bottom rule). */
data class MMatrix(
    val env: String,
    val rows: List<List<MathNode>>,
    val colSpec: String,
    val hlines: Set<Int>,
    val vlines: Set<Int>,
) : MathNode

data class MText(val text: String, val variant: Variant = Variant.ROMAN) : MathNode

data class MSpace(val em: Float) : MathNode

data class MStyled(val body: MathNode, val variant: Variant? = null, val style: Int? = null, val color: Int? = null) : MathNode

data class MPhantom(val body: MathNode, val keepWidth: Boolean, val keepHeight: Boolean) : MathNode

data class MCancel(val body: MathNode, val both: Boolean, val back: Boolean) : MathNode

data class MBoxed(val body: MathNode) : MathNode

data class MClass(val cls: Cls, val body: MathNode) : MathNode

/** \not followed by something: a slash through it. */
data class MNot(val body: MathNode) : MathNode

/** Something the parser does not know; shown literally so the reader still sees what was meant. */
data class MError(val source: String) : MathNode
