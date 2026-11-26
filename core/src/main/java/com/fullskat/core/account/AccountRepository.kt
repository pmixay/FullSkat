package com.fullskat.core.account

import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.Device
import com.fullskat.core.net.LinkStatus
import com.fullskat.core.net.Session
import com.fullskat.core.session.SessionStore
import com.fullskat.core.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The steps of linking this device with a code (docs/PROTOCOL.md, Linking a device). */
sealed interface LinkState {
    /** Show [code]; it is valid for [expiresIn] more seconds of a [period]-second window ([receivedAt]: when this was learned). */
    data class Code(val code: String, val expiresIn: Int, val period: Int, val receivedAt: Long = System.currentTimeMillis()) : LinkState
    data class Linked(val session: Session) : LinkState

    /** No connection; it keeps trying. */
    data object Offline : LinkState
}

/** Sign-up, linking, devices and the shared session around them. */
class AccountRepository(
    private val api: ApiClient,
    private val session: SessionStore,
    private val sync: SyncEngine,
) {
    /** Creates an account (phone) named with this install's hidden random id. Throws WrongPasscode, Locked, Network... */
    suspend fun register(passcode: String, deviceName: String): Session {
        val s = api.register(passcode, session.installUserId(), session.deviceId(), deviceName, session.deviceKind)
        session.save(s)
        sync.sync()
        return s
    }

    /**
     * Shows a code until a signed-in device enters it: emits [LinkState.Code] for every 30-second code and
     * [LinkState.Linked] at the end (the session is saved and the first sync done by then). Runs until linked
     * or cancelled; reconnects by itself.
     */
    fun link(deviceName: String): Flow<LinkState> = flow {
        val deviceId = session.deviceId()
        while (true) {
            try {
                val start = api.linkStart(deviceId, deviceName, session.deviceKind)
                emit(LinkState.Code(start.code, start.expiresIn, start.period))
                while (true) {
                    when (val st = api.linkStatus(start.linkId)) {
                        is LinkStatus.Pending -> emit(LinkState.Code(st.code, st.expiresIn, st.period))
                        is LinkStatus.Linked -> {
                            session.save(st.session)
                            sync.sync()
                            emit(LinkState.Linked(st.session))
                            return@flow
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.NotFound) {
                continue // the link expired: start a new one
            } catch (e: ApiException) {
                emit(LinkState.Offline)
                delay(3_000)
            }
        }
    }

    /** The signed-in user enters the code another device shows. Throws NotFound (wrong code), Locked... */
    suspend fun claim(code: String): Device {
        val token = session.token() ?: throw ApiException.Unauthorized("not signed in")
        return api.linkClaim(token, normalizeCode(code))
    }

    /** Reloads who we are and what the server offers (models, providers, efforts); false when offline. */
    suspend fun refresh(): Boolean {
        val token = session.token() ?: return false
        return try {
            session.save(api.me(token))
            true
        } catch (e: ApiException.Unauthorized) {
            sync.signedOut()
            false
        } catch (e: ApiException) {
            false
        }
    }

    suspend fun devices(): List<Device> {
        val token = session.token() ?: return emptyList()
        return api.devices(token)
    }

    suspend fun unlink(deviceId: String) {
        val token = session.token() ?: return
        api.unlink(token, deviceId)
    }

    /** Unlinks this device on the server (best effort) and forgets the account here. */
    suspend fun signOut() {
        val s = session.current()
        val token = s.token
        if (token != null && s.device != null) runCatching { api.unlink(token, s.device.id) }
        sync.signedOut()
    }

    companion object {
        /** What the user typed as a code: upper case, letters and digits only (the server's alphabet has no 0/O/1/I). */
        fun normalizeCode(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH)

        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 6
    }
}
