package com.fullskat.watch.ui.rich

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import com.fullskat.watch.richtext.RichEnv
import com.fullskat.watch.richtext.code.Highlighter
import com.fullskat.watch.richtext.code.Tok
import com.fullskat.watch.richtext.markdown.Align
import com.fullskat.watch.richtext.markdown.Block
import com.fullskat.watch.richtext.markdown.Break
import com.fullskat.watch.richtext.markdown.Code
import com.fullskat.watch.richtext.markdown.CodeBlock
import com.fullskat.watch.richtext.markdown.DisplayMath
import com.fullskat.watch.richtext.markdown.Heading
import com.fullskat.watch.richtext.markdown.Inline
import com.fullskat.watch.richtext.markdown.ListItem
import com.fullskat.watch.richtext.markdown.Markdown
import com.fullskat.watch.richtext.markdown.Math
import com.fullskat.watch.richtext.markdown.Paragraph
import com.fullskat.watch.richtext.markdown.Quote
import com.fullskat.watch.richtext.markdown.Rule
import com.fullskat.watch.richtext.markdown.Table
import com.fullskat.watch.richtext.markdown.Text
import com.fullskat.watch.richtext.math.MathLayout
import com.fullskat.watch.ui.theme.Tokens

/** Palette for code and tables: dark surfaces below the chat bubble, syntax colours that keep contrast on them. */
private object RichColors {
    val codeBg = Color(0xFF14151A)
    val inlineCodeBg = Color(0xFF2C2D34)
    val codePlain = Color(0xFFE3E3E3)
    val rule = Color(0xFF3F3F44)
}

private fun tokenStyle(tok: Tok): SpanStyle = when (tok) {
    Tok.KEYWORD -> SpanStyle(color = Color(0xFFFFB35C))
    Tok.STRING -> SpanStyle(color = Color(0xFFA5D6A7))
    Tok.COMMENT -> SpanStyle(color = Color(0xFF858B94), fontStyle = FontStyle.Italic)
    Tok.NUMBER -> SpanStyle(color = Color(0xFF82AAFF))
    Tok.TYPE -> SpanStyle(color = Color(0xFFE6C07B))
    Tok.FUNCTION -> SpanStyle(color = Color(0xFF89DDFF))
    Tok.ANNOTATION -> SpanStyle(color = Color(0xFFC792EA))
    Tok.CONSTANT -> SpanStyle(color = Color(0xFFF78C6C))
    Tok.OPERATOR -> SpanStyle(color = Color(0xFFA3A8B0))
    Tok.ATTR -> SpanStyle(color = Color(0xFF80CBC4))
    Tok.TAG -> SpanStyle(color = Color(0xFFF07178))
    Tok.VARIABLE -> SpanStyle(color = Color(0xFF9CDCFE))
    Tok.INSERT -> SpanStyle(color = Color(0xFF9CCC65))
    Tok.DELETE -> SpanStyle(color = Color(0xFFEF9A9A))
}

/**
 * An assistant answer with Markdown, LaTeX math and highlighted code. Plain text (no markup) renders
 * exactly like a normal paragraph, so short voice answers look as before.
 */
@Composable
fun RichText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 21.sp,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val env = RichEnv.get(LocalContext.current)
    val base = MaterialTheme.typography.bodyLarge.copy(color = color, fontSize = fontSize, lineHeight = lineHeight)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (b in blocks) BlockView(b, env, base)
    }
}

