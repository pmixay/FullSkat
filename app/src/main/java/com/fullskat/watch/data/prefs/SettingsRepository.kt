package com.fullskat.watch.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fullskat.watch.data.net.ModelEfforts
import com.fullskat.watch.data.net.ProviderInfo
import com.fullskat.watch.data.net.ServerEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
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
    /** Efforts each model accepts, as reported by the server (empty for a server that does not say). */
    val modelEfforts: Map<String, ModelEfforts>,
    /** Working providers with their models and the share of budget left, in the server's priority order. */
    val providers: List<ProviderInfo>,
    val webSearch: Boolean,
    val haptics: Boolean,
) {
    val paired: Boolean get() = !token.isNullOrBlank()

    /** The model the next question goes to. */
    val activeModel: String? get() = model ?: serverModel

    /** Efforts the active model accepts; null when the server did not say (all levels are then offered). */
    val supportedEfforts: List<String>? get() = activeModel?.let { modelEfforts[it]?.efforts }

    /** The effort the server applies to the active model when the watch sends none. */
    val defaultEffort: String?
        get() = activeModel?.let { m -> modelEfforts[m]?.default?.takeIf { d -> d.isNotBlank() } }
            ?: serverEffort?.takeIf { e -> e.isNotBlank() }

    /** The chosen effort if the active model accepts it, otherwise null (= server default). */
    val effectiveEffort: String?
        get() {
            val chosen = effort ?: return null
            val supported = supportedEfforts
            return if (supported == null || chosen in supported) chosen else null
        }
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
        val SERVER_MODEL_EFFORTS = stringPreferencesKey("server_model_efforts")
        val SERVER_PROVIDERS = stringPreferencesKey("server_providers")
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
            modelEfforts = p[Keys.SERVER_MODEL_EFFORTS]?.let(::decodeModelEfforts).orEmpty(),
            providers = p[Keys.SERVER_PROVIDERS]?.let(::decodeProviders).orEmpty(),
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

    suspend fun savePairing(
        token: String,
        serverName: String?,
        serverModel: String?,
        serverModels: List<String>,
        serverEffort: String?,
        modelEfforts: Map<String, ModelEfforts> = emptyMap(),
        providers: List<ProviderInfo> = emptyList(),
    ) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = ServerEndpoint.URL
            it[Keys.TOKEN_ENC] = KeystoreCrypto.encrypt(token)
            if (serverName != null) it[Keys.SERVER_NAME] = serverName
            if (serverEffort != null) it[Keys.SERVER_EFFORT] = serverEffort
            applyServerModels(it, serverModel, serverModels, modelEfforts, providers)
        }
    }

    /** Stores the server's default model and allowlist; drops a personal choice the server no longer offers. */
    suspend fun saveServerModels(
        serverModel: String?,
        serverModels: List<String>,
        serverEffort: String? = null,
        modelEfforts: Map<String, ModelEfforts> = emptyMap(),
        providers: List<ProviderInfo> = emptyList(),
    ) {
        context.dataStore.edit {
            if (serverEffort != null) it[Keys.SERVER_EFFORT] = serverEffort
            applyServerModels(it, serverModel, serverModels, modelEfforts, providers)
        }
    }

    private fun applyServerModels(
        prefs: MutablePreferences,
        serverModel: String?,
        serverModels: List<String>,
        modelEfforts: Map<String, ModelEfforts>,
        providers: List<ProviderInfo>,
    ) {
        if (serverModel != null) prefs[Keys.SERVER_MODEL] = serverModel
        if (serverModels.isNotEmpty()) {
            prefs[Keys.SERVER_MODELS] = serverModels.joinToString("\n")
            prefs[Keys.SERVER_MODEL_EFFORTS] = encodeModelEfforts(modelEfforts)
            prefs[Keys.SERVER_PROVIDERS] = encodeProviders(providers)
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

private fun encodeModelEfforts(all: Map<String, ModelEfforts>): String = JSONObject().apply {
    all.forEach { (model, e) ->
        put(model, JSONObject().put("e", JSONArray(e.efforts)).put("d", e.default ?: ""))
    }
}.toString()

private fun decodeModelEfforts(text: String): Map<String, ModelEfforts> = runCatching {
    val o = JSONObject(text)
    o.keys().asSequence().associateWith { model ->
        val e = o.getJSONObject(model)
        val levels = e.optJSONArray("e")
        ModelEfforts(
            (0 until (levels?.length() ?: 0)).map { levels!!.getString(it) },
            e.optString("d").ifBlank { null },
        )
    }
}.getOrDefault(emptyMap())

private fun encodeProviders(all: List<ProviderInfo>): String = JSONArray().apply {
    all.forEach { p ->
        put(
            JSONObject()
                .put("id", p.id)
                .put("m", JSONArray(p.models))
                .apply { if (p.usageRemaining != null) put("r", p.usageRemaining.toDouble()) }
                .put("l", p.usageLabel),
        )
    }
}.toString()

private fun decodeProviders(text: String): List<ProviderInfo> = runCatching {
    val a = JSONArray(text)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val models = o.optJSONArray("m")
        ProviderInfo(
            id = o.getString("id"),
            models = (0 until (models?.length() ?: 0)).map { models!!.getString(it) },
            usageRemaining = if (o.has("r")) o.getDouble("r").toFloat() else null,
            usageLabel = o.optString("l"),
        )
    }
}.getOrDefault(emptyList())
