package com.fullskat.watch.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class SpeechLang(val wire: String) { AUTO("auto"), RU("ru"), EN("en") }

data class Settings(
    val serverUrl: String,
    val token: String?,
    val deviceId: String,
    val model: String?,
    val serverModel: String?,
    val serverName: String?,
    val lang: SpeechLang,
    val haptics: Boolean,
) {
    val paired: Boolean get() = serverUrl.isNotBlank() && !token.isNullOrBlank()
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN_ENC = stringPreferencesKey("token_enc")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MODEL = stringPreferencesKey("model")
        val SERVER_MODEL = stringPreferencesKey("server_model")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val LANG = stringPreferencesKey("lang")
        val HAPTICS = booleanPreferencesKey("haptics")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = p[Keys.SERVER_URL].orEmpty(),
            token = p[Keys.TOKEN_ENC]?.let(KeystoreCrypto::decrypt),
            deviceId = p[Keys.DEVICE_ID].orEmpty(),
            model = p[Keys.MODEL],
            serverModel = p[Keys.SERVER_MODEL],
            serverName = p[Keys.SERVER_NAME],
            lang = p[Keys.LANG]?.let { v -> SpeechLang.entries.firstOrNull { it.wire == v } } ?: SpeechLang.AUTO,
            haptics = p[Keys.HAPTICS] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Stable per-install id; created on first use. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    suspend fun savePairing(serverUrl: String, token: String, serverName: String?, serverModel: String?) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = serverUrl.trimEnd('/')
            it[Keys.TOKEN_ENC] = KeystoreCrypto.encrypt(token)
            if (serverName != null) it[Keys.SERVER_NAME] = serverName
            if (serverModel != null) it[Keys.SERVER_MODEL] = serverModel
        }
    }

    suspend fun clearPairing() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN_ENC)
            it.remove(Keys.SERVER_NAME)
        }
    }

    suspend fun setModel(model: String?) = context.dataStore.edit {
        if (model == null) it.remove(Keys.MODEL) else it[Keys.MODEL] = model
    }

    suspend fun setLang(lang: SpeechLang) = context.dataStore.edit { it[Keys.LANG] = lang.wire }

    suspend fun setHaptics(enabled: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = enabled }
}
