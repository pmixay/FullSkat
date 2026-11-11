package com.fullskat.watch.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.fullskat.watch.data.net.ServerEndpoint
import com.fullskat.watch.data.prefs.Settings
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.components.UsageRing
import com.fullskat.watch.ui.theme.Tokens

private val itemModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
private val indentedModifier = Modifier.fillMaxWidth().padding(start = 26.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)

@Composable
fun SettingsScreen(vm: AppViewModel, onModel: () -> Unit, onEffort: () -> Unit, onRepair: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings ?: return
    val listState = rememberTransformingLazyColumnState()
    var confirmUnpair by remember { mutableStateOf(false) }
    val isAssistant = FullSkatVoiceInteractionService.isDefaultAssistant(context)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings), color = Tokens.accent) } }
            item {
                FilledTonalButton(
                    onClick = onModel,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_model)) },
                    secondaryLabel = { Text(modelDisplayName(cfg.model ?: cfg.serverModel ?: AppViewModel.DEFAULT_MODEL), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onEffort,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_effort)) },
                    secondaryLabel = { Text(effortSummary(cfg.effectiveEffort, cfg.defaultEffort), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                SwitchButton(
                    checked = cfg.webSearch,
                    onCheckedChange = vm::setWebSearch,
                    modifier = itemModifier,
                    label = { Text(stringResource(R.string.settings_web_search)) },
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
                    secondaryLabel = { Text(ServerEndpoint.URL.removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

/** One provider's section of the model picker. */
private data class ProviderGroup(val id: String, val models: List<String>, val usageRemaining: Float?, val usageLabel: String)

private fun providerName(id: String): String = when (id) {
    "anthropic" -> "Claude"
    "openrouter" -> "OpenRouter"
    "kimi" -> "Kimi"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** Provider of a model id when the server did not say (older servers), judged by the naming convention. */
private fun inferProvider(model: String): String = when {
    model.startsWith("claude-") -> "anthropic"
    model.startsWith("kimi-") -> "kimi"
    '/' in model -> "openrouter"
    else -> "other"
}

/** The server's working providers with their models; models it listed outside any group are filed by naming. */
private fun providerGroups(cfg: Settings): List<ProviderGroup> {
    val offered = (listOf(cfg.serverModel) + cfg.serverModels.ifEmpty { AppViewModel.MODELS }).filterNotNull().distinct()
    val groups = LinkedHashMap<String, ProviderGroup>()
    cfg.providers.forEach { groups[it.id] = ProviderGroup(it.id, it.models, it.usageRemaining, it.usageLabel) }
    offered.filter { m -> groups.values.none { m in it.models } }.forEach { m ->
        val id = inferProvider(m)
        val group = groups[id]
        groups[id] = if (group == null) ProviderGroup(id, listOf(m), null, "") else group.copy(models = group.models + m)
    }
    return groups.values.toList()
}

@Composable
fun ModelScreen(vm: AppViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings ?: return
    val listState = rememberTransformingLazyColumnState()
    val selected = cfg.model ?: cfg.serverModel ?: AppViewModel.DEFAULT_MODEL
    val groups = remember(cfg.serverModel, cfg.serverModels, cfg.providers) { providerGroups(cfg) }
    // Every provider starts collapsed; opening one does not close the others.
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    // The usage rings go stale; ask the server again whenever the picker opens.
    LaunchedEffect(Unit) { vm.refreshServerInfo() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings_model), color = Tokens.accent) } }
            groups.forEach { group ->
                val open = group.id in expanded
                item(key = "provider-${group.id}") {
                    ProviderHeader(
                        group = group,
                        expanded = open,
                        selectedModel = selected.takeIf { it in group.models },
                        onToggle = { expanded = if (open) expanded - group.id else expanded + group.id },
                    )
                }
                if (open) {
                    items(group.models.size, key = { i -> "model-${group.id}-${group.models[i]}" }) { i ->
                        val m = group.models[i]
                        RadioButton(
                            selected = m == selected,
                            onSelect = {
                                vm.setModel(if (m == (cfg.serverModel ?: AppViewModel.DEFAULT_MODEL)) null else m)
                                onDone()
                            },
                            modifier = indentedModifier,
                            label = { Text(modelDisplayName(m), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = if ('/' in m) {
                                { Text(m.substringBefore('/'), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Collapsible provider row: chevron, name, and on the right the ring of budget left. */
@Composable
private fun ProviderHeader(group: ProviderGroup, expanded: Boolean, selectedModel: String?, onToggle: () -> Unit) {
    // A collapsed group still tells which of its models is selected.
    val hint = listOfNotNull(
        selectedModel?.takeIf { !expanded }?.let(::modelDisplayName),
        group.usageLabel.ifBlank { null },
    ).joinToString(" · ")
    FilledTonalButton(
        onClick = onToggle,
        modifier = itemModifier,
        icon = {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        },
        label = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(providerName(group.id), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                group.usageRemaining?.let { UsageRing(it) }
            }
        },
        secondaryLabel = if (hint.isEmpty()) {
            null
        } else {
            { Text(hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    )
}

/**
 * Friendly model name: "claude-sonnet-5-5" -> "Sonnet 5.5", "claude-sonnet-5" -> "Sonnet 5";
 * OpenRouter ids such as "openai/gpt-5.5" -> "gpt-5.5".
 */
internal fun modelDisplayName(id: String): String {
    if (!id.startsWith("claude-")) return id.substringAfter('/')
    val parts = id.removePrefix("claude-").split('-').filter { it.isNotEmpty() }
    val family = parts.takeWhile { p -> !p.all { it.isDigit() } }
    val version = parts.drop(family.size).takeWhile { p -> p.length <= 2 && p.all { it.isDigit() } }
    if (family.isEmpty() && version.isEmpty()) return id
    val familyName = family.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    return listOf(familyName, version.joinToString(".")).filter { it.isNotEmpty() }.joinToString(" ")
}

@Composable
private fun effortName(effort: String): String = when (effort) {
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    else -> effort.replaceFirstChar { it.uppercase() } // levels only some models have: none, minimal, ...
}

/** "Medium", or "Default (Medium)" when the watch leaves the choice to the server. */
@Composable
private fun effortSummary(effort: String?, defaultEffort: String?): String {
    if (effort != null) return effortName(effort)
    val default = stringResource(R.string.effort_default)
    return if (defaultEffort != null) "$default (${effortName(defaultEffort)})" else default
}

@Composable
fun EffortScreen(vm: AppViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings ?: return
    val listState = rememberTransformingLazyColumnState()
    // Only the levels the selected model accepts; all of them when the server did not say.
    val options: List<String?> = listOf<String?>(null) + (cfg.supportedEfforts ?: AppViewModel.EFFORTS)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings_effort), color = Tokens.accent) } }
            items(options.size) { i ->
                val e = options[i]
                val defaultEffort = cfg.defaultEffort
                RadioButton(
                    selected = e == cfg.effectiveEffort,
                    onSelect = {
                        vm.setEffort(e)
                        onDone()
                    },
                    modifier = itemModifier,
                    label = { Text(if (e == null) stringResource(R.string.effort_default) else effortName(e), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (e == null && defaultEffort != null) {
                        { Text(effortName(defaultEffort), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
