package com.fullskat.phone.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.watchinstall.AdbDiscovery
import com.fullskat.phone.watchinstall.AdbProblem
import com.fullskat.phone.watchinstall.AppVersion
import com.fullskat.phone.watchinstall.BundledApk
import com.fullskat.phone.watchinstall.BundledWatchApk
import com.fullskat.phone.watchinstall.InstallError
import com.fullskat.phone.watchinstall.InstallOutcome
import com.fullskat.phone.watchinstall.PairedWatch
import com.fullskat.phone.watchinstall.PairedWatchStore
import com.fullskat.phone.watchinstall.WatchAdb
import com.fullskat.phone.watchinstall.WatchInfo
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.watchinstall.attemptKey
import com.fullskat.phone.watchinstall.classifyConnectError
import com.fullskat.phone.watchinstall.classifyPairError
import com.fullskat.phone.watchinstall.describe
import com.fullskat.phone.watchinstall.filterPairingCode
import com.fullskat.phone.watchinstall.forget
import com.fullskat.phone.watchinstall.hostPort
import com.fullskat.phone.watchinstall.isPlausibleHost
import com.fullskat.phone.watchinstall.nextConnectTarget
import com.fullskat.phone.watchinstall.parseHostPort
import com.fullskat.phone.watchinstall.remember
import com.fullskat.phone.watchinstall.watchDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The installer's stages, one after the other: pair (once per watch), connect (by itself), install (on the button). */
enum class InstallStage { PAIR, CONNECT, INSTALL, DONE }

sealed interface PairStatus {
    data object Idle : PairStatus
    data object Working : PairStatus
    data object Paired : PairStatus
    data class Failed(val problem: AdbProblem, val detail: String = "") : PairStatus
}

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Working : ConnectionStatus
    data class Connected(val info: WatchInfo) : ConnectionStatus
    data class Failed(val problem: AdbProblem, val detail: String = "") : ConnectionStatus
}

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data class Uploading(val sent: Long, val total: Long) : InstallStatus

    /** Everything sent; the watch's package manager is verifying and installing it. */
    data object Installing : InstallStatus

    /** Removing a FullSkat signed with another key before installing this one. */
    data object Removing : InstallStatus

    /** Installed; starting it on the watch. */
    data object Opening : InstallStatus
    data class Failed(val error: InstallError, val detail: String = "") : InstallStatus

    /**
     * [relink]: the watch app was new or removed first, so it has to be linked to the account again. [opened]: it was
     * started on the watch.
     */
    data class Done(val version: AppVersion, val relink: Boolean, val opened: Boolean = false) : InstallStatus
}

/** Everything the install-on-watch screen shows. */
data class InstallerState(
    /** Android 17+: the local network permission is missing, nothing on the Wi-Fi can be reached. */
    val needsPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val bundled: BundledApk = BundledApk.Loading,
    /** The remembered watches are still being read. */
    val loading: Boolean = false,
    /** PAIR or CONNECT: where the user is while no watch is connected (INSTALL and DONE follow from [stage]). */
    val step: InstallStage = InstallStage.PAIR,
    /** The watches paired before, most recently used first. */
    val saved: List<PairedWatch> = emptyList(),
    /** The watch being connected (just paired, or picked); null: whichever saved watch shows up first. */
    val target: PairedWatch? = null,
    val pairServices: List<WatchService> = emptyList(),
    val connectServices: List<WatchService> = emptyList(),
    val pairHost: String = "",
    val pairPort: String = "",
    val pairCode: String = "",
    val connectAddress: String = "",
    val pair: PairStatus = PairStatus.Idle,
    val connection: ConnectionStatus = ConnectionStatus.Idle,
    val install: InstallStatus = InstallStatus.Idle,
    /** The watch refused this phone (it was unpaired on the watch): it has to be paired again. */
    val notPaired: Boolean = false,
    /** Connecting found nothing for a while: the screen adds hints. */
    val slow: Boolean = false,
    /** The user disconnected: connecting waits until a watch is picked. */
    val paused: Boolean = false,
) {
    val connected: WatchInfo? get() = (connection as? ConnectionStatus.Connected)?.info
    val stage: InstallStage
        get() = when {
            install is InstallStatus.Done -> InstallStage.DONE
            connected != null -> InstallStage.INSTALL
            else -> step
        }

    /** The watches the connect stage is looking for. */
    val candidates: List<PairedWatch> get() = target?.let(::listOf) ?: saved
    val busy: Boolean
        get() = pair == PairStatus.Working || connection == ConnectionStatus.Working ||
            install is InstallStatus.Uploading || install == InstallStatus.Installing || install == InstallStatus.Removing ||
            install == InstallStatus.Opening
}

