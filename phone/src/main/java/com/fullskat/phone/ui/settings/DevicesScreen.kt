package com.fullskat.phone.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fullskat.core.net.Device
import com.fullskat.core.net.DeviceKind
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.ui.ConfirmDialog
import com.fullskat.phone.ui.relativeTime

@Composable
fun DevicesScreen(vm: DevicesViewModel, onBack: () -> Unit, onLinkWatch: () -> Unit) {
    DevicesContent(
        state = vm.state,
        unlinkFailed = vm.unlinkFailed,
        onUnlink = vm::unlink,
        onReload = { vm.load() },
        onDismissError = vm::dismissError,
        onLinkWatch = onLinkWatch,
        onBack = onBack,
    )
}

/** The account's phones and watches: kind, name, when it was last seen; any but this phone can be unlinked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(
    state: DevicesState,
    unlinkFailed: Boolean,
    onUnlink: (Device) -> Unit,
    onReload: () -> Unit,
    onDismissError: () -> Unit,
    onLinkWatch: () -> Unit,
    onBack: () -> Unit,
) {
    var pending by remember { mutableStateOf<Device?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = { IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh)) } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                DevicesState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Ember.accent)
                DevicesState.Offline -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.err_network), color = Ember.label, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onReload) { Text(stringResource(R.string.retry)) }
                }
                is DevicesState.Loaded -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.devices, key = { it.id }) { d ->
                        DeviceRow(d, onUnlink = { pending = d })
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        TextButton(onClick = onLinkWatch, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                            Icon(painterResource(R.drawable.ic_watch), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.link_watch))
                        }
                    }
                }
            }
        }
    }

    pending?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.unlink_q, d.name.ifBlank { stringResource(R.string.device_unnamed) }),
            text = stringResource(R.string.unlink_desc),
            confirm = stringResource(R.string.unlink),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                onUnlink(d)
            },
        )
    }
    if (unlinkFailed) {
        AlertDialog(
            onDismissRequest = onDismissError,
            text = { Text(stringResource(R.string.err_unlink)) },
            confirmButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun DeviceRow(d: Device, onUnlink: () -> Unit) {
    val context = LocalContext.current
    val kind = stringResource(
        when (d.kind) {
            DeviceKind.WATCH -> R.string.device_watch
            DeviceKind.PHONE -> R.string.device_phone
            else -> R.string.device_other
        },
    )
    val seen = if (d.lastSeen > 0) stringResource(R.string.device_last_seen, relativeTime(context, d.lastSeen)) else null
    ListItem(
        leadingContent = { RowIcon(painterResource(deviceIcon(d.kind))) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.name.ifBlank { stringResource(R.string.device_unnamed) }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (d.current) {
                    Text(
                        stringResource(R.string.device_this),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ember.onAccent,
                        modifier = Modifier.background(Ember.accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
        supportingContent = { Text(listOfNotNull(kind, seen).joinToString(" · ")) },
        trailingContent = if (d.current) null else {
            { TextButton(onClick = onUnlink) { Text(stringResource(R.string.unlink), color = MaterialTheme.colorScheme.error) } }
        },
        colors = listColors(),
    )
}
