package com.fullskat.phone.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.chat.Turn
import com.fullskat.phone.ui.ConfirmDialog
import com.fullskat.phone.ui.ErrorCard
import com.fullskat.phone.ui.ModelMenu
import com.fullskat.phone.ui.RenameDialog
import com.fullskat.phone.ui.modelDisplayName
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onImage: (AttachmentRef) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val turn by vm.turn.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = messages.orEmpty()
    val live = turn as? Turn.Answering

    // ---------------------------------------------------------------- voice input
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var voiceBase by remember { mutableStateOf("") }
    fun joined(spoken: String) = if (voiceBase.isBlank()) spoken else voiceBase.trimEnd() + " " + spoken
    val voice = remember {
        VoiceInput(context, object : VoiceInput.Listener {
            override fun onPartial(text: String) = vm.setText(joined(text))
            override fun onFinal(text: String) {
                vm.setText(joined(text))
                listening = false
            }
            override fun onLevel(value: Float) {
                level = value
            }
            override fun onError(error: VoiceError) {
                listening = false
                scope.launch { snackbar.showSnackbar(resources.getString(voiceErrorText(error))) }
            }
        })
    }
    DisposableEffect(voice) { onDispose { voice.destroy() } }
    val startVoice = {
        voiceBase = vm.input.text
        listening = true
        voice.start()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else scope.launch { snackbar.showSnackbar(resources.getString(R.string.err_mic_permission)) }
    }

    // ---------------------------------------------------------------- images
    val remaining = ChatViewModel.MAX_IMAGES - vm.images.size
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_IMAGES)) { vm.addImages(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addImages(listOf(it)) } }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.let { if (ok) vm.addImages(listOf(it)) }
        cameraUri = null
    }

    // ---------------------------------------------------------------- the list follows the end until the user scrolls away
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val endIndex = list.size // the footer after the last message
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
    }
    val lastLength = list.lastOrNull()?.let { it.content.length + it.thinking.length } ?: 0
    LaunchedEffect(list.size, lastLength, live?.text?.length, live?.thinking?.length) {
        if (follow && list.isNotEmpty() && !listState.isScrollInProgress) listState.scrollToItem(endIndex)
    }
    // The keyboard (or the composer growing) shrinks the list: keep the newest message in view.
    val end by rememberUpdatedState(endIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect {
            if (follow && end > 0 && !listState.isScrollInProgress) listState.scrollToItem(end)
        }
    }
    val showJump by remember { derivedStateOf { !follow && listState.canScrollForward } }

    val busy = turn is Turn.Uploading || turn is Turn.Waiting || turn is Turn.Answering
    val remoteStreaming = !busy && list.lastOrNull()?.let { vm.isLiveRemoteAnswer(it) } == true
    val hasDraft = vm.input.text.isNotBlank() || vm.images.isNotEmpty()
    val action = when {
        busy -> ComposerAction.STOP
        listening -> ComposerAction.LISTENING
        hasDraft -> ComposerAction.SEND
        remoteStreaming -> ComposerAction.STOP
        else -> ComposerAction.MIC
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                title = {
                    Column {
                        Text(
                            dialog?.title?.ifBlank { null } ?: stringResource(R.string.new_dialog),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        session?.let { ModelMenu(it, onPick = vm::setModel) }
                    }
                },
                actions = {
                    // A new chat has nothing to rename or delete yet: the menu comes with its first question.
                    if (!messages.isNullOrEmpty()) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_dialog)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().imePadding()) {
                Composer(
                    input = vm.input,
                    onInput = { vm.input = it },
                    images = vm.images,
                    onRemoveImage = vm::removeImage,
                    uploading = turn as? Turn.Uploading,
                    action = action,
                    level = level,
                    canAttach = remaining > 0,
                    onGallery = {
                        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        if (remaining >= 2) pickMany.launch(request) else pickOne.launch(request)
                    },
                    onCamera = {
                        val uri = newCameraUri(context)
                        cameraUri = uri
                        try {
                            takePicture.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            cameraUri = null
                            scope.launch { snackbar.showSnackbar(resources.getString(R.string.err_no_camera)) }
                        }
                    },
                    onAction = {
                        when (action) {
                            ComposerAction.MIC -> {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (granted) startVoice() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            ComposerAction.LISTENING -> voice.stop()
                            ComposerAction.SEND -> {
                                if (listening) {
                                    voice.destroy()
                                    listening = false
                                }
                                if (vm.send()) follow = true
                            }
                            ComposerAction.STOP -> vm.stop()
                        }
                    },
                    banner = {
                        (turn as? Turn.Failed)?.let { f ->
                            ErrorCard(f.error, f.retryAfterSec, onDismiss = vm::dismissError, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (messages != null && list.isEmpty() && turn == null) EmptyChat(session?.activeModel)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)) {
                items(list.size, key = { list[it].id }) { i ->
                    val m = list[i]
                    val isLive = live != null && live.answerId == m.id
                    MessageItem(
                        m = m,
                        text = if (isLive) live.text else m.content,
                        thinking = if (isLive) live.thinking else m.thinking,
                        onImage = onImage,
                        onRetry = { vm.retry(m) },
                        onDelete = { vm.deleteMessage(m.id) },
                    )
                }
                item(key = "end") { Spacer(Modifier.height(1.dp)) }
            }
            if (showJump) {
                SmallFloatingActionButton(
                    onClick = {
                        follow = true
                        scope.launch { listState.animateScrollToItem(endIndex) }
                    },
                    containerColor = Ember.surfaceHigh,
                    contentColor = Ember.accent,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.scroll_to_latest))
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(
            title = stringResource(R.string.rename_dialog),
            initial = dialog?.title.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                vm.rename(it)
                renaming = false
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_dialog_q),
            text = null,
            confirm = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.deleteDialog()
                onBack()
            },
        )
    }
}

@Composable
private fun EmptyChat(model: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).background(Ember.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.empty_dialog),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (model != null) {
            Spacer(Modifier.height(6.dp))
            Text(modelDisplayName(model), style = MaterialTheme.typography.labelLarge, color = Ember.label)
        }
    }
}

private fun voiceErrorText(error: VoiceError): Int = when (error) {
    VoiceError.NOTHING_HEARD -> R.string.err_nothing_heard
    VoiceError.NO_PERMISSION -> R.string.err_mic_permission
    VoiceError.NETWORK -> R.string.err_speech_network
    VoiceError.BUSY -> R.string.err_speech_busy
    VoiceError.UNAVAILABLE -> R.string.err_speech_unavailable
    VoiceError.OTHER -> R.string.err_speech
}

/** A file in cache/camera/ for the camera app to write into, shared with it through the FileProvider. */
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
