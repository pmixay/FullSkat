package com.fullskat.watch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.fullskat.watch.ui.FullSkatRoot
import kotlinx.coroutines.flow.MutableStateFlow

/** What an incoming intent asks the UI to do. */
enum class LaunchRequest {
    NEW_DIALOG_AND_LISTEN;

    companion object {
        private val LISTEN_ACTIONS = setOf(
            MainActivity.ACTION_LISTEN,
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND,
            "android.speech.action.WEB_SEARCH",
        )

        /**
         * The request of [intent], if it is a fresh one: not when the activity is [restored] (its intent is the old one)
         * and not when it was reopened from the recents screen, which replays the intent that first started it.
         */
        fun from(intent: Intent?, restored: Boolean): LaunchRequest? {
            intent ?: return null
            if (restored || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val listen = intent.action in LISTEN_ACTIONS || intent.getBooleanExtra(MainActivity.EXTRA_LISTEN, false)
            return if (listen) NEW_DIALOG_AND_LISTEN else null
        }
    }
}

class MainActivity : ComponentActivity() {
    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A recreated activity (a system setting changed, or reopened after its process was killed) gets its old
        // intent again: that one was handled already and must not start another dialog.
        launchRequests.value = LaunchRequest.from(intent, restored = savedInstanceState != null)
        setContent {
            FullSkatRoot(
                container = FullSkatApp.container(this),
                launchRequests = launchRequests,
                onLaunchRequestConsumed = { launchRequests.value = null },
            )
        }
    }

    // Live updates from the phone only while the app is on screen: nothing runs in the background.
    override fun onStart() {
        super.onStart()
        FullSkatApp.container(this).core.events.start()
    }

    override fun onStop() {
        FullSkatApp.container(this).core.events.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchRequest.from(intent, restored = false)?.let { launchRequests.value = it }
    }

    companion object {
        const val ACTION_LISTEN = "com.fullskat.watch.action.LISTEN"
        const val EXTRA_LISTEN = "com.fullskat.watch.extra.LISTEN"
    }
}
