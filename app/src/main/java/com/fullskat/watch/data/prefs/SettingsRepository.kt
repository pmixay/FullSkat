package com.fullskat.watch.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fullskat.watch.data.net.ServerEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val serverUrl: String,
    val token: String?,
    val deviceId: String,
    val model: String?,
    val serverModel: String?,
    val serverModels: List<String>,
    val serverName: String?,
    /** Reasoning effort chosen on the watch; null = whatever the server defaults to. */
    val effort: String?,
    val serverEffort: String?,
    val webSearch: Boolean,
    val haptics: Boolean,
) {
    val paired: Boolean get() = !token.isNullOrBlank()
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN_ENC = stringPreferencesKey("token_enc")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MODEL = stringPreferencesKey("model")
        val SERVER_MODEL = stringPreferencesKey("server_model")
        val SERVER_MODELS = stringPreferencesKey("server_models")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val EFFORT = stringPreferencesKey("effort")
        val SERVER_EFFORT = stringPreferencesKey("server_effort")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val HAPTICS = booleanPreferencesKey("haptics")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = ServerEndpoint.URL,
            // A token issued by a server other than the fixed one (older versions let the user type any address) is useless.
            token = p[Keys.TOKEN_ENC]?.takeIf { p[Keys.SERVER_URL] == ServerEndpoint.URL }?.let(KeystoreCrypto::decrypt),
            deviceId = p[Keys.DEVICE_ID].orEmpty(),
            model = p[Keys.MODEL],
            serverModel = p[Keys.SERVER_MODEL],
            serverModels = p[Keys.SERVER_MODELS]?.lines()?.filter { it.isNotBlank() }.orEmpty(),
            serverName = p[Keys.SERVER_NAME],
            effort = p[Keys.EFFORT],
            serverEffort = p[Keys.SERVER_EFFORT],
            webSearch = p[Keys.WEB_SEARCH] ?: true,
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

    suspend fun savePairing(token: String, serverName: String?, serverModel: String?, serverModels: List<String>, serverEffort: String?) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = ServerEndpoint.URL
            it[Keys.TOKEN_ENC] = KeystoreCrypto.encrypt(token)
            if (serverName != null) it[Keys.SERVER_NAME] = serverName
            if (serverEffort != null) it[Keys.SERVER_EFFORT] = serverEffort
            applyServerModels(it, serverModel, serverModels)
        }
    }

    /** Stores the server's default model and allowlist; drops a personal choice the server no longer offers. */
    suspend fun saveServerModels(serverModel: String?, serverModels: List<String>, serverEffort: String? = null) {
        context.dataStore.edit {
            if (serverEffort != null) it[Keys.SERVER_EFFORT] = serverEffort
            applyServerModels(it, serverModel, serverModels)
        }
    }

    private fun applyServerModels(prefs: MutablePreferences, serverModel: String?, serverModels: List<String>) {
        if (serverModel != null) prefs[Keys.SERVER_MODEL] = serverModel
        if (serverModels.isNotEmpty()) {
            prefs[Keys.SERVER_MODELS] = serverModels.joinToString("\n")
            val chosen = prefs[Keys.MODEL]
            if (chosen != null && chosen !in serverModels) prefs.remove(Keys.MODEL)
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

    suspend fun setEffort(effort: String?) = context.dataStore.edit {
        if (effort == null) it.remove(Keys.EFFORT) else it[Keys.EFFORT] = effort
    }

    suspend fun setWebSearch(enabled: Boolean) = context.dataStore.edit { it[Keys.WEB_SEARCH] = enabled }

    suspend fun setHaptics(enabled: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = enabled }
}
