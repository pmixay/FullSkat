package com.fullskat.watch.assistant

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.fullskat.watch.MainActivity

/**
 * Registered as a digital assistant. The system binds this when FullSkat is chosen in
 * Settings → Apps → Default apps → Digital assistant app. Nothing runs in the background:
 * the service only exists so the side-key / assist gesture can reach [FullSkatSession].
 */
class FullSkatVoiceInteractionService : VoiceInteractionService() {
    companion object {
        /** True when FullSkat is the current assistant on this watch. */
        fun isDefaultAssistant(context: Context): Boolean {
            val current = Settings.Secure.getString(context.contentResolver, "voice_interaction_service") ?: return false
            val self = ComponentName(context, FullSkatVoiceInteractionService::class.java).flattenToString()
            return current == self
        }

        /** Best-effort intent to the system screen where the assistant app is chosen. */
        fun assistantSettingsIntent(): List<Intent> = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }
}

class FullSkatSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FullSkatSession(this)
}

/**
 * Assist gesture arrived. We do not draw any session UI: the activity is the UI, so we
 * launch it with the "new dialog + listen" action and immediately hide the session.
 */
@SuppressLint("WearRecents") // NEW_TASK is mandatory when a service starts an activity
class FullSkatSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startAssistantActivity(intent) }.onFailure { context.startActivity(intent) }
        hide()
    }
}
