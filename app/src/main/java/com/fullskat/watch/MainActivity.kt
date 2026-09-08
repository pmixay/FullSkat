package com.fullskat.watch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.fullskat.watch.ui.FullSkatRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** What an incoming intent asks the UI to do. */
enum class LaunchRequest { NEW_DIALOG_AND_LISTEN }

class MainActivity : ComponentActivity() {
    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        launchRequests.value = requestFrom(intent)
        setContent {
            FullSkatRoot(
                container = FullSkatApp.container(this),
                launchRequests = launchRequests,
                onLaunchRequestConsumed = { launchRequests.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestFrom(intent)?.let { launchRequests.value = it }
    }

    private fun requestFrom(intent: Intent?): LaunchRequest? {
        intent ?: return null
        val listenAction = intent.action in setOf(
            ACTION_LISTEN,
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND,
            "android.speech.action.WEB_SEARCH",
        )
        return if (listenAction || intent.getBooleanExtra(EXTRA_LISTEN, false)) LaunchRequest.NEW_DIALOG_AND_LISTEN else null
    }

    companion object {
        const val ACTION_LISTEN = "com.fullskat.watch.action.LISTEN"
        const val EXTRA_LISTEN = "com.fullskat.watch.extra.LISTEN"
    }
}
