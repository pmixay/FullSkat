package com.fullskat.watch.ui.pair

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.fullskat.watch.R
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.PairUiState
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.delay

private const val MIN_CODE = 4
private const val MAX_CODE = 8

/**
 * All sizes of the passcode screen as functions of the diameter [d] (dp) of the round display.
 *
 * The block (hint, dots, 3x4 keypad) is centered on the circle and every rectangle of it keeps its
 * corners inside a circle of radius d / 2 - [margin]. Rows near the top and bottom are narrow chords,
 * so the hint is short, the dots are tiny and the two icon keys of the bottom row are smaller.
 * Every slot has a fixed size, so neither a longer message, a growing code nor the busy/done state
 * moves anything: messages and progress are overlaid on the dimmed keypad.
 */
private class PadGeometry(d: Float) {
    val margin = maxOf(10f, 0.055f * d).dp
    val key = (0.152f * d).dp
    val sideKey = (0.84f * 0.152f * d).dp
    val gap = maxOf(2.5f, 0.014f * d).dp
    val gapV = maxOf(2f, 0.014f * d).dp
    val hintW = (0.42f * d).dp
    val hintH = (0.056f * d).dp
    val hintFont = (0.046f * d).dp
    val dot = maxOf(5f, 0.032f * d).dp
    val dotGap = (0.02f * d).dp
    val dotsW = (8 * maxOf(5f, 0.032f * d) + 7 * 0.02f * d).dp
    val keypadW = (3 * 0.152f * d + 2 * maxOf(2.5f, 0.014f * d)).dp
    val keypadH = (4 * 0.152f * d + 3 * maxOf(2.5f, 0.014f * d)).dp
    val digitFont = (0.44f * 0.152f * d).dp
    val keyIcon = (0.5f * 0.152f * d).dp
    val overlayW = (0.70f * d).dp
    val msgFont = (0.05f * d).dp
    val msgLine = (0.058f * d).dp
    val doneIcon = (0.11f * d).dp
    val spinner = (0.12f * d).dp
}

@Composable
private fun Dp.asSp(): TextUnit = with(LocalDensity.current) { this@asSp.toSp() }

@Composable
fun PairScreen(vm: AppViewModel, onPaired: () -> Unit) {
    val state by vm.pairState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        val s = state
        if (s is PairUiState.Success) {
            delay(if (s.firstSetup) 1_800 else 400)
            vm.resetPairState()
            onPaired()
        }
    }

    PasscodeStep(
        state = state,
        onSubmit = { code -> vm.pair(code, Build.MODEL ?: "Wear OS") },
        onRetry = { vm.resetPairState() },
    )
}

@Composable
private fun PasscodeStep(state: PairUiState, onSubmit: (String) -> Unit, onRetry: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val busy = state is PairUiState.Busy
    val done = state is PairUiState.Success
    val failed = state is PairUiState.Failed
    val message: String? = when (val s = state) {
        is PairUiState.Failed -> when (s.reason) {
            PairUiState.Failed.Reason.WRONG -> stringResource(R.string.pair_wrong)
            PairUiState.Failed.Reason.LOCKED -> stringResource(R.string.pair_locked)
            PairUiState.Failed.Reason.UNREACHABLE -> stringResource(R.string.pair_unreachable)
            PairUiState.Failed.Reason.SERVER -> stringResource(R.string.err_server)
        }
        is PairUiState.Success -> if (s.firstSetup) stringResource(R.string.pair_set_first) else stringResource(R.string.done)
        else -> null
    }
    LaunchedEffect(state) {
        if (state is PairUiState.Failed) {
            code = ""
            delay(2_500)
            onRetry()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The window is the full square, the bezel cuts it to the inscribed circle.
        val geo = PadGeometry(maxOf(120f, minOf(maxWidth.value, maxHeight.value)))
        val locked = busy || done || failed
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(geo.hintW, geo.hintH), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.pair_passcode),
                    color = Tokens.userText,
                    fontSize = geo.hintFont.asSp(),
                    lineHeight = geo.hintH.asSp(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(geo.dotsW, geo.gapV + geo.dot + geo.gapV), contentAlignment = Alignment.Center) {
                CodeDots(length = code.length, geo = geo)
            }
            Box(Modifier.size(geo.overlayW, geo.keypadH), contentAlignment = Alignment.Center) {
                Keypad(
                    geo = geo,
                    enabled = !locked,
                    hasCode = code.isNotEmpty(),
                    canSubmit = code.length >= MIN_CODE,
                    onDigit = { if (code.length < MAX_CODE) code += it },
                    onBackspace = { code = code.dropLast(1) },
                    onSubmit = { if (code.length >= MIN_CODE) onSubmit(code) },
                    modifier = Modifier.alpha(if (locked) 0.2f else 1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(geo.spinner), strokeWidth = 3.dp)
                if (message != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (done) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(geo.doneIcon))
                        }
                        Text(
                            text = message,
                            color = if (failed) Tokens.error else Tokens.accent,
                            fontSize = geo.msgFont.asSp(),
                            lineHeight = geo.msgLine.asSp(),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(geo.overlayW),
                        )
                    }
                }
            }
        }
    }
}

/** One dot per entered digit, at least [MIN_CODE] slots; dots are drawn, not glyphs, so the row height never changes. */
@Composable
private fun CodeDots(length: Int, geo: PadGeometry) {
    Row(horizontalArrangement = Arrangement.spacedBy(geo.dotGap), verticalAlignment = Alignment.CenterVertically) {
        repeat(maxOf(MIN_CODE, length)) { i ->
            Box(Modifier.size(geo.dot).clip(CircleShape).background(if (i < length) Tokens.accent else Tokens.surfaceHigh))
        }
    }
}

@Composable
private fun Keypad(
    geo: PadGeometry,
    enabled: Boolean,
    hasCode: Boolean,
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(geo.gap)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(geo.gap)) {
                row.forEach { d -> DigitKey(geo, d.toString(), enabled) { onDigit(d) } }
            }
        }
        // The icon keys are smaller than the digits: the bottom row is the narrowest chord of the circle.
        Row(horizontalArrangement = Arrangement.spacedBy(geo.gap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(geo.key), contentAlignment = Alignment.Center) {
                IconKey(
                    geo = geo,
                    icon = R.drawable.ic_backspace,
                    description = stringResource(R.string.back),
                    enabled = enabled && hasCode,
                    background = Color.Transparent,
                    tint = Tokens.userText,
                    onClick = onBackspace,
                )
            }
            DigitKey(geo, "0", enabled) { onDigit('0') }
            Box(Modifier.size(geo.key), contentAlignment = Alignment.Center) {
                IconKey(
                    geo = geo,
                    icon = R.drawable.ic_check,
                    description = stringResource(R.string.pair_connect),
                    enabled = enabled && canSubmit,
                    background = Tokens.accent,
                    tint = Tokens.onAccent,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun DigitKey(geo: PadGeometry, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(geo.key)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(Tokens.surfaceHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Tokens.assistantText,
            fontSize = geo.digitFont.asSp(),
            lineHeight = (geo.digitFont * 1.2f).asSp(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun IconKey(
    geo: PadGeometry,
    icon: Int,
    description: String,
    enabled: Boolean,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(geo.sideKey)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(geo.keyIcon))
    }
}
