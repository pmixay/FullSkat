package com.fullskat.watch.ui.figure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ScreenScaffold
import com.fullskat.core.richtext.RichEnv
import com.fullskat.core.richtext.figure.Figure
import com.fullskat.core.richtext.figure.FigureScale
import com.fullskat.core.richtext.figure.FigureScene
import com.fullskat.core.richtext.figure.Parametric
import com.fullskat.core.richtext.figure.Polar
import com.fullskat.core.richtext.figure.FunctionItem
import com.fullskat.core.richtext.figure.View
import com.fullskat.core.ui.rich.FigureTitle
import com.fullskat.core.ui.rich.Legend
import com.fullskat.watch.ui.theme.Tokens
import kotlin.math.exp

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
/** The view the viewer opens with (shared with the phone through FigureScene.initialView). */
private fun initialView(figure: Figure): View {
    val fitted = FigureScale.fit(figure)
    val bleed = figure.pie == null && figure.bars.isEmpty() && (!figure.axes || figure.items.any {
        it is FunctionItem || it is Parametric || it is Polar
    })
    return if (bleed) fitted.zoom(0.74) else fitted
}
