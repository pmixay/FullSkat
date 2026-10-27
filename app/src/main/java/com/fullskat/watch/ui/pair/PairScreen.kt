package com.fullskat.watch.ui.pair

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import com.fullskat.watch.R
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.PairUiState
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.delay

private const val MIN_CODE = 4
private const val MAX_CODE = 8

// Every slot of the screen has a fixed size, so neither a longer status message, a growing code
// nor the busy/done state moves the keys.
private val KeySize = 36.dp
private val KeyGap = 4.dp
private val KeypadHeight = KeySize * 4 + KeyGap * 3

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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(28.dp).padding(horizontal = 36.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = message ?: stringResource(R.string.pair_passcode),
                    color = when (state) {
                        is PairUiState.Failed -> Tokens.error
                        is PairUiState.Success -> Tokens.accent
                        else -> Tokens.userText
                    },
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CodeDots(length = code.length, modifier = Modifier.height(22.dp))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.height(KeypadHeight), contentAlignment = Alignment.Center) {
                Keypad(
                    enabled = !busy && !done,
                    hasCode = code.isNotEmpty(),
                    canSubmit = code.length >= MIN_CODE,
                    onDigit = { if (code.length < MAX_CODE) code += it },
                    onBackspace = { code = code.dropLast(1) },
                    onSubmit = { if (code.length >= MIN_CODE) onSubmit(code) },
                    modifier = Modifier.alpha(if (busy || done) 0.2f else 1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                if (done) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** One dot per entered digit, at least [MIN_CODE] slots; dots are drawn, not glyphs, so the row height never changes. */
@Composable
private fun CodeDots(length: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(maxOf(MIN_CODE, length)) { i ->
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i < length) Tokens.accent else Tokens.surfaceHigh))
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    hasCode: Boolean,
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d -> KeyButton(d.toString(), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBackspace,
                enabled = enabled && hasCode,
                modifier = Modifier.size(KeySize),
                colors = TextButtonDefaults.textButtonColors(contentColor = Tokens.userText),
            ) {
                Icon(painterResource(R.drawable.ic_backspace), contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
            }
            KeyButton("0", enabled) { onDigit('0') }
            TextButton(
                onClick = onSubmit,
                enabled = enabled && canSubmit,
                modifier = Modifier.size(KeySize),
                colors = TextButtonDefaults.filledTextButtonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.pair_connect), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(KeySize),
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 16.sp, style = MaterialTheme.typography.labelMedium)
    }
}
