package com.fullskat.phone

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.fullskat.core.FullSkatCore
import com.fullskat.core.ServerEndpoint
import com.fullskat.core.net.DeviceKind
import com.fullskat.phone.chat.ChatTurns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/** Text and images shared into the app, waiting in the composer of the dialog made for them. */
data class SharedDraft(val dialogId: String, val text: String, val images: List<Uri>)

/** Hand-rolled dependency container: the core (API, synced dialogs, chat, account) plus what only the phone has. */
class PhoneContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val core = FullSkatCore(appContext, ServerEndpoint.URL, DeviceKind.PHONE)
    val turns = ChatTurns(core)

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The account is gone on this device (signed out here, or unlinked from another device): back to onboarding. */
    val signedOut: SharedFlow<Unit> = _signedOut.asSharedFlow()

    /** Something shared into the app; the app opens its dialog, whose chat screen takes it (and clears this). */
    val pendingShare = MutableStateFlow<SharedDraft?>(null)

    init {
        core.sync.onSignedOut = {
            turns.stopAll()
            core.attachments.clearCache()
            _signedOut.tryEmit(Unit)
        }
        // Photos taken or shared for a message are uploaded within minutes; day-old copies are leftovers.
        core.scope.launch(Dispatchers.IO) {
            val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
            File(appContext.cacheDir, "camera").listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
        }
    }

    /** The name other devices see for this phone: the one set in system settings, else the model. */
    val deviceName: String
        get() = runCatching { Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val foreground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** After sign-up the live stream can start (it stops by itself while there is no token). */
    fun onSignedIn() {
        if (foreground) core.events.start()
    }

    /** Starts a new dialog with what was shared; the chat screen picks it up from [pendingShare]. */
    fun share(text: String, images: List<Uri>) {
        core.scope.launch { pendingShare.value = SharedDraft(core.sync.newDialog(), text, images) }
    }

    internal fun onForeground() {
        core.events.start()
        core.scope.launch { core.account.refresh() } // model offer, usage, name: whatever changed meanwhile
    }

    internal fun onBackground() = core.events.stop()
}

class PhoneApp : Application() {
    lateinit var container: PhoneContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = PhoneContainer(this)
        // Live events only while the app is on screen: nothing keeps a connection open in the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
    }

    companion object {
        fun container(context: Context): PhoneContainer = (context.applicationContext as PhoneApp).container
    }
}
