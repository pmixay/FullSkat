package com.fullskat.phone.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fullskat.core.account.AccountRepository
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.Device
import com.fullskat.core.net.Prefs
import com.fullskat.core.session.SessionState
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.ui.filterLinkCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings, the model picker and the account: preferences go through sync, so the watch gets them too. */
class SettingsViewModel(private val app: PhoneContainer) : ViewModel() {
    private val core = app.core
    val session: StateFlow<SessionState?> = core.session.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)


    /** Asks the server again for models, providers and usage (the rings go stale). */
    fun refresh() = viewModelScope.launch { core.account.refresh() }

    /** [model] null (or the server default) means "the server's default". */
    fun setModel(model: String?) = viewModelScope.launch {
        val default = session.value?.info?.model
        if (model == null || model == default) core.sync.putPrefs(Prefs(), clear = setOf("model"))
        else core.sync.putPrefs(Prefs(model = model))
    }

    fun setEffort(effort: String?) = viewModelScope.launch {
        if (effort == null) core.sync.putPrefs(Prefs(), clear = setOf("effort")) else core.sync.putPrefs(Prefs(effort = effort))
    }

    fun setWebSearch(on: Boolean) = viewModelScope.launch { core.sync.putPrefs(Prefs(webSearch = on)) }

    fun setFigures(on: Boolean) = viewModelScope.launch { core.sync.putPrefs(Prefs(figures = on)) }



    /** Unlinks this phone on the server and forgets the account here; the app returns to onboarding. */
    fun signOut() = viewModelScope.launch { core.account.signOut() }
}

/** The link-a-watch screen: the code being typed and what the server said about it. */
sealed interface LinkState {
    data object Entering : LinkState
    data object Claiming : LinkState
    data class Linked(val device: Device) : LinkState
    data object WrongCode : LinkState
    data class Locked(val retryAfterSec: Int) : LinkState
    data object Offline : LinkState
    data class Failed(val message: String) : LinkState
}

class LinkViewModel(private val app: PhoneContainer) : ViewModel() {
    var code by mutableStateOf("")
        private set
    var state by mutableStateOf<LinkState>(LinkState.Entering)
        private set

    /** Typed or pasted text: kept to the code alphabet; the sixth character sends it. */
    fun onCode(raw: String) {
        if (state == LinkState.Claiming || state is LinkState.Linked) return
        val next = filterLinkCode(raw)
        if (next == code) return
        code = next
        if (state != LinkState.Entering) state = LinkState.Entering
        if (code.length == AccountRepository.CODE_LENGTH) claim()
    }

    fun claim() {
        if (code.length != AccountRepository.CODE_LENGTH || state == LinkState.Claiming) return
        state = LinkState.Claiming
        viewModelScope.launch {
            state = try {
                LinkState.Linked(app.core.account.claim(code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.NotFound) {
                LinkState.WrongCode
            } catch (e: ApiException.Locked) {
                LinkState.Locked(e.retryAfterSec)
            } catch (e: ApiException.RateLimited) {
                LinkState.Locked(e.retryAfterSec)
            } catch (e: ApiException.Network) {
                LinkState.Offline
            } catch (e: ApiException) {
                LinkState.Failed(e.message.orEmpty())
            }
            if (state !is LinkState.Linked) code = ""
        }
    }

    /** Link another device. */
    fun again() {
        code = ""
        state = LinkState.Entering
    }

    internal fun show(code: String, state: LinkState) {
        this.code = code
        this.state = state
    }
}

sealed interface DevicesState {
    data object Loading : DevicesState
    data class Loaded(val devices: List<Device>) : DevicesState
    data object Offline : DevicesState
}

class DevicesViewModel(private val app: PhoneContainer) : ViewModel() {
    var state by mutableStateOf<DevicesState>(DevicesState.Loading)
        private set
    var unlinkFailed by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        if (state !is DevicesState.Loaded) state = DevicesState.Loading
        state = try {
            // this phone first, then the most recently seen
            DevicesState.Loaded(app.core.account.devices().sortedWith(compareByDescending<Device> { it.current }.thenByDescending { it.lastSeen }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            DevicesState.Offline
        }
    }

    fun unlink(device: Device) = viewModelScope.launch {
        unlinkFailed = try {
            app.core.account.unlink(device.id)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            true
        }
        load()
    }

    fun dismissError() {
        unlinkFailed = false
    }

    internal fun show(state: DevicesState) {
        this.state = state
    }
}
