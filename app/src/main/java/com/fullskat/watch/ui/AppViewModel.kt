package com.fullskat.watch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fullskat.watch.AppContainer
import com.fullskat.watch.data.db.DialogSummary
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.net.ApiException
import com.fullskat.watch.data.prefs.Settings
import com.fullskat.watch.data.prefs.SettingsRepository
import com.fullskat.watch.data.prefs.SpeechLang
import com.fullskat.watch.domain.AssistantTurn
import com.fullskat.watch.domain.TurnState
import com.fullskat.watch.tile.FullSkatTileService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PairUiState {
    data object Idle : PairUiState
    data object Busy : PairUiState
    data class Failed(val reason: Reason, val retryAfterSec: Int = 0) : PairUiState {
        enum class Reason { WRONG, LOCKED, UNREACHABLE, SERVER }
    }
    data class Success(val firstSetup: Boolean) : PairUiState
}

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val dao = container.db.dialogDao()
    private val turn: AssistantTurn = container.newTurn()

    val settings: StateFlow<Settings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val turnState: StateFlow<TurnState> = turn.state
    val dialogs: Flow<List<DialogSummary>> = dao.observeDialogs()

    private val _pairState = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val pairState: StateFlow<PairUiState> = _pairState.asStateFlow()

    fun messages(dialogId: String): Flow<List<MessageEntity>> = dao.observeMessages(dialogId)

    suspend fun newDialog(): String {
        val id = turn.createDialog()
        dao.deleteEmptyDialogsExcept(id)
        return id
    }

    fun deleteDialog(id: String) {
        viewModelScope.launch {
            dao.deleteDialog(id)
            FullSkatTileService.requestUpdate(container.appContext)
        }
    }

    fun startTurn(dialogId: String) {
        if (turn.state.value.busy) return
        turn.start(viewModelScope, dialogId)
    }

    fun finishListening() = turn.finishListening()
    fun cancelTurn() = turn.cancel()
    fun dismissError() = turn.dismissError()

    init {
        refreshServerInfo()
    }

    /** Learns the server's current default model and allowlist (they depend on the provider it runs). */
    private fun refreshServerInfo() {
        viewModelScope.launch {
            val cfg = container.settings.current()
            val token = cfg.token ?: return@launch
            try {
                val me = container.api.me(cfg.serverUrl, token)
                container.settings.saveServerModels(me.model, me.models)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Offline, token or parsing problems surface on the first turn; the cached list stays usable.
            }
        }
    }

    fun pair(passcode: String, deviceName: String) {
        viewModelScope.launch {
            _pairState.value = PairUiState.Busy
            try {
                val deviceId = container.settings.deviceId()
                val r = container.api.pair(passcode, deviceId, deviceName)
                container.settings.savePairing(r.token, r.serverName, r.model, r.models)
                _pairState.value = PairUiState.Success(r.firstSetup)
            } catch (e: ApiException) {
                _pairState.value = when (e) {
                    is ApiException.WrongPasscode -> PairUiState.Failed(PairUiState.Failed.Reason.WRONG)
                    is ApiException.Locked -> PairUiState.Failed(PairUiState.Failed.Reason.LOCKED, e.retryAfterSec)
                    is ApiException.RateLimited -> PairUiState.Failed(PairUiState.Failed.Reason.LOCKED, e.retryAfterSec)
                    is ApiException.Network -> PairUiState.Failed(PairUiState.Failed.Reason.UNREACHABLE)
                    is ApiException.Unauthorized, is ApiException.Server -> PairUiState.Failed(PairUiState.Failed.Reason.SERVER)
                }
            }
        }
    }

    fun resetPairState() {
        _pairState.value = PairUiState.Idle
    }

    fun unpair() {
        viewModelScope.launch { container.settings.clearPairing() }
    }

    fun setModel(model: String?) {
        viewModelScope.launch { container.settings.setModel(model) }
    }

    fun setLang(lang: SpeechLang) {
        viewModelScope.launch { container.settings.setLang(lang) }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { container.settings.setHaptics(enabled) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }

    companion object {
        /** Fallback picker list until the server has reported its allowlist. The server validates every request. */
        val MODELS = listOf(
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "claude-fable-5-1",
        )
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