@Composable
private fun BlockView(b: Block, env: RichEnv, base: TextStyle) {
    when (b) {
        is Paragraph -> InlineText(b.inlines, env, base)
        is Heading -> {
            val size = when (b.level) { 1 -> 1.12f; 2 -> 1.06f; else -> 1f }
            val c = if (b.level <= 2) Tokens.accent else base.color
            InlineText(
                b.inlines, env,
                base.copy(fontSize = base.fontSize * size, lineHeight = base.lineHeight * size, color = c),
                bold = true,
            )
        }
        is CodeBlock -> CodeBlockView(b, base)
        is DisplayMath -> MathBlock(b.latex, env, base)
        is ListItem -> Row {
            val markerWidth = when {
                b.marker.length > 1 && b.marker[0].isDigit() -> 26.dp
                b.marker == "•" -> 15.dp
                else -> 24.dp // checkboxes
            }
            BasicText(b.marker, Modifier.widthIn(min = markerWidth), style = base)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (inner in b.blocks) BlockView(inner, env, base)
            }
        }
        is Quote -> Column(
            Modifier
                .drawBehind {
                    drawRoundRect(
                        Tokens.accent.copy(alpha = 0.6f),
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                .padding(start = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val dim = base.copy(color = base.color.copy(alpha = 0.78f))
            for (inner in b.blocks) BlockView(inner, env, dim)
        }
        Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(RichColors.rule))
        is Table -> TableView(b, env, base)
    }
}

// ------------------------------------------------------------------ inline text with math placeholders

private class Built(val text: AnnotatedString, val content: Map<String, InlineTextContent>, val descentExcessPx: Float)

@Composable
private fun InlineText(
    inlines: List<Inline>,
    env: RichEnv,
    style: TextStyle,
    bold: Boolean = false,
    textAlign: TextAlign? = null,
) {
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    val built = remember(inlines, fontPx, style.color, bold) { buildInline(inlines, env, style.fontSize, fontPx, density, style.color, bold) }
    // A formula that hangs below the baseline gets the room it needs under every line (all the extra goes below the text).
    var s = if (built.descentExcessPx > 0.5f) {
        style.copy(
            lineHeight = style.lineHeight + with(density) { built.descentExcessPx.toSp() },
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None),
        )
    } else style
    if (textAlign != null) s = s.copy(textAlign = textAlign)
    BasicText(built.text, style = s, inlineContent = built.content)
}

private fun buildInline(
    inlines: List<Inline>,
    env: RichEnv,
    fontSize: TextUnit,
    fontPx: Float,
    density: Density,
    color: Color,
    bold: Boolean,
): Built {
    val content = HashMap<String, InlineTextContent>()
    var mathCount = 0
    var descentExcess = 0f // how far inline formulas hang below the text's own descent (fractions, limits)
    val text = buildAnnotatedString {
        for (i in inlines) {
            when (i) {
                is Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (i.bold || bold) FontWeight.Bold else null,
                        fontStyle = if (i.italic) FontStyle.Italic else null,
                        textDecoration = if (i.strike) TextDecoration.LineThrough else null,
                    ),
                ) { append(i.text) }
                is Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize * 0.88f, background = RichColors.inlineCodeBg),
                ) { append(" ${i.text} ") }
                is Math -> {
                    val id = "m${mathCount++}"
                    val layout = env.layout(i.latex, display = false)
                    descentExcess = maxOf(descentExcess, layout.descent * fontPx - 0.26f * fontPx)
                    content[id] = mathContent(layout, env, i.latex, fontPx, density, color)
                    appendInlineContent(id, i.latex)
                }
                Break -> append("\n")
            }
        }
    }
    return Built(text, content, maxOf(descentExcess, 0f))
}

/** A formula in the middle of a line: the placeholder rests on the text baseline, descenders hang below it. */
private fun mathContent(layout: MathLayout, env: RichEnv, latex: String, fontPx: Float, density: Density, color: Color): InlineTextContent {
    val pad = 0.06f * fontPx
    val ascentPx = maxOf(layout.ascent * fontPx, 0.5f * fontPx)
    val widthPx = layout.width * fontPx + 2f * pad
    val argb = color.toArgb()
    return with(density) {
        InlineTextContent(Placeholder(widthPx.toSp(), ascentPx.toSp(), PlaceholderVerticalAlign.AboveBaseline)) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = latex }
                    .drawBehind {
                        drawIntoCanvas { c -> env.draw(c.nativeCanvas, layout, pad, size.height, fontPx, argb) }
                    },
            )
        }
    }
}

