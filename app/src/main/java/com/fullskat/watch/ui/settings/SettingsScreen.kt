package com.fullskat.watch.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.fullskat.watch.BuildConfig
import com.fullskat.watch.R
import com.fullskat.watch.assistant.FullSkatVoiceInteractionService
import com.fullskat.watch.data.prefs.SpeechLang
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.theme.Tokens

private val itemModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)

@Composable
fun SettingsScreen(vm: AppViewModel, onModel: () -> Unit, onRepair: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings ?: return
    val listState = rememberTransformingLazyColumnState()
    var confirmUnpair by remember { mutableStateOf(false) }
    val isAssistant = remember { FullSkatVoiceInteractionService.isDefaultAssistant(context) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings), color = Tokens.accent) } }
            item {
                FilledTonalButton(
                    onClick = onModel,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_model)) },
                    secondaryLabel = { Text(cfg.model ?: cfg.serverModel ?: AppViewModel.DEFAULT_MODEL, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                val next = when (cfg.lang) {
                    SpeechLang.AUTO -> SpeechLang.RU
                    SpeechLang.RU -> SpeechLang.EN
                    SpeechLang.EN -> SpeechLang.AUTO
                }
                FilledTonalButton(
                    onClick = { vm.setLang(next) },
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_language)) },
                    secondaryLabel = { Text(langLabel(cfg.lang)) },
                )
            }
            item {
                SwitchButton(
                    checked = cfg.haptics,
                    onCheckedChange = vm::setHaptics,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_haptics)) },
                )
            }
            item {
                Button(
                    onClick = {
                        for (intent in FullSkatVoiceInteractionService.assistantSettingsIntent()) {
                            try {
                                context.startActivity(intent)
                                break
                            } catch (_: ActivityNotFoundException) {
                            }
                        }
                    },
                    modifier = itemModifier,
                    icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_assistant)) },
                    secondaryLabel = { Text(if (isAssistant) "✓" else stringResource(R.string.settings_assistant_desc), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onRepair,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_server)) },
                    secondaryLabel = { Text(cfg.serverUrl.removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                Button(
                    onClick = { confirmUnpair = true },
                    modifier = itemModifier,
                    colors = ButtonDefaults.buttonColors(containerColor = Tokens.surfaceHigh, contentColor = Tokens.error),
                    label = { Text(stringResource(R.string.settings_unpair)) },
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    color = Tokens.label,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }

    AlertDialog(
        visible = confirmUnpair,
        onDismissRequest = { confirmUnpair = false },
        title = { Text(stringResource(R.string.settings_unpair_q)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmUnpair = false
                vm.unpair()
                onUnpaired()
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmUnpair = false }) },
    )
}

@Composable
private fun langLabel(lang: SpeechLang): String = when (lang) {
    SpeechLang.AUTO -> stringResource(R.string.lang_auto)
    SpeechLang.RU -> stringResource(R.string.lang_ru)
    SpeechLang.EN -> stringResource(R.string.lang_en)
}

@Composable
fun ModelScreen(vm: AppViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings ?: return
    val listState = rememberTransformingLazyColumnState()
    val selected = cfg.model ?: cfg.serverModel ?: AppViewModel.DEFAULT_MODEL
    val models = remember(cfg.serverModel) { (listOf(cfg.serverModel) + AppViewModel.MODELS).filterNotNull().distinct() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings_model), color = Tokens.accent) } }
            items(models.size) { i ->
                val m = models[i]
                RadioButton(
                    selected = m == selected,
                    onSelect = {
                        vm.setModel(if (m == (cfg.serverModel ?: AppViewModel.DEFAULT_MODEL)) null else m)
                        onDone()
                    },
                    modifier = itemModifier,
                    label = { Text(m.substringAfter('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(m.substringBefore('/'), fontSize = 11.sp) },
                )
            }
        }
    }
}
