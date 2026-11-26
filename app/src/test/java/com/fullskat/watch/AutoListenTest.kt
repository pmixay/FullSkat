package com.fullskat.watch

import android.content.Intent
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fullskat.watch.ui.chat.AutoListen
import com.fullskat.watch.ui.chat.StopListeningWhenLeaving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watch must start listening only when asked: once per chat opened from the mic, never again by itself. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [36])
class AutoListenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun comingBackFromAFigureDoesNotListenAgain() {
        var starts = 0
        var screen by mutableStateOf("chat")
        compose.setContent {
            // what the navigation host does with each back-stack entry
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(screen) {
                if (screen == "chat") AutoListen("d1", autoListen = true, busy = false) { starts++ } else BasicText("figure")
            }
        }
        compose.waitForIdle()
        assertEquals(1, starts)
        screen = "figure"
        compose.waitForIdle()
        screen = "chat" // back button
        compose.waitForIdle()
        assertEquals(1, starts)
    }

    @Test fun aRecreatedScreenDoesNotListenAgain() {
        var starts = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AutoListen("d1", autoListen = true, busy = false) { starts++ } }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(1, starts)
    }

    @Test fun chatsOpenedFromTheListOrWhileBusyDoNotListen() {
        var starts = 0
        compose.setContent {
            AutoListen("d1", autoListen = false, busy = false) { starts++ }
            AutoListen("d2", autoListen = true, busy = true) { starts++ }
        }
        compose.waitForIdle()
        assertEquals(0, starts)
    }

    @Test fun leavingTheChatWhileListeningStopsTheMicrophone() {
        var stops = 0
        var screen by mutableStateOf("chat")
        var listening by mutableStateOf(true)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(screen) {
                if (screen == "chat") StopListeningWhenLeaving(listening) { stops++ } else BasicText("dialogs")
            }
        }
        compose.waitForIdle()
        assertEquals(0, stops)
        screen = "dialogs" // back button while the listening overlay is up
        compose.waitForIdle()
        assertEquals(1, stops)
        screen = "chat"
        listening = false
        compose.waitForIdle()
        screen = "dialogs" // leaving while answering or idle stops nothing
        compose.waitForIdle()
        assertEquals(1, stops)
    }

    @Test fun theAppGoingToTheBackgroundWhileListeningStopsTheMicrophone() {
        var stops = 0
        var listening by mutableStateOf(true)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { StopListeningWhenLeaving(listening) { stops++ } }
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        assertEquals(1, stops) // screen off, another app
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            listening = false
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        assertEquals(1, stops) // not listening: nothing to stop
    }

    @Test fun onlyFreshLaunchIntentsStartADialog() {
        val assist = Intent(Intent.ACTION_ASSIST)
        assertEquals(LaunchRequest.NEW_DIALOG_AND_LISTEN, LaunchRequest.from(assist, restored = false))
        assertEquals(LaunchRequest.NEW_DIALOG_AND_LISTEN, LaunchRequest.from(Intent(MainActivity.ACTION_LISTEN), restored = false))
        assertEquals(LaunchRequest.NEW_DIALOG_AND_LISTEN, LaunchRequest.from(Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_LISTEN, true), restored = false))
        assertNull(LaunchRequest.from(assist, restored = true)) // the activity was recreated: its intent is the old one
        assertNull(LaunchRequest.from(Intent(assist).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), restored = false)) // from recents
        assertNull(LaunchRequest.from(Intent(Intent.ACTION_MAIN), restored = false))
        assertNull(LaunchRequest.from(null, restored = false))
    }
}