/** What the screen can ask for; the defaults do nothing (screenshots). */
interface InstallerActions {
    fun onPairHost(text: String) {}
    fun onPairPort(text: String) {}
    fun onPairCode(text: String) {}
    fun onConnectAddress(text: String) {}
    fun selectPairService(service: WatchService) {}
    fun pair() {}

    /** From the connect stage: pair a watch that is not in the list (or pair one again). */
    fun pairNewWatch() {}

    /** From the pair stage: back to the watches paired before. */
    fun usePairedWatches() {}
    fun selectWatch(watch: PairedWatch) {}
    fun forgetWatch(watch: PairedWatch) {}
    fun retryConnect() {}

    /** Connects to the address typed in by hand. */
    fun connect() {}
    fun disconnect() {}
    fun install() {}
    fun removeAndInstall() {}
    fun dismissInstallError() {}
}

fun hasLocalNetworkAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/**
 * Installs the bundled watch app over wireless debugging, in stages: pair a new watch with its code (remembered
 * afterwards), connect to it by itself once its wireless debugging shows up on the Wi-Fi, install on the button, then
 * open the app on the watch. All ADB work runs off the main thread in [viewModelScope]; leaving the screen cancels it
 * and closes the connection.
 */
class InstallWatchViewModel(app: PhoneContainer) : ViewModel(), InstallerActions {
    private val context = app.appContext
    private val adbDir = File(context.noBackupFilesDir, "adb")
    private val adb = WatchAdb(adbDir, "FullSkat@" + app.deviceName.replace(Regex("\\s+"), "-"))
    private val store = PairedWatchStore(File(adbDir, "watches.json"))
    private val discovery = AdbDiscovery(context)

    var state by mutableStateOf(InstallerState(needsPermission = !hasLocalNetworkAccess(context), loading = true))
        private set

    private var work: Job? = null
    private var slowTimer: Job? = null

    /** Addresses (attemptKey) already tried in this connect stage: each is tried once by itself. */
    private val tried = mutableSetOf<String>()

    /** The pairing service last filled in by discovery (the fields follow it until the user types). */
    private var autoPair: WatchService? = null

