package com.fullskat.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fullskat.core.account.AccountRepository
import com.fullskat.core.net.Device
import com.fullskat.core.net.DeviceKind
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.ui.durationText

@Composable
fun LinkWatchScreen(vm: LinkViewModel, onBack: () -> Unit) {
    LinkWatchContent(vm.code, vm.state, onCode = vm::onCode, onSubmit = vm::claim, onAgain = vm::again, onBack = onBack)
}

/** Six boxes for the code the watch shows; the sixth character sends it. Success shows the device that joined. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkWatchContent(code: String, state: LinkState, onCode: (String) -> Unit, onSubmit: () -> Unit, onAgain: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_watch)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state is LinkState.Linked) {
                LinkedView(state.device, onDone = onBack, onAgain = onAgain)
                return@Column
            }
            Box(Modifier.size(84.dp).background(Ember.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.link_instructions),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            val error = state is LinkState.WrongCode || state is LinkState.Locked || state is LinkState.Offline || state is LinkState.Failed
            CodeField(code, onCode, onSubmit, enabled = state != LinkState.Claiming, error = error)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    LinkState.Claiming -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ember.accent)
                        Text(stringResource(R.string.link_claiming), color = Ember.label)
                    }
                    LinkState.WrongCode -> ErrorLine(stringResource(R.string.link_wrong))
                    is LinkState.Locked -> ErrorLine(stringResource(R.string.link_locked, durationText(state.retryAfterSec)))
                    LinkState.Offline -> ErrorLine(stringResource(R.string.err_network))
                    is LinkState.Failed -> ErrorLine(stringResource(R.string.link_failed, state.message))
                    else -> Text(stringResource(R.string.link_code_changes), style = MaterialTheme.typography.bodySmall, color = Ember.label, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSubmit,
                enabled = code.length == AccountRepository.CODE_LENGTH && state != LinkState.Claiming,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).height(52.dp),
            ) { Text(stringResource(R.string.link_button), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

/** One hidden text field drawn as six boxes: typing advances, backspace goes back, pasting fills them all. */
@Composable
private fun CodeField(code: String, onCode: (String) -> Unit, onSubmit: () -> Unit, enabled: Boolean, error: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val label = stringResource(R.string.link_code_label)
    BasicTextField(
        value = TextFieldValue(code, TextRange(code.length)),
        onValueChange = { onCode(it.text) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password, // no suggestions bar over a one-time code
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier.focusRequester(focus).semantics { contentDescription = label },
        decorationBox = { _ ->
            Row(Modifier.widthIn(max = 380.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(AccountRepository.CODE_LENGTH) { i ->
                    val current = enabled && i == code.length
                    val color = when {
                        error -> MaterialTheme.colorScheme.error
                        current -> Ember.accent
                        i < code.length -> Ember.accentDim.copy(alpha = 0.6f)
                        else -> Ember.outline
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .background(Ember.surface, RoundedCornerShape(12.dp))
                            .border(if (current) 2.dp else 1.dp, color, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            code.getOrNull(i)?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun LinkedView(device: Device, onDone: () -> Unit, onAgain: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Box(Modifier.size(96.dp).background(Ember.accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, contentDescription = null, tint = Ember.onAccent, modifier = Modifier.size(52.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.link_done_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.background(Ember.surface, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(deviceIcon(device.kind)), contentDescription = null, tint = Ember.accent)
        Text(device.name.ifBlank { stringResource(R.string.device_unnamed) }, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.link_done_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.done)) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.link_another)) }
}

fun deviceIcon(kind: String): Int = when (kind) {
    DeviceKind.WATCH -> R.drawable.ic_watch
    DeviceKind.PHONE -> R.drawable.ic_phone
    else -> R.drawable.ic_devices
}
