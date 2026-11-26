package com.fullskat.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.fullskat.phone.ui.PhoneRoot
import com.fullskat.phone.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = PhoneApp.container(this)
        if (savedInstanceState == null) handleShare(intent)
        setContent { PhoneRoot(app) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Images or text shared from another app start a new dialog with them in the composer. */
    private fun handleShare(intent: Intent?) {
        val streams: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        val images = streams.filter { contentResolver.getType(it)?.startsWith("image/") ?: true }.take(ChatViewModel.MAX_IMAGES)
        if (images.isEmpty() && text.isBlank()) return
        val app = PhoneApp.container(this)
        lifecycleScope.launch {
            // The permission to read what was shared lasts only as long as this activity: keep copies.
            val copies = withContext(Dispatchers.IO) { images.mapIndexedNotNull { i, uri -> copyShared(this@MainActivity, uri, i) } }
            app.share(text, copies)
        }
        setIntent(Intent(this, MainActivity::class.java)) // a recreated activity must not share it again
    }
}

/** Copies a shared image into cache/camera/ (served by our FileProvider); null when it cannot be read. */
private fun copyShared(context: Context, uri: Uri, index: Int): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "shared_${System.currentTimeMillis()}_$index")
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}.getOrNull()
