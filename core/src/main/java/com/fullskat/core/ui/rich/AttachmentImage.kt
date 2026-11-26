package com.fullskat.core.ui.rich

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.fullskat.core.attachments.AttachmentRepository
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.ui.theme.Ember

/** Where attachment images come from; each app provides its core's repository. */
val LocalAttachments = staticCompositionLocalOf<AttachmentRepository?> { null }

/** An attached image (a thumbnail when [thumb]), loaded from the cache or the server; a quiet box until then. */
@Composable
fun AttachmentImage(ref: AttachmentRef, thumb: Boolean, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val repo = LocalAttachments.current
    var bitmap by remember(ref.id, thumb) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref.id, thumb, repo) {
        if (repo != null) bitmap = repo.bitmap(ref, thumb)
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Ember.surfaceHigh))
    }
}
