package com.fullskat.core.sync

import androidx.room.withTransaction
import com.fullskat.core.db.CoreDatabase
import com.fullskat.core.db.DialogEntity
import com.fullskat.core.db.KeyValue
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.db.PendingOp
import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.DialogDto
import com.fullskat.core.net.JsonModels
import com.fullskat.core.net.MessageDto
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.Prefs
import com.fullskat.core.net.WebSources
import com.fullskat.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the local copy in step with the server (docs/PROTOCOL.md, Sync): first replays the changes made here
 * (queued as [PendingOp]s, so they survive being offline), then pulls everything after the last seen `seq`.
 * One sync runs at a time; a request that comes in meanwhile is folded into a second pass.
 */
class SyncEngine(
    private val api: ApiClient,
    private val db: CoreDatabase,
    private val session: SessionStore,
) {
    enum class Status { IDLE, SYNCING, OFFLINE }

    private val dao = db.dao()
    private val mutex = Mutex()
    @Volatile private var again = false
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Answers this device is streaming right now: their live text is newer than any copy sync brings. */
    val activeAnswers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Called when the server says the token is gone (the device was unlinked): the app returns to setup. */
    var onSignedOut: (suspend () -> Unit)? = null

    suspend fun seq(): Long = dao.value(KEY_SEQ)?.toLongOrNull() ?: 0L

    /** Syncs now (or right after the running sync). False when offline or not signed in. */
    suspend fun sync(): Boolean {
        if (mutex.isLocked) { again = true; return true }
        return mutex.withLock {
            var ok: Boolean
            do {
                again = false
                ok = pass()
            } while (again && ok)
            ok
        }
    }

    /** Syncs if the server is ahead of [seq] (from a live event). */
    suspend fun syncIfBehind(serverSeq: Long) {
        if (serverSeq > seq()) sync()
    }

    private suspend fun pass(): Boolean {
        val token = session.token() ?: return false
        _status.value = Status.SYNCING
        return try {
            flush(token)
            var since = seq()
            do {
                val page = api.sync(token, since)
                apply(page.dialogs, page.messages, page.prefs, page.seq)
                since = page.seq
            } while (page.more)
            _status.value = Status.IDLE
            true
        } catch (e: ApiException.Unauthorized) {
            signedOut()
            false
        } catch (e: CancellationException) {
            _status.value = Status.IDLE
            throw e
        } catch (e: Exception) {
            _status.value = Status.OFFLINE
            false
        }
    }

    /** Replays queued local changes in order; stops at the first one the network refuses (retried next time). */
    private suspend fun flush(token: String) {
        for (op in dao.ops()) {
            when (op.kind) {
                CREATE_DIALOG -> {
                    val d = dao.dialog(op.targetId)
                    if (d != null) api.createDialog(token, d.id, d.title, d.createdAt)
                }
                RENAME_DIALOG -> api.renameDialog(token, op.targetId, op.payload)
                DELETE_DIALOG -> api.deleteDialog(token, op.targetId)
                DELETE_MESSAGE -> api.deleteMessage(token, op.targetId)
                PUT_PREFS -> {
                    val o = JSONObject(op.payload)
                    val clear = o.optJSONArray("clear")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
                    val saved = api.putPrefs(token, JsonModels.prefs(o.optJSONObject("prefs")), clear)
                    if (dao.ops().none { it.kind == PUT_PREFS && it.id != op.id }) session.savePrefs(saved)
                }
            }
            dao.removeOp(op.id)
        }
    }

    private suspend fun apply(dialogs: List<DialogDto>, messages: List<MessageDto>, prefs: Prefs?, seq: Long) {
        db.withTransaction {
            for (d in dialogs) {
                if (d.deleted) dao.deleteDialog(d.id)
                else dao.upsertDialog(DialogEntity(d.id, d.title, d.createdAt, d.updatedAt, d.seq, local = false))
            }
            for (m in messages) {
                if (m.deleted) { dao.deleteMessage(m.id); continue }
                // the live stream on this device is ahead of a copy the server saved halfway
                if (m.id in activeAnswers && m.status == MessageStatus.STREAMING) continue
                dao.upsertMessage(entityOf(m))
            }
            dao.putValue(KeyValue(KEY_SEQ, seq.toString()))
        }
        // prefs changed here and not yet sent win over the server's older copy
        if (prefs != null && dao.ops().none { it.kind == PUT_PREFS }) session.savePrefs(prefs)
    }

    // ---------------------------------------------------------------- local changes

    /** A new empty dialog, local until its first message (or forever, if it stays empty). */
    suspend fun newDialog(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity(id, "", now, now, local = true))
        return id
    }

    suspend fun renameDialog(id: String, title: String) {
        dao.updateDialog(id, title, System.currentTimeMillis())
        val d = dao.dialog(id)
        if (d?.local == true) return // the server gets the title with the dialog
        dao.removeOps(id, RENAME_DIALOG)
        queue(RENAME_DIALOG, id, title)
    }

    suspend fun deleteDialog(id: String) {
        val local = dao.dialog(id)?.local == true && dao.messages(id).isEmpty()
        dao.deleteDialog(id)
        dao.removeOps(id, RENAME_DIALOG)
        if (!local) queue(DELETE_DIALOG, id)
    }

    suspend fun deleteMessage(id: String) {
        dao.deleteMessage(id)
        queue(DELETE_MESSAGE, id)
    }

    /** Sets shared preferences: saved here at once, sent to the server with the next sync. [clear]: keys back to default. */
    suspend fun putPrefs(change: Prefs, clear: Set<String> = emptySet()) {
        val cur = session.current().prefs
        val merged = Prefs(
            model = if ("model" in clear) null else change.model ?: cur.model,
            effort = if ("effort" in clear) null else change.effort ?: cur.effort,
            webSearch = if ("web_search" in clear) null else change.webSearch ?: cur.webSearch,
            figures = if ("figures" in clear) null else change.figures ?: cur.figures,
        )
        session.savePrefs(merged)
        val payload = JSONObject().put("prefs", JsonModels.prefsJson(change)).put("clear", org.json.JSONArray(clear.toList()))
        queue(PUT_PREFS, "prefs", payload.toString())
    }

    private suspend fun queue(kind: String, target: String, payload: String = "") {
        dao.addOp(PendingOp(kind = kind, targetId = target, payload = payload, createdAt = System.currentTimeMillis()))
        sync()
    }

    /** Forgets the local copy and the session (signing out, or the device was unlinked elsewhere). */
    suspend fun signedOut() {
        activeAnswers.clear()
        dao.clearAll()
        session.clear()
        _status.value = Status.IDLE
        onSignedOut?.invoke()
    }

    companion object {
        const val KEY_SEQ = "seq"
        const val CREATE_DIALOG = "create_dialog"
        const val RENAME_DIALOG = "rename_dialog"
        const val DELETE_DIALOG = "delete_dialog"
        const val DELETE_MESSAGE = "delete_message"
        const val PUT_PREFS = "put_prefs"

        fun entityOf(m: MessageDto) = MessageEntity(
            id = m.id,
            dialogId = m.dialogId,
            role = m.role,
            content = m.content,
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            status = m.status,
            thinking = m.thinking,
            sources = WebSources.encode(m.sources),
            attachments = JsonModels.attachmentsJson(m.attachments),
            model = m.model,
            error = m.error,
            seq = m.seq,
        )
    }
}
