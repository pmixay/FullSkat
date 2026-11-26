package com.fullskat.watch.ui.link

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.fullskat.core.account.LinkState
import com.fullskat.watch.R
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * The watch's only setup: it shows a 6-character code that a signed-in phone enters (FullSkat on the phone →
 * Link a watch). The code changes every 30 seconds; a ring around the bezel runs down with it. Once the phone
 * has entered the code the watch is part of that account and its dialogs sync both ways.
 */
@Composable
fun LinkScreen(vm: AppViewModel, onLinked: () -> Unit) {
    val state by vm.link.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.startLinking()
        onDispose { vm.stopLinking() }
    }
    LaunchedEffect(state) {
        if (state is LinkState.Linked) {
            delay(900)
            onLinked()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val code = state as? LinkState.Code
    LaunchedEffect(code) {
        // The ring moves only while a code is on screen; the screen stays on during setup anyway.
        while (code != null) withFrameMillis { now = System.currentTimeMillis() }
    }
    val left = code?.let { ((it.receivedAt + it.expiresIn * 1000L - now) / (it.period * 1000f)).coerceIn(0f, 1f) } ?: 0f
    val seconds = code?.let { ((it.receivedAt + it.expiresIn * 1000L - now + 999) / 1000).coerceAtLeast(0L).toInt() } ?: 0
    LinkContent(state, left, seconds)
}

/** The link screen for a given [state]; [left] is the share of the code's 30 seconds still to go. */
@Composable
fun LinkContent(state: LinkState?, left: Float, secondsLeft: Int) {
    ScreenScaffold(timeText = {}) {
        Box(Modifier.fillMaxSize().background(Tokens.background), contentAlignment = Alignment.Center) {
            val code = state as? LinkState.Code
            val linked = state is LinkState.Linked
            CountdownRing(if (linked) 1f else left, active = code != null || linked)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp)) {
                Text(
                    stringResource(if (linked) R.string.link_done else R.string.link_title),
                    color = if (linked) Tokens.accent else Tokens.userText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    linked -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(40.dp))
                    code != null -> CodeText(code.code)
                    else -> CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        linked -> ""
                        state is LinkState.Offline -> stringResource(R.string.link_offline)
                        else -> stringResource(R.string.link_hint)
                    },
                    color = Tokens.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                )
                if (code != null) {
                    Text(stringResource(R.string.duration_s, secondsLeft), color = Tokens.label, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** "K7Q 2ZP": two groups of three, monospace so a new code does not shift; it fades over when it changes. */
@Composable
private fun CodeText(code: String) {
    AnimatedContent(targetState = code, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "code") { c ->
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = c.toList().joinToString(" ") },
        ) {
            Text(c.take(3), color = Tokens.onUserBubble, fontSize = 31.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(10.dp))
            Text(c.drop(3), color = Tokens.onUserBubble, fontSize = 31.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

/** A ring hugging the bezel: the amber arc is the time left of the current code, running down clockwise from the top. */
@Composable
private fun CountdownRing(left: Float, active: Boolean) {
    val accent = Tokens.accent
    val track = Tokens.surfaceHigh
    Canvas(Modifier.fillMaxSize()) {
        val w = 5.dp.toPx()
        val inset = w / 2 + 2.dp.toPx()
        val d = size.minDimension - 2 * inset
        val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(width = w))
        if (!active || left <= 0f) return@Canvas
        val sweep = 360f * left
        drawArc(accent.copy(alpha = if (left < 0.17f) 0.6f else 1f), -90f + (360f - sweep), sweep, false, topLeft, Size(d, d), style = Stroke(width = w, cap = StrokeCap.Round))
        // a bright head where the time runs out from
        val a = Math.toRadians((-90.0 + 360.0 - sweep))
        val r = d / 2
        drawCircle(accent, radius = w * 0.9f, center = Offset(topLeft.x + r + (r * cos(a)).toFloat(), topLeft.y + r + (r * sin(a)).toFloat()))
    }
}
