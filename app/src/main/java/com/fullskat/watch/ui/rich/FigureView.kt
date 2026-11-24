package com.fullskat.watch.ui.rich

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ScreenScaffold
import com.fullskat.watch.R
import com.fullskat.watch.richtext.RichEnv
import com.fullskat.watch.richtext.figure.Figure
import com.fullskat.watch.richtext.figure.FigureScale
import com.fullskat.watch.richtext.figure.FigureScene
import com.fullskat.watch.richtext.figure.View
import com.fullskat.watch.ui.theme.Tokens
import kotlin.math.exp

/** Opens a figure (its ```figure JSON) full screen; null where there is nowhere to open it (previews, tests). */
val LocalFigureOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * A figure inside an answer: title, the drawing, a legend. [animate] plays the drawing animation (for an answer
 * that is arriving now); otherwise the finished figure is shown at once. Tapping opens the full-screen viewer.
 */
@Composable
fun FigureBlock(figure: Figure, json: String, animate: Boolean, modifier: Modifier = Modifier) {
    val env = RichEnv.get(LocalContext.current)
    val density = LocalDensity.current
    val opener = LocalFigureOpener.current
    val fitted = remember(figure) { FigureScale.fit(figure) }
    val clock = remember(figure) { Animatable(0f) }
    var played by remember(figure) { mutableStateOf(!animate) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (figure.title.isNotBlank()) FigureTitle(figure.title, TextAlign.Start)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = wPx * FigureScene.aspect(figure, fitted)
            val scene = remember(figure, wPx, hPx) {
                FigureScene(figure, fitted, wPx, hPx, density.density, env, FigureScene.Mode.INLINE)
            }
            LaunchedEffect(scene) {
                if (!played) {
                    clock.snapTo(0f)
                    clock.animateTo(scene.durationMs, tween(scene.durationMs.toInt(), easing = LinearEasing))
                    played = true
                }
            }
            Canvas(
                Modifier
                    .size(maxWidth, with(density) { hPx.toDp() })
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (opener != null) Modifier.clickable { opener(json) } else Modifier)
                    .semantics { contentDescription = figure.title.ifBlank { "figure" } },
            ) {
                val t = if (played) scene.durationMs else clock.value
                drawIntoCanvas { scene.draw(it.nativeCanvas, t) }
            }
        }
        Legend(figure, centered = false)
    }
}

/** While a hand-written ```figure block is still streaming in. */
@Composable
fun FigurePlaceholder() {
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF17181C)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(stringResource(R.string.figure_drawing), style = TextStyle(color = Tokens.label, fontSize = 12.sp))
    }
}

@Composable
private fun FigureTitle(title: String, align: TextAlign) {
    RichText(title, color = Tokens.userText, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = align)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(figure: Figure, centered: Boolean, modifier: Modifier = Modifier) {
    if (figure.legend.isEmpty()) return
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        for ((color, label) in figure.legend) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(color)))
                RichText(label, color = Tokens.userText, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(start = 3.dp).widthIn(max = 150.dp))
            }
        }
    }
}

/**
 * The figure on the whole round screen. The drawing animation plays when it opens; the bezel or crown zooms,
 * pinch zooms, a drag pans (except from the left edge, which is left to swipe-to-dismiss), a double tap resets
 * the view and draws it again. Curves are evaluated anew for every view, so zooming stays sharp.
 */
@Composable
fun FigureScreen(json: String) {
    val figure = remember(json) { runCatching { Figure.parse(json) }.getOrNull() } ?: return
    val env = RichEnv.get(LocalContext.current)
    val density = LocalDensity.current
    val clock = remember { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<View?>(null) }
    var moved by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // no clock on top: the figure has the whole screen
    ScreenScaffold(timeText = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.background)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val base = remember(figure, wPx, hPx) { FigureScene(figure, initialView(figure), wPx, hPx, density.density, env, FigureScene.Mode.FULL).view }
        val scene = remember(figure, view, base, wPx, hPx) {
            FigureScene(figure, view ?: base, wPx, hPx, density.density, env, FigureScene.Mode.FULL)
        }
        LaunchedEffect(replay) {
            clock.snapTo(0f)
            clock.animateTo(scene.durationMs, tween(scene.durationMs.toInt(), easing = LinearEasing))
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val edge = with(density) { 24.dp.toPx() }
        val zoomable = figure.pie == null
        val latest by rememberUpdatedState(scene)
        Canvas(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e ->
                    if (zoomable) {
                        view = (view ?: base).zoom(exp(e.verticalScrollPixels / 350.0))
                        moved = true
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(zoomable) {
                    if (!zoomable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x < edge) return@awaitEachGesture // swipe-to-dismiss starts at the left edge
                        val plot = latest.plot
                        var v = latest.view // accumulated here: several events can arrive between two frames
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val c = event.calculateCentroid(useCurrent = true)
                                if (zoom != 1f && c != Offset.Unspecified) {
                                    val cx = v.x0 + (c.x - plot.left) / plot.width() * v.w
                                    val cy = v.y0 + (plot.bottom - c.y) / plot.height() * v.h
                                    v = v.zoom(zoom.toDouble(), cx, cy)
                                }
                                v = v.pan(-pan.x / plot.width() * v.w, pan.y / plot.height() * v.h)
                                view = v
                                moved = true
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { view = null; moved = false; replay++ })
                }
                .semantics { contentDescription = figure.title.ifBlank { "figure" } },
        ) {
            val t = if (moved) scene.durationMs else clock.value
            drawIntoCanvas { scene.draw(it.nativeCanvas, t) }
        }
        if (figure.title.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, start = 36.dp, end = 36.dp)) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.background.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 1.dp).align(Alignment.Center)) {
                    FigureTitle(figure.title, TextAlign.Center)
                }
            }
        }
        if (figure.legend.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp, start = 40.dp, end = 40.dp)) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.background.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Legend(figure, centered = true)
                }
            }
        }
    }
    }
}

/** The view the viewer opens with: the figure's own range shown inside the circle, the plane around it visible too. */
private fun initialView(figure: Figure): View {
    val fitted = FigureScale.fit(figure)
    val bleed = figure.pie == null && figure.bars.isEmpty() && (!figure.axes || figure.items.any {
        it is com.fullskat.watch.richtext.figure.FunctionItem || it is com.fullskat.watch.richtext.figure.Parametric || it is com.fullskat.watch.richtext.figure.Polar
    })
    return if (bleed) fitted.zoom(0.74) else fitted
}
