package com.fullskat.phone.ui.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fullskat.core.db.DialogSummary
import com.fullskat.core.sync.SyncEngine
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.R
import com.fullskat.phone.ui.RenameDialog
import com.fullskat.phone.ui.previewText
import com.fullskat.phone.ui.relativeTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DialogsViewModel(private val app: PhoneContainer) : ViewModel() {
    private val core = app.core
    private val dao = core.db.dao()

    /** Conversations only: a chat just created and still empty is not listed (nothing to open, rename or delete). */
    val dialogs: StateFlow<List<DialogSummary>?> = dao.observeDialogs().map { all -> all.filter { it.messageCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val status: StateFlow<SyncEngine.Status> = core.sync.status

    var refreshing by mutableStateOf(false)
        private set

    /** Dialogs opened here and left empty are dropped whenever the list shows again. */
    fun cleanUp() = viewModelScope.launch { dao.deleteEmptyLocalDialogsExcept("") }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                core.sync.sync()
            } finally {
                refreshing = false
            }
        }
    }

    suspend fun newDialog(): String = core.sync.newDialog()

    fun delete(id: String) = viewModelScope.launch { core.sync.deleteDialog(id) }

    fun rename(id: String, title: String) = viewModelScope.launch { core.sync.renameDialog(id, title.trim()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsScreen(vm: DialogsViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val dialogs by vm.dialogs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<DialogSummary?>(null) }
    var renaming by remember { mutableStateOf<DialogSummary?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(Unit) { vm.cleanUp() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), color = Ember.accent, fontWeight = FontWeight.SemiBold)
                        if (status == SyncEngine.Status.OFFLINE) {
                            Text(stringResource(R.string.offline), style = MaterialTheme.typography.labelSmall, color = Ember.label)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { onOpen(vm.newDialog()) } },
                icon = { Icon(painterResource(R.drawable.ic_new_chat), contentDescription = null) },
                text = { Text(stringResource(R.string.new_dialog)) },
                containerColor = Ember.accent,
                contentColor = Ember.onAccent,
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            val list = dialogs
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyDialogs()
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.id }) { d ->
                        SwipeToDelete(onDelete = { pendingDelete = d }) {
                            DialogRow(d, onClick = { onOpen(d.id) }, onRename = { renaming = d }, onDelete = { pendingDelete = d })
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    pendingDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_dialog_q)) },
            text = { Text(d.title.ifBlank { stringResource(R.string.untitled) }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(d.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    renaming?.let { d ->
        RenameDialog(
            title = stringResource(R.string.rename_dialog),
            initial = d.title,
            onDismiss = { renaming = null },
            onConfirm = {
                vm.rename(d.id, it)
                renaming = null
            },
        )
    }
}

/** Swiping a row to the left asks to delete it; the row slides back if the user says no. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialogRow(d: DialogSummary, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val figure = stringResource(R.string.preview_figure)
    val code = stringResource(R.string.preview_code)
    val preview = remember(d.lastMessage) { d.lastMessage?.let { previewText(it, figure, code) }.orEmpty() }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    d.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preview.ifBlank { if (d.messageCount == 0) stringResource(R.string.no_messages) else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                relativeTime(context, d.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = Ember.label,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyDialogs() {
    // Scrollable so pull-to-refresh works on the empty list too.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(120.dp))
        Box(Modifier.size(72.dp).background(Ember.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.no_dialogs), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.no_dialogs_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
