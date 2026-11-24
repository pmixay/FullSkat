package com.fullskat.core.net

import org.json.JSONArray
import org.json.JSONObject

/** JSON of the v2 protocol to the models in ApiModels.kt (lenient: missing fields take defaults). */
object JsonModels {
    fun user(o: JSONObject) = User(o.optString("id"), o.optString("name"), o.optLong("created_at"))

    fun device(o: JSONObject) = Device(
        id = o.optString("id"),
        kind = o.optString("kind", DeviceKind.OTHER),
        name = o.optString("name"),
        createdAt = o.optLong("created_at"),
        lastSeen = o.optLong("last_seen"),
        current = o.optBoolean("current", false),
    )

    fun <T> list(a: JSONArray?, item: (JSONObject) -> T): List<T> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(item) }
    }

    fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
    }

    fun modelInfo(o: JSONObject) = ModelInfo(
        model = o.optString("model").ifBlank { null },
        models = strings(o.optJSONArray("models")),
        effort = o.optString("effort").ifBlank { null },
        modelEfforts = modelEfforts(o.optJSONObject("model_efforts")),
        providers = providers(o.optJSONObject("providers")),
        webSearch = o.optBoolean("web_search", true),
        figures = o.optBoolean("figures", true),
    )

    fun modelEfforts(all: JSONObject?): Map<String, ModelEfforts> {
        if (all == null) return emptyMap()
        return all.keys().asSequence().associateWith { model ->
            val o = all.optJSONObject(model)
            ModelEfforts(strings(o?.optJSONArray("efforts")), o?.optString("default")?.ifBlank { null })
        }
    }

    /** Working providers in server order (it lists them by priority). */
    fun providers(all: JSONObject?): List<ProviderInfo> {
        if (all == null) return emptyList()
        return all.keys().asSequence().mapNotNull { id ->
            val o = all.optJSONObject(id) ?: return@mapNotNull null
            if (!o.optBoolean("ok", false)) return@mapNotNull null
            val usage = o.optJSONObject("usage")
            ProviderInfo(
                id = id,
                models = strings(o.optJSONArray("models")),
                usageRemaining = usage?.takeIf { !it.isNull("remaining") }?.optDouble("remaining")?.toFloat(),
                usageLabel = usage?.optString("label").orEmpty(),
            )
        }.toList()
    }

    /** A sign-up, linked status or /v1/me body; [token] fills in when the body has none (/v1/me). */
    fun session(o: JSONObject, firstSetupDefault: Boolean, token: String = ""): Session = Session(
        token = o.optString("token").ifBlank { token },
        serverName = o.optString("server_name").ifBlank { null },
        user = o.optJSONObject("user")?.let(::user) ?: User("", ""),
        device = o.optJSONObject("device")?.let(::device) ?: Device("", DeviceKind.OTHER, ""),
        info = modelInfo(o),
        firstSetup = o.optBoolean("first_setup", firstSetupDefault),
    )

    fun prefs(o: JSONObject?): Prefs {
        if (o == null) return Prefs()
        return Prefs(
            model = o.optString("model").takeIf { !o.isNull("model") && it.isNotBlank() },
            effort = o.optString("effort").takeIf { !o.isNull("effort") && it.isNotBlank() },
            webSearch = if (o.has("web_search") && !o.isNull("web_search")) o.optBoolean("web_search") else null,
            figures = if (o.has("figures") && !o.isNull("figures")) o.optBoolean("figures") else null,
        )
    }

    fun prefsJson(p: Prefs): JSONObject = JSONObject().apply {
        p.model?.let { put("model", it) }
        p.effort?.let { put("effort", it) }
        p.webSearch?.let { put("web_search", it) }
        p.figures?.let { put("figures", it) }
    }

    fun attachment(o: JSONObject) = AttachmentRef(o.optString("id"), o.optString("mime", "image/jpeg"), o.optInt("width"), o.optInt("height"))

    fun attachmentsJson(list: List<AttachmentRef>): String {
        if (list.isEmpty()) return ""
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("mime", it.mime).put("width", it.width).put("height", it.height)) }
        return a.toString()
    }

    fun attachmentsFrom(text: String): List<AttachmentRef> {
        if (text.isBlank()) return emptyList()
        return runCatching { list(JSONArray(text), ::attachment) }.getOrDefault(emptyList())
    }

    fun dialog(o: JSONObject) = DialogDto(
        id = o.optString("id"),
        title = o.optString("title"),
        createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"),
        deleted = o.optBoolean("deleted", false),
        seq = o.optLong("seq"),
    )

    fun message(o: JSONObject) = MessageDto(
        id = o.optString("id"),
        dialogId = o.optString("dialog_id"),
        role = o.optString("role", Roles.USER),
        content = o.optString("content"),
        thinking = o.optString("thinking"),
        sources = WebSources.parse(o.optJSONArray("sources")),
        attachments = list(o.optJSONArray("attachments"), ::attachment),
        model = o.optString("model").ifBlank { null },
        status = o.optString("status", MessageStatus.DONE),
        error = o.optString("error").ifBlank { null },
        createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"),
        deleted = o.optBoolean("deleted", false),
        seq = o.optLong("seq"),
    )

    fun syncPage(o: JSONObject, since: Long) = SyncPage(
        dialogs = list(o.optJSONArray("dialogs"), ::dialog),
        messages = list(o.optJSONArray("messages"), ::message),
        prefs = if (o.has("prefs") && !o.isNull("prefs")) prefs(o.optJSONObject("prefs")) else null,
        seq = o.optLong("seq", since),
        more = o.optBoolean("more", false),
    )

    fun chatEvent(event: String, data: String): ChatEvent? = runCatching {
        val o = JSONObject(data)
        when (event) {
            "delta" -> ChatEvent.Delta(o.optString("text"))
            "thinking" -> ChatEvent.Thinking(o.optString("text"))
            "done" -> ChatEvent.Done(
                text = o.optString("text"),
                model = o.optString("model").ifBlank { null },
                thinking = o.optString("thinking"),
                sources = WebSources.parse(o.optJSONArray("sources")),
                message = o.optJSONObject("message")?.let(::message),
                seq = o.optLong("seq"),
            )
            "error" -> ChatEvent.Error(o.optString("error", "error"), o.optString("message"), o.optInt("retry_after", 0))
            else -> null
        }
    }.getOrElse {
        ApiClient.warn("Bad SSE payload for $event: ${data.take(200)}")
        null
    }

    fun modelInfoJson(info: ModelInfo): String = JSONObject().apply {
        info.model?.let { put("model", it) }
        put("models", JSONArray(info.models))
        info.effort?.let { put("effort", it) }
        put("model_efforts", JSONObject().apply {
            info.modelEfforts.forEach { (m, e) -> put(m, JSONObject().put("efforts", JSONArray(e.efforts)).put("default", e.default ?: "")) }
        })
        put("providers", JSONObject().apply {
            info.providers.forEach { p ->
                put(p.id, JSONObject().put("ok", true).put("models", JSONArray(p.models)).put("usage",
                    JSONObject().put("remaining", p.usageRemaining?.toDouble() ?: JSONObject.NULL).put("label", p.usageLabel)))
            }
        })
        put("web_search", info.webSearch)
        put("figures", info.figures)
    }.toString()

    fun userJson(u: User): String = JSONObject().put("id", u.id).put("name", u.name).put("created_at", u.createdAt).toString()

    fun deviceJson(d: Device): String = JSONObject().put("id", d.id).put("kind", d.kind).put("name", d.name)
        .put("created_at", d.createdAt).put("last_seen", d.lastSeen).toString()
}
