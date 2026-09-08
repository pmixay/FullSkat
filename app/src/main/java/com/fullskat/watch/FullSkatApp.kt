package com.fullskat.watch

import android.app.Application
import android.content.Context
import com.fullskat.watch.audio.AudioCapture
import com.fullskat.watch.data.db.AppDatabase
import com.fullskat.watch.data.net.ApiClient
import com.fullskat.watch.data.net.ListenClient
import com.fullskat.watch.data.prefs.SettingsRepository
import com.fullskat.watch.domain.AssistantTurn

/** Hand-rolled dependency container: the app is small enough that a DI framework would only add APK size. */
class AppContainer(val appContext: Context) {
    private val context: Context get() = appContext
    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val api: ApiClient by lazy { ApiClient() }
    val listenClient: ListenClient by lazy { ListenClient(api.http) }
    val audioCapture: AudioCapture by lazy { AudioCapture() }

    fun newTurn(): AssistantTurn = AssistantTurn(context, db.dialogDao(), settings, api, listenClient, audioCapture)
}

class FullSkatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as FullSkatApp).container
    }
}
