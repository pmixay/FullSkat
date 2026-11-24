package com.fullskat.phone.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.WebSource
import com.fullskat.core.net.WebSources
import com.fullskat.core.ui.rich.AttachmentImage
import com.fullskat.core.ui.rich.RichText
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.chat.ChatError
import com.fullskat.phone.ui.copyToClipboard
import com.fullskat.phone.ui.errorText

private val userShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val assistantShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

/**
 * One message. [text] and [thinking] may be the live copy of an answer streaming on this device. A long press
 * offers copy and delete (and retry for a question that was not sent).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    m: MessageEntity,
    text: String,
    thinking: String,
    onImage: (AttachmentRef) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val notSent = m.isUser && m.status == MessageStatus.ERROR
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (m.isUser) 48.dp else 12.dp, end = if (m.isUser) 12.dp else 24.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = if (m.isUser) Alignment.End else Alignment.Start,
    ) {
        if (!m.isUser && thinking.isNotBlank()) ThinkingBlock(thinking, live = m.pending && text.isBlank())
        val attachments = remember(m.attachments) { m.attachmentList }
        if (attachments.isNotEmpty()) {
            AttachmentGrid(attachments, onImage, Modifier.padding(bottom = if (text.isNotBlank()) 4.dp else 0.dp))
        }
        Box {
            val bubble = Modifier
                .clip(if (m.isUser) userShape else assistantShape)
                .combinedClickable(onClick = { if (notSent) onRetry() }, onLongClick = { menu = true })
            when {
                m.isUser && text.isNotBlank() -> Text(
                    text,
                    color = Ember.onUserBubble,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = bubble.background(Ember.userBubble).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                !m.isUser && text.isNotBlank() -> RichText(
                    markdown = text,
                    color = Ember.onAssistantBubble,
                    animateFigures = m.pending, // figures draw themselves while the answer arrives
                    modifier = bubble.fillMaxWidth().background(Ember.assistantBubble).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                !m.isUser && m.pending && thinking.isBlank() -> Box(
                    bubble.background(Ember.assistantBubble).padding(horizontal = 18.dp, vertical = 14.dp),
                ) { TypingDots() }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (notSent) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.retry)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = { menu = false; onRetry() },
                    )
                }
                if (text.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                        onClick = { menu = false; copyToClipboard(context, text) },
                    )
                }
                if (!m.pending) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        when {
            notSent -> StatusLine(stringResource(R.string.not_sent), error = true, onClick = onRetry)
            m.isUser && m.status == MessageStatus.SENDING -> StatusLine(stringResource(R.string.sending), error = false)
            !m.isUser && m.status == MessageStatus.ERROR -> {
                val kind = ChatError.fromCode(m.error)
                StatusLine(
                    if (kind != null) stringResource(R.string.answer_failed_why, errorText(kind)) else stringResource(R.string.answer_failed),
                    error = true,
                )
            }
        }
        if (!m.isUser && !m.pending) {
            val sources = remember(m.sources) { WebSources.decode(m.sources) }
            if (sources.isNotEmpty()) SourcesBlock(sources)
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error) Icon(Icons.Default.Warning, contentDescription = null, tint = Ember.error, modifier = Modifier.size(14.dp).padding(end = 2.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (error) Ember.error else Ember.label, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Attached images: one alone keeps its shape; two to four sit in a grid of squares. */
@Composable
private fun AttachmentGrid(refs: List<AttachmentRef>, onImage: (AttachmentRef) -> Unit, modifier: Modifier = Modifier) {
    val open = stringResource(R.string.open_image)
    val tile = @Composable { ref: AttachmentRef, m: Modifier ->
        AttachmentImage(
            ref,
            thumb = true,
            modifier = m.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = open, role = Role.Image) { onImage(ref) },
        )
    }
    if (refs.size == 1) {
        val ref = refs[0]
        val ratio = if (ref.width > 0 && ref.height > 0) (ref.width.toFloat() / ref.height).coerceIn(0.6f, 1.8f) else 1f
        tile(ref, modifier.width(220.dp).aspectRatio(ratio))
    } else {
        Column(modifier.widthIn(max = 244.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            refs.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { tile(it, Modifier.size(120.dp)) }
                }
            }
        }
    }
}

/** The model's reasoning summary: a header with a chevron, collapsed until tapped. */
@Composable
private fun ThinkingBlock(thinking: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        CollapsibleHeader(stringResource(R.string.thinking_title) + if (live) "…" else "", expanded) { expanded = !expanded }
        if (expanded) {
            Text(
                thinking,
                color = Ember.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 6.dp)
                    .background(Ember.surfaceLow, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(150), label = "chevron")
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Ember.label, style = MaterialTheme.typography.labelLarge)
        Icon(
            painterResource(R.drawable.ic_expand),
            contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
            tint = Ember.label,
            modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(rotation),
        )
    }
}

/**
 * The web pages an answer drew on: "Sources (n)", collapsed until tapped. Expanded, one row per page with its
 * site and title; a tap opens it in the browser, a long press copies the link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcesBlock(sources: List<WebSource>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 2.dp).animateContentSize()) {
        CollapsibleHeader(stringResource(R.string.sources_title, sources.size), expanded) { expanded = !expanded }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sources.forEach { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ember.surface)
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.open_link),
                                onClick = { openLink(context, source.url) },
                                onLongClick = { copyToClipboard(context, source.url, R.string.link_copied) },
                            )
                            .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.domain, color = Ember.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (source.title.isNotBlank() && !source.title.equals(source.domain, ignoreCase = true)) {
                                Text(
                                    source.title,
                                    color = Ember.userText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, tint = Ember.label, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

/** Opens a web page in the browser; only http(s) links are followed. */
fun openLink(context: Context, url: String) {
    if (!WebSources.isWebUrl(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** Three dots pulsing in turn while the answer has not started. */
@Composable
fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(a).background(Ember.label, CircleShape))
        }
    }
}