// ------------------------------------------------------------------ display math

private const val MIN_MATH_SCALE = 0.62f

/** A centred display equation: shrunk to fit the width down to 62 %, scrolled sideways beyond that. */
@Composable
private fun MathBlock(latex: String, env: RichEnv, base: TextStyle) {
    val density = LocalDensity.current
    val fontPx = with(density) { base.fontSize.toPx() }
    val layout = remember(latex) { env.layout(latex, display = true) }
    val argb = base.color.toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = with(density) { maxWidth.toPx() }
        val natural = (layout.width + 0.2f) * fontPx // the formula plus its 0.1 em padding on both sides
        val scale = if (natural <= maxPx) 1f else maxOf(maxPx / natural, MIN_MATH_SCALE)
        val px = fontPx * scale
        val pad = 0.1f * px
        val wPx = layout.width * px + 2f * pad
        val hPx = layout.height * px + 2f * pad
        val formula = @Composable {
            Canvas(
                Modifier
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .semantics { contentDescription = latex },
            ) {
                drawIntoCanvas { c -> env.draw(c.nativeCanvas, layout, pad, pad + layout.ascent * px, px, argb) }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (wPx <= maxPx + 0.5f) formula() else Box(Modifier.horizontalScroll(rememberScrollState())) { formula() }
        }
    }
}

// ------------------------------------------------------------------ code

@Composable
private fun CodeBlockView(b: CodeBlock, base: TextStyle) {
    val annotated = remember(b.code, b.language) { highlight(b.code, b.language) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RichColors.codeBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val lang = b.language.trim()
        if (lang.isNotEmpty()) BasicText(lang, style = TextStyle(color = Tokens.label, fontSize = 10.sp))
        BasicText(
            annotated,
            style = TextStyle(
                color = RichColors.codePlain,
                fontFamily = FontFamily.Monospace,
                fontSize = base.fontSize * 0.7f,
                lineHeight = base.fontSize * 0.9f,
            ),
        )
    }
}

private fun highlight(code: String, language: String): AnnotatedString {
    val src = code.replace("\t", "    ").trimEnd('\n')
    val spans = Highlighter.highlight(src, language)
    return buildAnnotatedString {
        append(src)
        for (s in spans) addStyle(tokenStyle(s.tok), s.start, s.end)
    }
}

// ------------------------------------------------------------------ tables

@Composable
private fun TableView(t: Table, env: RichEnv, base: TextStyle) {
    val small = base.copy(fontSize = base.fontSize * 0.8f, lineHeight = base.lineHeight * 0.8f)
    val cols = t.header.size.coerceAtLeast(1)
    val scrolls = cols > 3
    val colWidth = 74.dp
    val rows = listOf(t.header) + t.rows
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RichColors.codeBg)
            .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()),
    ) {
        rows.forEachIndexed { r, row ->
            Row {
                for (c in 0 until cols) {
                    val align = t.align.getOrNull(c) ?: Align.START
                    Box(
                        (if (scrolls) Modifier.width(colWidth) else Modifier.weight(1f)).padding(horizontal = 5.dp, vertical = 4.dp),
                        contentAlignment = when (align) {
                            Align.START -> Alignment.CenterStart
                            Align.CENTER -> Alignment.Center
                            Align.END -> Alignment.CenterEnd
                        },
                    ) {
                        InlineText(
                            row.getOrNull(c).orEmpty(), env, small, bold = r == 0,
                            textAlign = when (align) { Align.START -> TextAlign.Start; Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End },
                        )
                    }
                }
            }
            if (r == 0) Box((if (scrolls) Modifier.width(colWidth * cols) else Modifier.fillMaxWidth()).height(1.dp).background(RichColors.rule))
        }
    }
}
