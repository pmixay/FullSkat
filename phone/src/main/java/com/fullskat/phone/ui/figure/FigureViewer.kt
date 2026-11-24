package com.fullskat.phone.ui.figure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fullskat.core.richtext.RichEnv
import com.fullskat.core.richtext.figure.Figure
import com.fullskat.core.richtext.figure.FigureScale
import com.fullskat.core.richtext.figure.FigureScene
import com.fullskat.core.richtext.figure.View
import com.fullskat.core.ui.rich.Legend
import com.fullskat.core.ui.rich.RichText
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R

/**
 * A figure on the whole phone screen. The drawing animation plays when it opens; pinch zooms around the fingers,
 * a drag pans, a double tap resets the view and draws it again. The scene is rebuilt for every view, so curves
 * are sampled anew and stay sharp at any zoom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FigureViewer(json: String, onClose: () -> Unit) {
    val figure = remember(json) { runCatching { Figure.parse(json) }.getOrNull() }
    var replay by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) }
                },
                title = {
                    if (figure != null && figure.title.isNotBlank()) {
                        RichText(figure.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, lineHeight = 23.sp)
                    }
                },
                actions = {
                    if (figure != null) {
                        IconButton(onClick = { replay++ }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.figure_replay)) }
                    }
                },
            )
        },
    ) { padding ->
        if (figure == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.figure_unavailable), color = Ember.label)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            FigureCanvas(figure, replay, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            if (figure.legend.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Legend(figure, centered = true) }
            }
            Text(
                stringResource(if (figure.pie == null) R.string.figure_hint else R.string.figure_hint_pie),
                style = MaterialTheme.typography.labelMedium,
                color = Ember.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FigureCanvas(figure: Figure, replay: Int, modifier: Modifier) {
    val env = RichEnv.get(LocalContext.current)
    val density = LocalDensity.current
    val clock = remember { Animatable(0f) }
    var reset by remember { mutableIntStateOf(0) }
    var view by remember(figure) { mutableStateOf<View?>(null) }
    var moved by remember(figure) { mutableStateOf(false) }
    LaunchedEffect(replay) {
        if (replay > 0) {
            view = null
            moved = false
            reset++
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fitted = remember(figure) { FigureScale.fit(figure) }
        val chart = figure.pie != null || figure.bars.isNotEmpty()
        val wPx = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // Charts keep the proportions they have in the chat; plane figures get more height, up to 1.25 x the width
        // (a taller strip would only show empty plane; pan and zoom reach the rest).
        val hPx = minOf(boxH, wPx * if (chart) FigureScene.aspect(figure, fitted) else MAX_PLANE_ASPECT)
        val base = remember(figure, wPx, hPx) { baseView(figure, fitted, wPx, hPx) }
        val scene = remember(figure, view, base, wPx, hPx) {
            FigureScene(figure, view ?: base, wPx, hPx, density.density, env, FigureScene.Mode.INLINE)
        }
        LaunchedEffect(reset) {
            clock.snapTo(0f)
            clock.animateTo(scene.durationMs, tween(scene.durationMs.toInt(), easing = LinearEasing))
        }
        val zoomable = figure.pie == null
        val latest by rememberUpdatedState(scene)
        Canvas(
            Modifier
                .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                .clip(RoundedCornerShape(12.dp))
                .background(Ember.assistantBubble)
                .pointerInput(zoomable) {
                    if (!zoomable) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
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
                    detectTapGestures(onDoubleTap = {
                        view = null
                        moved = false
                        reset++
                    })
                }
                .semantics { contentDescription = figure.title.ifBlank { "figure" } },
        ) {
            val t = if (moved) scene.durationMs else clock.value
            drawIntoCanvas { scene.draw(it.nativeCanvas, t) }
        }
    }
}

private const val MAX_PLANE_ASPECT = 1.25f

/**
 * The view the viewer opens with. A plane figure keeps the scale it has in the chat (where it is drawn at the
 * inline aspect): on a taller canvas more of the plane shows above and below instead of the curves stretching.
 * Equal-unit figures are fitted by the scene itself; charts get their own box.
 */
internal fun baseView(figure: Figure, fitted: View, widthPx: Float, heightPx: Float): View {
    if (figure.pie != null || figure.bars.isNotEmpty() || figure.equal || widthPx <= 0f) return fitted
    val inlineHeight = widthPx * FigureScene.aspect(figure, fitted)
    val k = heightPx / inlineHeight
    if (k <= 1f) return fitted
    val cy = (fitted.y0 + fitted.y1) / 2
    val half = fitted.h * k / 2
    return View(fitted.x0, fitted.x1, cy - half, cy + half)
}
