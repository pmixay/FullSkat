package com.fullskat.core.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fullskat.core.net.Device
import com.fullskat.core.net.DeviceKind
import com.fullskat.core.net.JsonModels
import com.fullskat.core.net.ModelInfo
import com.fullskat.core.net.Prefs
import com.fullskat.core.net.Session
import com.fullskat.core.net.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

private val Context.sessionStore by preferencesDataStore(name = "fullskat_session")

/** What this device knows about its account. [token] is null when it is not signed in / linked. */
data class SessionState(
    val token: String?,
    val deviceId: String,
    val serverName: String?,
    val user: User?,
    val device: Device?,
    val info: ModelInfo,
    val prefs: Prefs,
) {
    val signedIn: Boolean get() = !token.isNullOrBlank()

    /** The model the next question goes to: the user's choice if the server still offers it, else the default. */
    val activeModel: String?
        get() = prefs.model?.takeIf { info.models.isEmpty() || it in info.models } ?: info.model

    /** Efforts the active model accepts; null when the server did not say (all levels are then offered). */
    val supportedEfforts: List<String>? get() = activeModel?.let { info.modelEfforts[it]?.efforts }

    /** The effort the server applies to the active model when none is chosen. */
    val defaultEffort: String?
        get() = activeModel?.let { m -> info.modelEfforts[m]?.default?.takeIf { it.isNotBlank() } } ?: info.effort

    /** The chosen effort if the active model accepts it, otherwise null (= server default). */
    val effectiveEffort: String?
        get() {
            val chosen = prefs.effort ?: return null
            val supported = supportedEfforts
            return if (supported == null || chosen in supported) chosen else null
        }

    val webSearch: Boolean get() = info.webSearch && prefs.webSearch != false
    val figures: Boolean get() = info.figures && prefs.figures != false
}

/**
 * The session in a DataStore: the token (AES-GCM encrypted with a Keystore key, never in plain text), who the
 * user and this device are, the server's model offer and the user's shared preferences as last seen.
 */
class SessionStore(private val context: Context, private val kind: String = DeviceKind.OTHER) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token_enc")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val USER = stringPreferencesKey("user")
        val DEVICE = stringPreferencesKey("device")
        val INFO = stringPreferencesKey("model_info")
        val PREFS = stringPreferencesKey("prefs")
    }

    val state: Flow<SessionState> = context.sessionStore.data.map { p ->
        SessionState(
            token = p[Keys.TOKEN]?.let { runCatching { KeystoreCrypto.decrypt(it) }.getOrNull() },
            deviceId = p[Keys.DEVICE_ID].orEmpty(),
            serverName = p[Keys.SERVER_NAME],
            user = p[Keys.USER]?.let { runCatching { JsonModels.user(JSONObject(it)) }.getOrNull() },
            device = p[Keys.DEVICE]?.let { runCatching { JsonModels.device(JSONObject(it)) }.getOrNull() },
            info = p[Keys.INFO]?.let { runCatching { JsonModels.modelInfo(JSONObject(it)) }.getOrNull() } ?: ModelInfo(),
            prefs = p[Keys.PREFS]?.let { runCatching { JsonModels.prefs(JSONObject(it)) }.getOrNull() } ?: Prefs(),
        )
    }

    suspend fun current(): SessionState = state.first()

    suspend fun token(): String? = current().token

    /** Stable per-install id; created on first use. */
    suspend fun deviceId(): String {
        val existing = context.sessionStore.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        context.sessionStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    suspend fun save(session: Session) {
        context.sessionStore.edit {
            if (session.token.isNotBlank()) it[Keys.TOKEN] = KeystoreCrypto.encrypt(session.token)
            session.serverName?.let { name -> it[Keys.SERVER_NAME] = name }
            if (session.user.id.isNotBlank()) it[Keys.USER] = JsonModels.userJson(session.user)
            if (session.device.id.isNotBlank()) it[Keys.DEVICE] = JsonModels.deviceJson(session.device)
            it[Keys.INFO] = JsonModels.modelInfoJson(session.info)
        }
    }

    suspend fun saveUser(user: User) = context.sessionStore.edit { it[Keys.USER] = JsonModels.userJson(user) }

    suspend fun saveInfo(info: ModelInfo) = context.sessionStore.edit { it[Keys.INFO] = JsonModels.modelInfoJson(info) }

    suspend fun savePrefs(prefs: Prefs) = context.sessionStore.edit { it[Keys.PREFS] = JsonModels.prefsJson(prefs).toString() }

    /** Forgets the account on this device (keeps the device id, so linking again reuses the device record). */
    suspend fun clear() = context.sessionStore.edit {
        it.remove(Keys.TOKEN)
        it.remove(Keys.USER)
        it.remove(Keys.DEVICE)
        it.remove(Keys.PREFS)
    }

    val deviceKind: String get() = kind
}
