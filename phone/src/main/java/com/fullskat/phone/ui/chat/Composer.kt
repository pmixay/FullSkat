package com.fullskat.phone.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.fullskat.core.attachments.AttachmentRepository
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.chat.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** What the round button at the end of the composer does right now. */
enum class ComposerAction { MIC, LISTENING, SEND, STOP }

/**
 * The bottom bar of a chat: picked images as chips, a multi-line field, the attach menu (gallery, camera) and one
 * round button that is the mic, the send button or the stop button depending on the state. While images upload,
 * they show here with their progress and the field is locked.
 */
@Composable
fun Composer(
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    images: List<Uri>,
    onRemoveImage: (Uri) -> Unit,
    uploading: Turn.Uploading?,
    action: ComposerAction,
    level: Float,
    canAttach: Boolean,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (uploading != null) {
            val total = uploading.draft.images.size.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { (uploading.done + 0.5f) / total },
                modifier = Modifier.fillMaxWidth(),
                color = Ember.accent,
                trackColor = Ember.surfaceHigh,
            )
        }
        banner()
        val shown = uploading?.draft?.images ?: images
        if (shown.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.forEachIndexed { i, uri ->
                    val state = when {
                        uploading == null -> ChipState.IDLE
                        i < uploading.done -> ChipState.DONE
                        i == uploading.done -> ChipState.UPLOADING
                        else -> ChipState.WAITING
                    }
                    ImageChip(uri, state, onRemove = { onRemoveImage(uri) })
                }
            }
        }
        if (uploading != null) {
            Text(
                stringResource(R.string.uploading_images, uploading.done + 1, uploading.draft.images.size),
                style = MaterialTheme.typography.labelMedium,
                color = Ember.label,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AttachButton(enabled = canAttach && uploading == null, onGallery = onGallery, onCamera = onCamera)
            TextField(
                value = input,
                onValueChange = onInput,
                enabled = uploading == null,
                placeholder = { Text(stringResource(if (action == ComposerAction.LISTENING) R.string.listening else R.string.message_hint)) },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ember.surface,
                    unfocusedContainerColor = Ember.surface,
                    disabledContainerColor = Ember.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Ember.accent,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            ActionButton(action, level, onAction, Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.padding(bottom = 2.dp).size(48.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.attach), tint = if (enabled) Ember.accent else Ember.label)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_gallery)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_gallery), contentDescription = null) },
                onClick = { menu = false; onGallery() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_camera)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                onClick = { menu = false; onCamera() },
            )
        }
    }
}

@Composable
private fun ActionButton(action: ComposerAction, level: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ring by animateFloatAsState(if (action == ComposerAction.LISTENING) level else 0f, tween(90), label = "level")
    val accent = Ember.accent
    Box(
        modifier
            .size(52.dp)
            .drawBehind {
                // While listening a halo around the button follows the voice.
                if (action == ComposerAction.LISTENING) drawCircle(accent.copy(alpha = 0.18f + 0.3f * ring), radius = size.minDimension / 2f * (0.9f + 0.25f * ring))
            },
        contentAlignment = Alignment.Center,
    ) {
        val stop = action == ComposerAction.STOP
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (stop) Ember.surfaceHigh else Ember.accent,
                contentColor = if (stop) Ember.accent else Ember.onAccent,
            ),
        ) {
            when (action) {
                ComposerAction.MIC -> Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.mic))
                ComposerAction.LISTENING -> Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.mic_stop))
                ComposerAction.SEND -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                ComposerAction.STOP -> Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.stop))
            }
        }
    }
}

private enum class ChipState { IDLE, WAITING, UPLOADING, DONE }

@Composable
private fun ImageChip(uri: Uri, state: ChipState, onRemove: () -> Unit) {
    Box(Modifier.size(76.dp)) {
        UriThumbnail(
            uri,
            Modifier
                .padding(top = 6.dp, end = 6.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Ember.outline, RoundedCornerShape(12.dp)),
        )
        when (state) {
            ChipState.IDLE -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Ember.surfaceHigh)
                    .border(1.dp, Ember.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_image), tint = Ember.assistantText, modifier = Modifier.size(14.dp))
                }
            }
            ChipState.UPLOADING, ChipState.WAITING -> Box(
                Modifier.padding(top = 6.dp, end = 6.dp).fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (state == ChipState.UPLOADING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Ember.accent)
            }
            ChipState.DONE -> Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).background(Ember.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Ember.onAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A small preview of a picked or shot image (upright, decoded at thumbnail size); a quiet box until it loads. */
@Composable
fun UriThumbnail(uri: Uri, modifier: Modifier = Modifier, maxSide: Int = 256) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bitmap = decodeThumbnail(context, uri, maxSide) }
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), contentDescription = stringResource(R.string.attached_image), modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Ember.surfaceHigh))
}

private suspend fun decodeThumbnail(context: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@runCatching null
        val rotation = resolver.openInputStream(uri)?.use { AttachmentRepository.exifRotation(ExifInterface(it)) } ?: 0
        AttachmentRepository.scale(decoded, maxSide, rotation)
    }.getOrNull()
}
