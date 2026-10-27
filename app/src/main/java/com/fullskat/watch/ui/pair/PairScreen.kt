package com.fullskat.watch.ui.pair

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text = message ?: stringResource(R.string.pair_passcode),
                color = when (state) {
                    is PairUiState.Failed -> Tokens.error
                    is PairUiState.Success -> Tokens.accent
                    else -> Tokens.userText
                },
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 30.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (code.isEmpty()) "· · · ·" else "•".repeat(code.length),
                color = Tokens.assistantText,
                fontSize = 18.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(4.dp))
            if (busy || state is PairUiState.Success) {
                Box(Modifier.height(112.dp), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    else Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(32.dp))
                }
            } else {
                Keypad(
                    onDigit = { if (code.length < 8) code += it },
                    onBackspace = { code = code.dropLast(1) },
                    onSubmit = { if (code.length >= 4) onSubmit(code) },
                    canSubmit = code.length >= 4,
                )
            }
        }
    }
}

@Composable
private fun Keypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean) {
    val rows = listOf("123", "456", "789")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { d -> KeyButton(d.toString()) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackspace, modifier = Modifier.size(30.dp), colors = TextButtonDefaults.textButtonColors(contentColor = Tokens.userText)) {
                Icon(painterResource(R.drawable.ic_backspace), contentDescription = stringResource(R.string.back), modifier = Modifier.size(16.dp))
            }
            KeyButton("0") { onDigit('0') }
            TextButton(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.size(30.dp),
                colors = TextButtonDefaults.filledTextButtonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.pair_connect), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 14.sp, style = MaterialTheme.typography.labelMedium)
    }
}
