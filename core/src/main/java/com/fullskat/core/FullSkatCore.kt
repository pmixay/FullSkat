package com.fullskat.core

import android.content.Context
import com.fullskat.core.account.AccountRepository
import com.fullskat.core.attachments.AttachmentRepository
import com.fullskat.core.chat.ChatRepository
import com.fullskat.core.db.CoreDatabase
import com.fullskat.core.net.ApiClient
import com.fullskat.core.session.SessionStore
import com.fullskat.core.sync.LiveEvents
import com.fullskat.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything both apps share, wired once per process: the API client, the local copy of the account's data,
 * sync and live events, chat, attachments and the account itself. [kind] is the device kind this app
 * registers as ("phone", "watch").
 */
class FullSkatCore(context: Context, baseUrl: String, kind: String, dbName: String = "fullskat-sync.db") {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = ApiClient(baseUrl)
    val db: CoreDatabase by lazy { CoreDatabase.build(appContext, dbName) }
    val session = SessionStore(appContext, kind)
    val sync: SyncEngine by lazy { SyncEngine(api, db, session) }
    val chat: ChatRepository by lazy { ChatRepository(api, db, session, sync) }
    val attachments: AttachmentRepository by lazy { AttachmentRepository(appContext, api, session) }
    val account: AccountRepository by lazy { AccountRepository(api, session, sync) }
    val events: LiveEvents by lazy { LiveEvents(api, session, sync, scope) }
}
