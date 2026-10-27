package com.fullskat.watch.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.fullskat.watch.R
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.domain.TurnState
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.components.ErrorChip
import com.fullskat.watch.ui.components.errorText
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

@Composable
fun ChatScreen(vm: AppViewModel, dialogId: String, autoListen: Boolean) {
    val context = LocalContext.current
    val messages by remember(dialogId) { vm.messages(dialogId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val turn by vm.turnState.collectAsStateWithLifecycle()
    val view = LocalView.current

    // Keep the screen on only while a turn is running; the rest of the time the system may dim as usual.
    DisposableEffect(turn.busy) {
        view.keepScreenOn = turn.busy
        onDispose { view.keepScreenOn = false }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startTurn(dialogId)
    }
    val startListening = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startTurn(dialogId) else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(dialogId, autoListen) {
        if (autoListen && !turn.busy) startListening()
    }
    // Errors clear themselves after a few seconds.
    LaunchedEffect(turn) {
        if (turn is TurnState.Failed) {
            delay(4_000)
            vm.dismissError()
        }
    }

    val listState = rememberTransformingLazyColumnState()
    val answering = turn as? TurnState.Answering
    val itemCount = messages.size + (if (turn is TurnState.Thinking) 1 else 0)
    LaunchedEffect(itemCount, answering?.text?.length?.div(40)) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        val bottomInset = 60.dp // room for the floating mic button
        val padding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + bottomInset,
        )
        TransformingLazyColumn(state = listState, contentPadding = padding) {
            if (messages.isEmpty() && turn !is TurnState.Thinking) {
                item {
                    Text(
                        stringResource(R.string.empty_dialog),
                        color = Tokens.userText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(messages.size, key = { messages[it].id }) { index ->
                val m = messages[index]
                val streaming = m.pending && index == messages.lastIndex && answering != null
                MessageRow(
                    m,
                    if (streaming) answering.text else m.content,
                    if (streaming) answering.thinking else m.thinking,
                )
            }
            if (turn is TurnState.Thinking) {
                item {
                    Text(
                        stringResource(R.string.thinking),
                        color = Tokens.label,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Floating round mic, Gemini-style: sits 14 dp above the bottom of the round display.
        Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            when (turn) {
                is TurnState.Listening, TurnState.Transcribing -> Unit
                TurnState.Thinking -> CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                is TurnState.Answering -> FilledIconButton(
                    onClick = { vm.cancelTurn() },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tokens.surfaceHigh, contentColor = Tokens.accent),
                ) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.stop), modifier = Modifier.size(18.dp))
                }
                else -> FilledIconButton(onClick = startListening, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.mic), modifier = Modifier.size(22.dp))
                }
            }
        }
        val failed = turn as? TurnState.Failed
        if (failed != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                ErrorChip(errorText(failed.kind), Modifier.padding(bottom = 60.dp).clickable { vm.dismissError() })
            }
        }
        val listening = turn as? TurnState.Listening
        if (listening != null || turn is TurnState.Transcribing) {
            ListeningOverlay(
                partial = listening?.partial.orEmpty(),
                level = listening?.level ?: 0f,
                finishing = turn is TurnState.Transcribing,
                onTap = { vm.finishListening() },
            )
        }
    }
}

@Composable
private fun MessageRow(m: MessageEntity, text: String, thinking: String) {
    val isUser = m.role == Role.USER
    val shape = if (isUser) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val showThinking = !isUser && thinking.isNotBlank()
    // While the model is still reasoning there is no answer yet: the thinking header replaces the "…" placeholder.
    val showBubble = text.isNotBlank() || (m.pending && !showThinking)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (showThinking) ThinkingBlock(thinking = thinking, live = m.pending && text.isBlank())
        if (showBubble) {
            Text(
                text = text.ifBlank { "…" },
                color = if (isUser) Tokens.onUserBubble else Tokens.onAssistantBubble,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start)
                    .background(if (isUser) Tokens.userBubble else Tokens.assistantBubble, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The model's reasoning summary: a one-line header with a chevron, collapsed until tapped. */
@Composable
private fun ThinkingBlock(thinking: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, animationSpec = tween(150), label = "chevron")
    Column(Modifier.fillMaxWidth(0.86f)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.thinking_title) + if (live) "…" else "",
                color = Tokens.label,
                fontSize = 12.sp,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = Tokens.label,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(rotation),
            )
        }
        if (expanded) {
            Text(
                text = thinking,
                color = Tokens.label,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * Full-screen listening state: the words recognised so far, a level arc hugging the bottom
 * of the round display, and a tap-anywhere-to-send affordance.
 */
@Composable
private fun ListeningOverlay(partial: String, level: Float, finishing: Boolean, onTap: () -> Unit) {
    val animatedLevel by animateFloatAsState(targetValue = level.coerceIn(0f, 0.6f) / 0.6f, animationSpec = tween(90), label = "level")
    val accent = Tokens.accent
    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = partial.ifBlank { stringResource(R.string.listening) },
            color = if (partial.isBlank()) Tokens.userText else Tokens.onUserBubble,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            modifier = Modifier.padding(horizontal = 26.dp).padding(bottom = 18.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            // Bezel ring: a soft halo whose width follows the voice level, plus a crisp rim.
            val halo = 8.dp.toPx() + animatedLevel * 22.dp.toPx()
            drawCircle(
                color = accent.copy(alpha = 0.10f + animatedLevel * 0.35f),
                radius = size.minDimension / 2 - halo / 2,
                style = Stroke(width = halo),
            )
            val rim = 4.dp.toPx()
            drawCircle(
                color = accent,
                radius = size.minDimension / 2 - rim / 2,
                style = Stroke(width = rim),
            )
        }
        Text(
            text = if (finishing) "…" else stringResource(R.string.tap_to_send),
            color = accent,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        )
    }
}
