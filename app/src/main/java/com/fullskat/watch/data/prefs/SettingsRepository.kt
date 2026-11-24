package com.fullskat.watch.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.fullskat.core.net.ProviderInfo
import com.fullskat.core.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private val Context.watchSettings by preferencesDataStore(name = "watch_settings")

/**
 * What the watch screens show and use: the account's shared preferences and the server's offer (from the core
 * session, synced with the phone) plus the settings that belong to this watch alone (vibration).
 */
data class Settings(
    val paired: Boolean,
    val userName: String?,
    val serverName: String?,
    /** The user's chosen model; null = the server's default ([serverModel]). */
    val model: String?,
    val serverModel: String?,
    val serverModels: List<String>,
    val providers: List<ProviderInfo>,
    val supportedEfforts: List<String>?,
    val defaultEffort: String?,
    val effectiveEffort: String?,
    val webSearch: Boolean,
    val figures: Boolean,
    val haptics: Boolean,
)

class SettingsRepository(private val context: Context, private val session: SessionStore) {
    private object Keys {
        val HAPTICS = booleanPreferencesKey("haptics")
    }

    val settings: Flow<Settings> = combine(session.state, context.watchSettings.data) { s, p ->
        Settings(
            paired = s.signedIn,
            userName = s.user?.name,
            serverName = s.serverName,
            model = s.prefs.model?.takeIf { s.info.models.isEmpty() || it in s.info.models },
            serverModel = s.info.model,
            serverModels = s.info.models,
            providers = s.info.providers,
            supportedEfforts = s.supportedEfforts,
            defaultEffort = s.defaultEffort,
            effectiveEffort = s.effectiveEffort,
            webSearch = s.webSearch,
            figures = s.figures,
            haptics = p[Keys.HAPTICS] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setHaptics(enabled: Boolean) = context.watchSettings.edit { it[Keys.HAPTICS] = enabled }
}
