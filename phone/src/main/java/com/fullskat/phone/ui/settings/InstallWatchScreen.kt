package com.fullskat.phone.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.watchinstall.AdbProblem
import com.fullskat.phone.watchinstall.BundledApk
import com.fullskat.phone.watchinstall.InstallAction
import com.fullskat.phone.watchinstall.InstallError
import com.fullskat.phone.watchinstall.WatchInfo
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.watchinstall.installAction
import com.fullskat.phone.watchinstall.wearOsVersion

@Composable
fun InstallWatchScreen(vm: InstallWatchViewModel, onBack: () -> Unit, onLinkWatch: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onPermissionResult)
    LifecycleResumeEffect(vm) {
        vm.recheckPermission()
        onPauseOrDispose {}
    }
    InstallWatchContent(
        state = vm.state,
        actions = vm,
        onAllowNetwork = {
            if (vm.state.permissionDenied) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                runCatching { context.startActivity(intent) }
            } else {
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        },
        onBack = onBack,
        onLinkWatch = onLinkWatch,
    )
}

private enum class StepState { ACTIVE, DONE, WAITING }

/**
 * Four steps on one page: prepare the watch, pair (once), connect, install; then a done page. Steps that are behind
 * fold into one line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallWatchContent(
    state: InstallerState,
    actions: InstallerActions,
    onAllowNetwork: () -> Unit,
    onBack: () -> Unit,
    onLinkWatch: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.iw_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val install = state.install
            if (install is InstallStatus.Done) {
                DoneView(install, onDone = onBack, onLinkWatch = onLinkWatch)
                return@Column
            }
            Text(
                stringResource(R.string.iw_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (state.needsPermission) PermissionCard(state.permissionDenied, onAllowNetwork)
            PrepareStep(state)
            PairStep(state, actions)
            ConnectStep(state, actions)
            InstallStep(state, actions)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- steps

@Composable
private fun PrepareStep(state: InstallerState) {
    val seen = state.connected != null || state.pairServices.isNotEmpty() || state.connectServices.isNotEmpty()
    var open by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = open ?: !seen
    StepCard(
        number = 1,
        title = stringResource(R.string.iw_step_prepare),
        step = if (seen) StepState.DONE else StepState.ACTIVE,
        summary = if (seen) stringResource(R.string.iw_prepare_done) else null,
        expanded = expanded,
        onToggle = { open = !expanded },
    ) {
        Bullet(stringResource(R.string.iw_prepare_wifi))
        Bullet(stringResource(R.string.iw_prepare_dev))
        Bullet(stringResource(R.string.iw_prepare_wireless))
        Bullet(stringResource(R.string.iw_prepare_pair))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairStep(state: InstallerState, actions: InstallerActions) {
    val done = state.pair == PairStatus.Paired || state.connected != null
    StepCard(
        number = 2,
        title = stringResource(R.string.iw_step_pair),
        step = if (done) StepState.DONE else StepState.ACTIVE,
        summary = if (done) stringResource(R.string.iw_paired) else null,
        expanded = !done,
    ) {
        val working = state.pair == PairStatus.Working
        if (state.notPaired) {
            Text(stringResource(R.string.iw_not_paired), style = MaterialTheme.typography.bodyMedium, color = Ember.accent)
            Spacer(Modifier.height(8.dp))
        }
        if (state.pairServices.isNotEmpty()) {
            Text(stringResource(R.string.iw_found), style = MaterialTheme.typography.labelMedium, color = Ember.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.pairServices.forEach { s ->
                    ServiceChip(s, selected = s.host == state.pairHost && s.port.toString() == state.pairPort, enabled = !working) { actions.selectPairService(s) }
                }
            }
        } else {
            Searching(stringResource(R.string.iw_pair_searching))
            Text(stringResource(R.string.iw_pair_manual), style = MaterialTheme.typography.bodySmall, color = Ember.label)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.pairHost,
                onValueChange = actions::onPairHost,
                label = { Text(stringResource(R.string.iw_host)) },
                singleLine = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.weight(1.7f),
            )
            OutlinedTextField(
                value = state.pairPort,
                onValueChange = actions::onPairPort,
                label = { Text(stringResource(R.string.iw_port)) },
                singleLine = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.iw_code), style = MaterialTheme.typography.labelMedium, color = Ember.label)
        Spacer(Modifier.height(6.dp))
        PairingCodeField(state.pairCode, actions::onPairCode, onDone = actions::pair, enabled = !working, error = state.pair is PairStatus.Failed)
        Spacer(Modifier.height(10.dp))
        when (val p = state.pair) {
            PairStatus.Working -> Progress(stringResource(R.string.iw_pairing))
            is PairStatus.Failed -> ErrorText(adbProblemText(p.problem), p.detail.takeIf { p.problem == AdbProblem.OTHER })
            else -> Text(stringResource(R.string.iw_pair_once), style = MaterialTheme.typography.bodySmall, color = Ember.label)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = actions::pair,
            enabled = state.pairCode.length == 6 && !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.iw_pair_button)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectStep(state: InstallerState, actions: InstallerActions) {
    val info = state.connected
    StepCard(
        number = 3,
        title = stringResource(R.string.iw_step_connect),
        step = if (info != null) StepState.DONE else StepState.ACTIVE,
        expanded = true,
    ) {
        if (info != null) {
            WatchCard(info, minSdk = (state.bundled as? BundledApk.Ready)?.minSdk ?: 0)
            TextButton(onClick = actions::disconnect, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.iw_disconnect))
            }
            return@StepCard
        }
        val working = state.connection == ConnectionStatus.Working
        if (state.connectServices.isNotEmpty()) {
            Text(stringResource(R.string.iw_found), style = MaterialTheme.typography.labelMedium, color = Ember.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.connectServices.forEach { s ->
                    ServiceChip(s, selected = s.address == state.connectAddress, enabled = !state.busy) { actions.selectConnectService(s) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = state.connectAddress,
            onValueChange = actions::onConnectAddress,
            label = { Text(stringResource(R.string.iw_connect_address)) },
            placeholder = { Text("192.168.1.23:41235") },
            supportingText = { Text(stringResource(R.string.iw_connect_hint)) },
            singleLine = true,
            enabled = !working,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { actions.connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        when (val c = state.connection) {
            ConnectionStatus.Working -> {
                Progress(stringResource(R.string.iw_connecting, state.connectAddress))
                Spacer(Modifier.height(10.dp))
            }
            is ConnectionStatus.Failed -> {
                ErrorText(adbProblemText(c.problem), c.detail.takeIf { c.problem == AdbProblem.OTHER })
                Spacer(Modifier.height(10.dp))
            }
            else -> Unit
        }
        OutlinedButton(
            onClick = actions::connect,
            enabled = state.connectAddress.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.iw_connect_button)) }
    }
}

@Composable
private fun InstallStep(state: InstallerState, actions: InstallerActions) {
    val info = state.connected
    val bundled = state.bundled
    StepCard(
        number = 4,
        title = stringResource(R.string.iw_step_install),
        step = if (info != null && bundled is BundledApk.Ready) StepState.ACTIVE else StepState.WAITING,
        expanded = true,
    ) {
        val context = LocalContext.current
        when (bundled) {
            BundledApk.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ember.accent)
            BundledApk.NotBundled -> Note(stringResource(R.string.iw_not_bundled))
            BundledApk.Broken -> ErrorText(stringResource(R.string.iw_broken))
            is BundledApk.Ready -> Text(
                stringResource(R.string.iw_bundled, bundled.version.label, Formatter.formatShortFileSize(context, bundled.size)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (bundled !is BundledApk.Ready) return@StepCard
        if (info == null) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.iw_connect_first), style = MaterialTheme.typography.bodySmall, color = Ember.label)
            return@StepCard
        }
        Spacer(Modifier.height(12.dp))
        val action = installAction(bundled.version, info.installed)
        when (val install = state.install) {
            InstallStatus.Idle -> {
                if (action == InstallAction.DOWNGRADE) {
                    Note(stringResource(R.string.iw_newer_on_watch, info.installed?.label.orEmpty()))
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = actions::install, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(painterResource(R.drawable.ic_install), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (action) {
                            InstallAction.INSTALL, InstallAction.DOWNGRADE -> stringResource(R.string.iw_install_button)
                            InstallAction.UPDATE -> stringResource(R.string.iw_update_button, bundled.version.name.ifBlank { bundled.version.label })
                            InstallAction.REINSTALL -> stringResource(R.string.iw_reinstall_button)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            is InstallStatus.Uploading -> {
                val fraction = if (install.total > 0) (install.sent.toFloat() / install.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Ember.accent, trackColor = Ember.surfaceHigh)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.iw_sending, Formatter.formatShortFileSize(context, install.sent), Formatter.formatShortFileSize(context, install.total)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.iw_keep_open), style = MaterialTheme.typography.bodySmall, color = Ember.label)
            }
            InstallStatus.Installing, InstallStatus.Removing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Ember.accent, trackColor = Ember.surfaceHigh)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (install == InstallStatus.Removing) R.string.iw_removing else R.string.iw_installing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.iw_keep_open), style = MaterialTheme.typography.bodySmall, color = Ember.label)
            }
            is InstallStatus.Failed -> InstallFailure(install, actions)
            is InstallStatus.Done -> Unit
        }
    }
}

@Composable
private fun InstallFailure(failure: InstallStatus.Failed, actions: InstallerActions) {
    ErrorText(installErrorText(failure.error), failure.detail.takeIf { it.isNotBlank() })
    Spacer(Modifier.height(12.dp))
    val canReplace = failure.error == InstallError.UPDATE_INCOMPATIBLE || failure.error == InstallError.VERSION_DOWNGRADE
    if (canReplace) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.iw_remove_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = actions::removeAndInstall,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.iw_remove_and_install), textAlign = TextAlign.Center) }
        TextButton(onClick = actions::dismissInstallError, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    } else {
        OutlinedButton(
            onClick = if (failure.error == InstallError.LOST) actions::dismissInstallError else actions::install,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun DoneView(done: InstallStatus.Done, onDone: () -> Unit, onLinkWatch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(96.dp).background(Ember.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Ember.onAccent, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.iw_done_title, done.version.name.ifBlank { done.version.label }),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (done.relink) R.string.iw_done_relink else R.string.iw_done_updated),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().background(Ember.surface, RoundedCornerShape(16.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.iw_done_battery), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(28.dp))
        if (done.relink) {
            Button(onClick = onLinkWatch, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.link_watch)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.done)) }
        } else {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.done)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- parts

@Composable
private fun PermissionCard(denied: Boolean, onAllow: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ember.userBubble, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(
            stringResource(if (denied) R.string.iw_permission_denied else R.string.iw_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = Ember.onUserBubble,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onAllow) { Text(stringResource(if (denied) R.string.iw_open_settings else R.string.iw_allow)) }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    step: StepState,
    expanded: Boolean,
    summary: String? = null,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ember.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = if (expanded) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepBadge(number, step)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (step == StepState.WAITING) Ember.label else MaterialTheme.colorScheme.onSurface,
                )
                if (summary != null && !expanded) Text(summary, style = MaterialTheme.typography.bodySmall, color = Ember.label)
            }
            if (onToggle != null) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    tint = Ember.label,
                )
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), content = content)
        }
    }
}

@Composable
private fun StepBadge(number: Int, step: StepState) {
    val modifier = Modifier.size(30.dp)
    when (step) {
        StepState.ACTIVE -> Box(modifier.background(Ember.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", color = Ember.onAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        StepState.DONE -> Box(modifier.background(Ember.accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Ember.accent, modifier = Modifier.size(18.dp))
        }
        StepState.WAITING -> Box(modifier.border(1.dp, Ember.outline, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", color = Ember.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(Ember.accentDim, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServiceChip(service: WatchService, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(painterResource(R.drawable.ic_watch), contentDescription = null, modifier = Modifier.size(18.dp)) },
        label = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(service.address, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                service.serial?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Ember.label) }
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Ember.userBubble,
            selectedLabelColor = Ember.onUserBubble,
            selectedLeadingIconColor = Ember.accent,
            iconColor = Ember.accent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected, borderColor = Ember.outline, selectedBorderColor = Ember.accentDim),
    )
}

@Composable
private fun Searching(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ember.accentDim)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ember.accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ember.label)
    }
}

@Composable
private fun ErrorText(text: String, detail: String? = null) {
    Column {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (!detail.isNullOrBlank()) {
            Text(detail, color = Ember.label, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Ember.surfaceHigh, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Ember.accentDim, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The connected watch: model, system, processor and the FullSkat on it. */
