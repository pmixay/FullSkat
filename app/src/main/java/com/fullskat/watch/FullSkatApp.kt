package com.fullskat.watch

import android.app.Application
import android.content.Context
import com.fullskat.core.FullSkatCore
import com.fullskat.core.ServerEndpoint
import com.fullskat.core.net.DeviceKind
import com.fullskat.watch.audio.AudioCapture
import com.fullskat.watch.data.prefs.SettingsRepository
import com.fullskat.watch.domain.AssistantTurn
import com.fullskat.watch.speech.LocalSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency container: the app is small enough that a DI framework would only add APK size. */
class AppContainer(val appContext: Context) {
    private val context: Context get() = appContext

    /** Account, synced dialogs, chat and attachments, shared with the phone app's code. */
    val core: FullSkatCore by lazy { FullSkatCore(context, ServerEndpoint.URL, DeviceKind.WATCH) }
    val settings: SettingsRepository by lazy { SettingsRepository(context, core.session) }
    val speech: LocalSpeech by lazy { LocalSpeech(context) }
    val audioCapture: AudioCapture by lazy { AudioCapture() }

    fun newTurn(): AssistantTurn = AssistantTurn(context, core, settings, speech, audioCapture)
}

class FullSkatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start loading the speech model as early as possible, in parallel with the activity and
        // Compose start-up, so a cold start after the app was swiped away is not waiting on it.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { container.speech.warmUp() }
    }

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as FullSkatApp).container
    }
}
