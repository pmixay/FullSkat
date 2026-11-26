package com.fullskat.core.sync

import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.LiveEvent
import com.fullskat.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live `GET /v1/events` stream while an app is in the foreground: every `changed` from another device
 * triggers a sync, `revoked` signs out. It syncs once on (re)connect, reconnects with backoff, waits while the
 * device is not signed in, and is stopped when the app goes to the background, so nothing runs while the screen is off.
 */
class LiveEvents(
    private val api: ApiClient,
    private val session: SessionStore,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                // not signed in yet (setup screen): start as soon as there is a session
                val token = session.state.map { it.token }.first { !it.isNullOrBlank() }!!
                try {
                    sync.sync()
                    api.events(token).collect { ev ->
                        backoff = 1_000L
                        when (ev) {
                            is LiveEvent.Changed -> sync.syncIfBehind(ev.seq)
                            LiveEvent.Revoked -> sync.signedOut()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException.Unauthorized) {
                    sync.signedOut()
                    continue // wait for the next session
                } catch (e: Exception) {
                    // offline or the stream dropped: try again later
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