@Composable
private fun WatchCard(info: WatchInfo, minSdk: Int) {
    Row(
        Modifier.fillMaxWidth().background(Ember.surfaceLow, RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, Ember.outline), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).background(Ember.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_watch), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val name = listOf(info.manufacturer.replaceFirstChar { it.uppercase() }, info.model).filter { it.isNotBlank() }.distinct()
                .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
                .joinToString(" ")
            Text(name.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
            val wear = wearOsVersion(info.sdk)
            Text(
                if (wear != null) stringResource(R.string.iw_watch_system, wear, info.androidVersion, info.sdk)
                else stringResource(R.string.iw_watch_system_android, info.androidVersion, info.sdk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (info.abis.isNotEmpty()) {
                Text(stringResource(R.string.iw_watch_abis, info.abis.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.iw_watch_app, info.installed?.label ?: stringResource(R.string.iw_not_installed)),
                style = MaterialTheme.typography.bodySmall,
                color = if (info.installed != null) Ember.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (minSdk > 0 && info.sdk in 1 until minSdk) {
                Text(stringResource(R.string.iw_too_old, minSdk, info.sdk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** One hidden field drawn as six digit boxes; the sixth digit pairs. */
@Composable
private fun PairingCodeField(code: String, onCode: (String) -> Unit, onDone: () -> Unit, enabled: Boolean, error: Boolean) {
    val label = stringResource(R.string.iw_code)
    BasicTextField(
        value = TextFieldValue(code, TextRange(code.length)),
        onValueChange = { onCode(it.text) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.semantics { contentDescription = label },
        decorationBox = { _ ->
            Row(Modifier.widthIn(max = 380.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    val current = enabled && i == code.length
                    val color: Color = when {
                        error -> MaterialTheme.colorScheme.error
                        current -> Ember.accent
                        i < code.length -> Ember.accentDim.copy(alpha = 0.6f)
                        else -> Ember.outline
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(Ember.surfaceLow, RoundedCornerShape(12.dp))
                            .border(if (current) 2.dp else 1.dp, color, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            code.getOrNull(i)?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun adbProblemText(problem: AdbProblem): String = stringResource(
    when (problem) {
        AdbProblem.WRONG_CODE -> R.string.iw_adb_wrong_code
        AdbProblem.TIMEOUT -> R.string.iw_adb_timeout
        AdbProblem.UNREACHABLE -> R.string.iw_adb_unreachable
        AdbProblem.REFUSED -> R.string.iw_adb_refused
        AdbProblem.NOT_PAIRED -> R.string.iw_adb_not_paired
        AdbProblem.LOST -> R.string.iw_adb_lost
        AdbProblem.NO_TLS -> R.string.iw_adb_no_tls
        AdbProblem.BAD_ADDRESS -> R.string.iw_adb_bad_address
        AdbProblem.OTHER -> R.string.iw_adb_other
    },
)

@Composable
private fun installErrorText(error: InstallError): String = stringResource(
    when (error) {
        InstallError.UPDATE_INCOMPATIBLE -> R.string.iw_err_incompatible
        InstallError.INSUFFICIENT_STORAGE -> R.string.iw_err_storage
        InstallError.VERSION_DOWNGRADE -> R.string.iw_err_downgrade
        InstallError.NO_MATCHING_ABIS -> R.string.iw_err_abi
        InstallError.OLDER_SDK -> R.string.iw_err_sdk
        InstallError.INVALID_APK -> R.string.iw_err_invalid
        InstallError.LOST -> R.string.iw_err_lost
        InstallError.OTHER -> R.string.iw_err_other
    },
)
