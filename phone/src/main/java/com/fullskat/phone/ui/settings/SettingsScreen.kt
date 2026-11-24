package com.fullskat.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fullskat.core.ServerEndpoint
import com.fullskat.core.session.SessionState
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.BuildConfig
import com.fullskat.phone.R
import com.fullskat.phone.ui.ALL_EFFORTS
import com.fullskat.phone.ui.ConfirmDialog
import com.fullskat.phone.ui.RenameDialog
import com.fullskat.phone.ui.SectionHeader
import com.fullskat.phone.ui.modelDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onModel: () -> Unit,
    onLinkWatch: () -> Unit,
    onDevices: () -> Unit,
) {
    val session by vm.session.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var choosingEffort by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val s = session ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader(stringResource(R.string.settings_account)) }
            item {
                val name = s.user?.name.orEmpty()
                ListItem(
                    leadingContent = { Avatar(name) },
                    headlineContent = { Text(name.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(R.string.settings_name_hint)) },
                    colors = listColors(),
                    modifier = Modifier.clickable { renaming = true },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_assistant)) }
            item {
                NavRow(
                    title = stringResource(R.string.settings_model),
                    value = s.activeModel?.let(::modelDisplayName) ?: stringResource(R.string.effort_default),
                    onClick = onModel,
                )
            }
            item {
                val adjustable = s.supportedEfforts?.isNotEmpty() != false
                NavRow(
                    title = stringResource(R.string.settings_effort),
                    value = if (adjustable) effortSummary(s.effectiveEffort, s.defaultEffort) else stringResource(R.string.effort_fixed),
                    enabled = adjustable,
                    onClick = { choosingEffort = true },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_web_search),
                    subtitle = stringResource(if (s.info.webSearch) R.string.settings_web_search_desc else R.string.settings_off_on_server),
                    checked = s.webSearch,
                    enabled = s.info.webSearch,
                    onChange = vm::setWebSearch,
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_figures),
                    subtitle = stringResource(if (s.info.figures) R.string.settings_figures_desc else R.string.settings_off_on_server),
                    checked = s.figures,
                    enabled = s.info.figures,
                    onChange = vm::setFigures,
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_shared_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ember.label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_devices)) }
            item {
                ListItem(
                    leadingContent = { RowIcon(painterResource(R.drawable.ic_watch)) },
                    headlineContent = { Text(stringResource(R.string.link_watch)) },
                    supportingContent = { Text(stringResource(R.string.link_watch_desc)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ember.label) },
                    colors = listColors(),
                    modifier = Modifier.clickable(onClick = onLinkWatch),
                )
            }
            item {
                ListItem(
                    leadingContent = { RowIcon(painterResource(R.drawable.ic_devices)) },
                    headlineContent = { Text(stringResource(R.string.devices)) },
                    supportingContent = { Text(stringResource(R.string.devices_desc)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ember.label) },
                    colors = listColors(),
                    modifier = Modifier.clickable(onClick = onDevices),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                ListItem(
                    leadingContent = { RowIcon(vector = Icons.Default.Info) },
                    headlineContent = { Text(s.serverName ?: stringResource(R.string.app_name)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_server_version, ServerEndpoint.URL.removePrefix("https://"), BuildConfig.VERSION_NAME))
                    },
                    colors = listColors(),
                )
            }
            item {
                ListItem(
                    leadingContent = { RowIcon(vector = Icons.AutoMirrored.Filled.ExitToApp, tint = Ember.error) },
                    headlineContent = { Text(stringResource(R.string.sign_out), color = Ember.error) },
                    colors = listColors(),
                    modifier = Modifier.clickable { confirmSignOut = true },
                )
            }
        }
    }

    val s = session
    if (renaming && s != null) {
        RenameDialog(
            title = stringResource(R.string.settings_your_name),
            initial = s.user?.name.orEmpty(),
            maxLength = 40,
            onDismiss = { renaming = false },
            onConfirm = {
                vm.rename(it)
                renaming = false
            },
        )
    }
    if (vm.renameFailed) {
        AlertDialog(
            onDismissRequest = vm::dismissRenameError,
            text = { Text(stringResource(R.string.err_rename)) },
            confirmButton = { TextButton(onClick = vm::dismissRenameError) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (choosingEffort && s != null) {
        EffortDialog(s, onDismiss = { choosingEffort = false }, onPick = {
            vm.setEffort(it)
            choosingEffort = false
        })
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.sign_out_q),
            text = stringResource(R.string.sign_out_desc),
            confirm = stringResource(R.string.sign_out),
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
        )
    }
}

@Composable
internal fun listColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(40.dp).background(Ember.userBubble, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Ember.onUserBubble,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun RowIcon(painter: androidx.compose.ui.graphics.painter.Painter? = null, vector: ImageVector? = null, tint: Color = Ember.accent) {
    Box(Modifier.size(40.dp).background(Ember.surface, CircleShape), contentAlignment = Alignment.Center) {
        if (painter != null) Icon(painter, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        else if (vector != null) Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = if (enabled) Ember.accent else Ember.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ember.label) },
        colors = listColors(),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Ember.onAccent, checkedTrackColor = Ember.accent),
            )
        },
        colors = listColors(),
        modifier = Modifier.selectable(selected = checked, enabled = enabled, role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
internal fun effortName(effort: String): String = when (effort) {
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    "none" -> stringResource(R.string.effort_none)
    "minimal" -> stringResource(R.string.effort_minimal)
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** "Medium", or "Default (Medium)" when the choice is left to the server. */
@Composable
private fun effortSummary(effort: String?, defaultEffort: String?): String {
    if (effort != null) return effortName(effort)
    val default = stringResource(R.string.effort_default)
    return if (defaultEffort != null) "$default (${effortName(defaultEffort)})" else default
}

/** Only the levels the active model accepts (all of them when the server did not say), plus the server default. */
@Composable
private fun EffortDialog(s: SessionState, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val options: List<String?> = listOf<String?>(null) + (s.supportedEfforts ?: ALL_EFFORTS)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_effort)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { e ->
                    val selected = e == s.effectiveEffort
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton) { onPick(e) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column {
                            Text(if (e == null) stringResource(R.string.effort_default) else effortName(e), style = MaterialTheme.typography.bodyLarge)
                            if (e == null && s.defaultEffort != null) {
                                Text(effortName(s.defaultEffort!!), style = MaterialTheme.typography.bodySmall, color = Ember.label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
