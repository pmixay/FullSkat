package com.fullskat.watch.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.fullskat.watch.R
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.components.relativeTime
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.launch

@Composable
fun DialogsScreen(vm: AppViewModel, onOpen: (String) -> Unit, onNew: (String) -> Unit, onSettings: () -> Unit) {
    val dialogs by vm.dialogs.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader { Text(stringResource(R.string.app_name), color = Tokens.accent) }
            }
            item {
                // Icon-only actions in one row, so the first chat is visible without scrolling.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = { scope.launch { onNew(vm.newDialog()) } },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_new_chat), contentDescription = stringResource(R.string.new_dialog), modifier = Modifier.size(24.dp))
                    }
                    FilledIconButton(
                        onClick = onSettings,
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tokens.surfaceHigh, contentColor = Tokens.accent),
                    ) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings), modifier = Modifier.size(24.dp))
                    }
                }
            }
            if (dialogs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_dialogs),
                        color = Tokens.userText,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
            }
            items(dialogs.size, key = { dialogs[it].id }) { i ->
                val d = dialogs[i]
                Card(
                    onClick = { onOpen(d.id) },
                    onLongClick = { pendingDelete = d.id },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Column {
                        Text(
                            d.title.ifBlank { stringResource(R.string.untitled) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(relativeTime(d.updatedAt), color = Tokens.label, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    AlertDialog(
        visible = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.delete_dialog_q)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                pendingDelete?.let(vm::deleteDialog)
                pendingDelete = null
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { pendingDelete = null }) },
    )
}
