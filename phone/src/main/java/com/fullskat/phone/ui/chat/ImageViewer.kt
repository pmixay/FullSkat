package com.fullskat.phone.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.ui.rich.LocalAttachments
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R

/**
 * An attached image on the whole screen: the thumbnail first, the full image once it is loaded. Pinch to zoom
 * (up to 6x) around the fingers, drag to pan, double tap to zoom in or back out.
 */
@Composable
fun ImageViewer(ref: AttachmentRef, onClose: () -> Unit) {
    val repo = LocalAttachments.current
    var bitmap by remember(ref.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(ref.id) { mutableStateOf(true) }
    LaunchedEffect(ref.id, repo) {
        if (repo != null) {
            repo.bitmap(ref, thumb = true)?.let { bitmap = it }
            repo.bitmap(ref, thumb = false)?.let { bitmap = it }
        }
        loading = false
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    // keep the point under the fingers where it is while zooming
                    val o = centroid - center - (centroid - center - offset) * (next / scale) + pan
                    scale = next
                    offset = clamp(o, next)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        scale = 2.5f
                        offset = clamp((center - p) * 1.5f, 2.5f)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            b != null -> Image(
                b.asImageBitmap(),
                contentDescription = stringResource(R.string.attached_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            loading -> CircularProgressIndicator(color = Ember.accent)
            else -> Text(stringResource(R.string.image_unavailable), color = Ember.label, style = MaterialTheme.typography.bodyLarge)
        }
        FilledIconButton(
            onClick = onClose,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0x99000000), contentColor = Color.White),
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
        }
    }
}