    init {
        viewModelScope.launch {
            val bundled = withContext(Dispatchers.IO) { runCatching { BundledWatchApk.load(context) }.getOrDefault(BundledApk.Broken) }
            state = state.copy(bundled = bundled)
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrDefault(emptyList()) }
            state = state.copy(saved = saved, loading = false)
            if (saved.isNotEmpty()) enterConnect(target = null) // paired before: straight to connecting
        }
        viewModelScope.launch { discovery.pairing.collect(::onPairServices) }
        viewModelScope.launch { discovery.connect.collect(::onConnectServices) }
        if (!state.needsPermission) discovery.start()
    }

    fun onPermissionResult(granted: Boolean) {
        state = state.copy(needsPermission = !granted, permissionDenied = !granted)
        if (granted) {
            discovery.start()
            if (state.stage == InstallStage.CONNECT) enterConnect(state.target)
        }
    }

    /** Back from the system settings: the permission may have been granted there. */
    fun recheckPermission() {
        if (state.needsPermission && hasLocalNetworkAccess(context)) onPermissionResult(true)
    }

    // ------------------------------------------------------------------ discovery

    private fun onPairServices(list: List<WatchService>) {
        state = state.copy(pairServices = list)
        val newest = list.lastOrNull() ?: return
        val untouched = (state.pairHost.isBlank() && state.pairPort.isBlank()) ||
            autoPair?.let { it.host == state.pairHost && it.port.toString() == state.pairPort } == true
        if (untouched && state.pair != PairStatus.Working) fillPair(newest)
    }

    private fun fillPair(service: WatchService) {
        autoPair = service
        state = state.copy(pairHost = service.host, pairPort = service.port.toString())
    }

    private fun onConnectServices(list: List<WatchService>) {
        state = state.copy(connectServices = list)
        maybeAutoConnect()
    }

    // ------------------------------------------------------------------ stage 1: pair

    override fun onPairHost(text: String) {
        val split = parseHostPort(text)
        state = if (split != null) state.copy(pairHost = split.first, pairPort = split.second.toString()) else state.copy(pairHost = text.trim())
        clearPairError()
    }

    override fun onPairPort(text: String) {
        state = state.copy(pairPort = text.filter { it.isDigit() }.take(5))
        clearPairError()
    }

    override fun onPairCode(text: String) {
        val code = filterPairingCode(text)
        if (code == state.pairCode) return
        state = state.copy(pairCode = code)
        clearPairError()
        if (code.length == 6 && pairTarget() != null) pair()
    }

    override fun selectPairService(service: WatchService) {
        fillPair(service)
        clearPairError()
    }

    private fun clearPairError() {
        if (state.pair is PairStatus.Failed) state = state.copy(pair = PairStatus.Idle)
    }

    private fun pairTarget(): Pair<String, Int>? {
        val host = state.pairHost.trim()
        val port = state.pairPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return if (isPlausibleHost(host)) host to port else null
    }

    override fun pair() {
        if (state.stage != InstallStage.PAIR || state.busy) return
        val target = pairTarget()
        if (target == null) {
            state = state.copy(pair = PairStatus.Failed(AdbProblem.BAD_ADDRESS))
            return
        }
        val code = state.pairCode
        if (code.length != 6) return
        val (host, port) = target
        work = viewModelScope.launch {
            state = state.copy(pair = PairStatus.Working)
            try {
                withTimeout(PAIR_TIMEOUT_MS) { adb.pair(host, port, code) }
            } catch (e: TimeoutCancellationException) {
                state = state.copy(pair = PairStatus.Failed(AdbProblem.TIMEOUT))
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = classifyPairError(e)
                state = state.copy(pair = PairStatus.Failed(problem, describe(e)), pairCode = if (problem == AdbProblem.WRONG_CODE) "" else state.pairCode)
                return@launch
            }
            // The watch trusts this phone from now on: remember it, then connect to it by itself.
            val service = state.pairServices.firstOrNull { it.host == host && it.port == port }
            val earlier = service?.let { s -> state.saved.firstOrNull { it.matches(s) } }
            val now = System.currentTimeMillis()
            val watch = PairedWatch(
                key = earlier?.key ?: UUID.randomUUID().toString(),
                serviceName = service?.name ?: earlier?.serviceName,
                serial = service?.serial ?: earlier?.serial,
                name = earlier?.name ?: service?.serial ?: host,
                host = host,
                port = earlier?.port ?: 0,
                pairedAt = now,
                lastUsedAt = now,
            )
            rememberWatch(watch)
            state = state.copy(pair = PairStatus.Paired, pairCode = "")
            enterConnect(watch)
        }
    }

    override fun pairNewWatch() {
        work?.cancel()
        slowTimer?.cancel()
        if (state.connected != null) thread(name = "adb-disconnect", isDaemon = true) { adb.disconnect() }
        state = state.copy(
            step = InstallStage.PAIR,
            pair = PairStatus.Idle,
            pairCode = "",
            connection = ConnectionStatus.Idle,
            install = InstallStatus.Idle,
            notPaired = false,
            slow = false,
            paused = false,
        )
        if (!state.needsPermission) discovery.start()
    }

    override fun usePairedWatches() {
        if (state.saved.isEmpty()) return
        work?.cancel()
        state = state.copy(pair = PairStatus.Idle)
        enterConnect(target = null)
    }

    // ------------------------------------------------------------------ stage 2: connect

    /** Starts looking for [target] (or any saved watch) and connects to it as soon as it is seen. */
    private fun enterConnect(target: PairedWatch?) {
        tried.clear()
        state = state.copy(step = InstallStage.CONNECT, target = target, connection = ConnectionStatus.Idle, notPaired = false, slow = false, paused = false)
        slowTimer?.cancel()
        slowTimer = viewModelScope.launch {
            delay(SLOW_MS)
            if (state.stage == InstallStage.CONNECT) state = state.copy(slow = true)
        }
        if (!state.needsPermission) discovery.start()
        maybeAutoConnect()
    }

    /**
     * In the connect stage: connects to the next watch found on the Wi-Fi that was not tried yet; when none is seen (mDNS
     * can be blocked on a network), to a watch's last address, which works while its wireless debugging stayed on.
     */
    private fun maybeAutoConnect() {
        val s = state
        if (s.loading || s.stage != InstallStage.CONNECT || s.busy || s.needsPermission || s.notPaired || s.paused) return
        val watches = s.candidates
        nextConnectTarget(s.connectServices, watches, tried)?.let { (service, watch) ->
            tried += attemptKey(service.host, service.port)
            work = viewModelScope.launch { doConnect(service.host, service.port, watch, quiet = false) }
            return
        }
        val last = watches.firstOrNull { it.port > 0 && attemptKey(it.host, it.port) !in tried } ?: return
        tried += attemptKey(last.host, last.port)
        work = viewModelScope.launch { doConnect(last.host, last.port, last, quiet = true) }
    }

    override fun selectWatch(watch: PairedWatch) {
        if (state.stage != InstallStage.CONNECT) return
        work?.cancel()
        enterConnect(watch)
    }

    override fun forgetWatch(watch: PairedWatch) {
        val list = state.saved.forget(watch)
        state = state.copy(saved = list, target = state.target?.takeUnless { it.key == watch.key })
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(list) } }
        if (list.isEmpty()) pairNewWatch() else if (state.stage == InstallStage.CONNECT && state.connection != ConnectionStatus.Working) enterConnect(state.target)
    }

    override fun retryConnect() {
        work?.cancel()
        enterConnect(state.target)
    }

    override fun onConnectAddress(text: String) {
        state = state.copy(connectAddress = text.trim())
        if (state.connection is ConnectionStatus.Failed && !state.notPaired) state = state.copy(connection = ConnectionStatus.Idle)
    }

    override fun connect() {
        if (state.busy) return
        val target = parseHostPort(state.connectAddress)
        if (target == null) {
            state = state.copy(connection = ConnectionStatus.Failed(AdbProblem.BAD_ADDRESS))
            return
        }
        state = state.copy(paused = false)
        tried += attemptKey(target.first, target.second)
        work = viewModelScope.launch { doConnect(target.first, target.second, state.target, quiet = false) }
    }

    /** [quiet]: a guess (a watch's last address); its failure is not shown, except that the watch no longer knows the phone. */
    private suspend fun doConnect(host: String, port: Int, watch: PairedWatch?, quiet: Boolean) {
        val before = state.connection
        state = state.copy(connection = ConnectionStatus.Working, connectAddress = hostPort(host, port), install = InstallStatus.Idle)
        try {
            adb.connect(host, port)
            val info = adb.watchInfo()
            val remembered = rememberConnected(watch, info, host, port)
            slowTimer?.cancel()
            state = state.copy(connection = ConnectionStatus.Connected(info), target = remembered, notPaired = false, slow = false)
            discovery.stop() // found; no more multicast while installing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { adb.disconnect() }
            val problem = classifyConnectError(e)
            state = when {
                problem == AdbProblem.NOT_PAIRED -> state.copy(connection = ConnectionStatus.Failed(problem, describe(e)), notPaired = true)
                quiet -> state.copy(connection = before.takeIf { it is ConnectionStatus.Failed } ?: ConnectionStatus.Idle)
                else -> state.copy(connection = ConnectionStatus.Failed(problem, describe(e)))
            }
            maybeAutoConnect() // another watch or address may still come
        }
    }

    /** Updates (or adds) the saved record of the watch just connected: its name, serial and current address. */
    private suspend fun rememberConnected(watch: PairedWatch?, info: WatchInfo, host: String, port: Int): PairedWatch {
        val service = state.connectServices.firstOrNull { it.host == host && it.port == port }
        val serial = info.serial.ifBlank { null }
        val base = watch
            ?: state.saved.firstOrNull { serial != null && it.serial == serial }
            ?: service?.let { s -> state.saved.firstOrNull { it.matches(s) } }
        val now = System.currentTimeMillis()
        val updated = PairedWatch(
            key = base?.key ?: UUID.randomUUID().toString(),
            serviceName = service?.name ?: base?.serviceName,
            serial = serial ?: base?.serial,
            name = watchDisplayName(info).ifBlank { base?.name ?: host },
            host = host,
            port = port,
            pairedAt = base?.pairedAt ?: now,
            lastUsedAt = now,
        )
        rememberWatch(updated)
        return updated
    }

    private suspend fun rememberWatch(watch: PairedWatch) {
        val list = state.saved.remember(watch)
        state = state.copy(saved = list)
        withContext(Dispatchers.IO) { runCatching { store.save(list) } }
    }

    override fun disconnect() {
        work?.cancel()
        thread(name = "adb-disconnect", isDaemon = true) { adb.disconnect() }
        slowTimer?.cancel()
        // Back to the list; it does not connect again by itself until a watch is picked.
        state = state.copy(step = InstallStage.CONNECT, connection = ConnectionStatus.Idle, install = InstallStatus.Idle, paused = true, slow = false)
        if (!state.needsPermission) discovery.start()
    }

    // ------------------------------------------------------------------ stage 3: install, then open

    override fun install() = startInstall(removeFirst = false)

    override fun removeAndInstall() = startInstall(removeFirst = true)

    override fun dismissInstallError() {
        if (state.install is InstallStatus.Failed) state = state.copy(install = InstallStatus.Idle)
    }

    private fun startInstall(removeFirst: Boolean) {
        val bundled = state.bundled as? BundledApk.Ready ?: return
        val info = state.connected ?: return
        if (state.busy) return
        work = viewModelScope.launch {
            try {
                var removed = false
                if (removeFirst) {
                    state = state.copy(install = InstallStatus.Removing)
                    removed = adb.uninstall()
                }
                state = state.copy(install = InstallStatus.Uploading(0, bundled.size))
                val sent = AtomicLong(0)
                val ticker = launch {
                    while (isActive) {
                        val s = sent.get()
                        state = state.copy(install = if (s >= bundled.size) InstallStatus.Installing else InstallStatus.Uploading(s, bundled.size))
                        delay(150)
                    }
                }
                val outcome = try {
                    adb.install(bundled.file) { sent.set(it) }
                } finally {
                    ticker.cancel()
                }
                when (outcome) {
                    InstallOutcome.Success -> {
                        val version = runCatching { adb.installedVersion() }.getOrNull() ?: bundled.version
                        state = state.copy(install = InstallStatus.Opening)
                        val opened = runCatching { adb.launchApp() }.getOrDefault(false)
                        state = state.copy(install = InstallStatus.Done(version, relink = removed || info.installed == null, opened = opened))
                        withContext(Dispatchers.IO) { adb.disconnect() } // done with the watch
                    }
                    is InstallOutcome.Failure -> state = state.copy(install = InstallStatus.Failed(outcome.error, outcome.detail))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (adb.isConnected) {
                    state = state.copy(install = InstallStatus.Failed(InstallError.OTHER, describe(e)))
                } else {
                    state = state.copy(install = InstallStatus.Failed(InstallError.LOST, describe(e)), connection = ConnectionStatus.Failed(AdbProblem.LOST))
                    if (!state.needsPermission) discovery.start()
                }
            }
        }
    }

    override fun onCleared() {
        work?.cancel()
        discovery.stop()
        // Closing joins libadb's reader thread and deleting the APK copy is disk work: not on the main thread.
        val bundled = state.bundled
        thread(name = "adb-close", isDaemon = true) {
            adb.close()
            BundledWatchApk.delete(bundled)
        }
    }

    /** Screenshots and previews. */
    internal fun show(state: InstallerState) {
        this.state = state
    }

    private companion object {
        const val PAIR_TIMEOUT_MS = 30_000L

        /** How long connecting looks for the watch before the screen adds hints. */
        const val SLOW_MS = 12_000L
    }
}
