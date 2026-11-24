package com.fullskat.core.ui.rich

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
import com.fullskat.core.R
import com.fullskat.core.richtext.RichEnv
import com.fullskat.core.richtext.figure.Figure
import com.fullskat.core.richtext.figure.FigureScale
import com.fullskat.core.richtext.figure.FigureScene
import com.fullskat.core.richtext.figure.View
import com.fullskat.core.ui.theme.Ember
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
        BasicText(stringResource(R.string.figure_drawing), style = TextStyle(color = Ember.label, fontSize = 12.sp))
    }
}

@Composable
fun FigureTitle(title: String, align: TextAlign) {
    RichText(title, color = Ember.userText, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = align)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(figure: Figure, centered: Boolean, modifier: Modifier = Modifier) {
    if (figure.legend.isEmpty()) return
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        for ((color, label) in figure.legend) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(color)))
                RichText(label, color = Ember.userText, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(start = 3.dp).widthIn(max = 150.dp))
            }
        }
    }
}
