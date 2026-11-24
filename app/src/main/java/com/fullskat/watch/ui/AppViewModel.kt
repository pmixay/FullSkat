package com.fullskat.watch.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fullskat.core.account.LinkState
import com.fullskat.core.db.DialogSummary
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.Prefs
import com.fullskat.watch.AppContainer
import com.fullskat.watch.data.prefs.Settings
import com.fullskat.watch.domain.AssistantTurn
import com.fullskat.watch.domain.TurnState
import com.fullskat.watch.tile.FullSkatTileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val core = container.core
    private val dao = core.db.dao()
    private val turn: AssistantTurn = container.newTurn()

    val settings: StateFlow<Settings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val turnState: StateFlow<TurnState> = turn.state
    val dialogs: Flow<List<DialogSummary>> = dao.observeDialogs()

    fun messages(dialogId: String): Flow<List<MessageEntity>> = dao.observeMessages(dialogId)

    suspend fun newDialog(): String {
        val id = turn.createDialog()
        dao.deleteEmptyLocalDialogsExcept(id)
        return id
    }

    fun deleteDialog(id: String) {
        viewModelScope.launch {
            core.sync.deleteDialog(id)
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
        viewModelScope.launch { container.speech.warmUp() }
    }

    /** Relearns who we are and what the server offers, and catches up with the dialogs (cheap when nothing changed). */
    fun refreshServerInfo() {
        viewModelScope.launch {
            if (core.account.refresh()) core.sync.sync()
        }
    }

    // ------------------------------------------------------------ linking

    private val _link = MutableStateFlow<LinkState?>(null)

    /** The code to show while this watch waits to be linked from the phone. */
    val link: StateFlow<LinkState?> = _link.asStateFlow()
    private var linkJob: Job? = null

    fun startLinking() {
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch {
            core.account.link(deviceName()).collect { _link.value = it }
        }
    }

    fun stopLinking() {
        linkJob?.cancel()
        linkJob = null
        _link.value = null
    }

    /** Unlinks this watch from the account (it forgets the dialogs; they stay on the phone and the server). */
    fun unlink() {
        viewModelScope.launch {
            core.account.signOut()
            FullSkatTileService.requestUpdate(container.appContext)
        }
    }

    // ------------------------------------------------------------ settings (shared with the phone)

    fun setModel(model: String?) {
        viewModelScope.launch { if (model == null) core.sync.putPrefs(Prefs(), setOf("model")) else core.sync.putPrefs(Prefs(model = model)) }
    }

    fun setEffort(effort: String?) {
        viewModelScope.launch { if (effort == null) core.sync.putPrefs(Prefs(), setOf("effort")) else core.sync.putPrefs(Prefs(effort = effort)) }
    }

    fun setWebSearch(enabled: Boolean) {
        viewModelScope.launch { core.sync.putPrefs(Prefs(webSearch = enabled)) }
    }

    fun setFigures(enabled: Boolean) {
        viewModelScope.launch { core.sync.putPrefs(Prefs(figures = enabled)) }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { container.settings.setHaptics(enabled) }
    }

    private val _figure = MutableStateFlow<String?>(null)

    /** The figure open in the full-screen viewer (its ```figure JSON). */
    val figure: StateFlow<String?> = _figure

    fun showFigure(json: String) {
        _figure.value = json
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

        /** Reasoning effort levels the server accepts, lowest first. */
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct()
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.ifBlank { "Watch" }
    }
}
