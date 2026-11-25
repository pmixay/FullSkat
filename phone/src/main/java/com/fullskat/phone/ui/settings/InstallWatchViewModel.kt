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
import com.fullskat.phone.watchinstall.WatchAdb
import com.fullskat.phone.watchinstall.WatchInfo
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.watchinstall.classifyConnectError
import com.fullskat.phone.watchinstall.classifyPairError
import com.fullskat.phone.watchinstall.describe
import com.fullskat.phone.watchinstall.filterPairingCode
import com.fullskat.phone.watchinstall.hostPort
import com.fullskat.phone.watchinstall.isPlausibleHost
import com.fullskat.phone.watchinstall.parseHostPort
import com.fullskat.phone.watchinstall.sameWatch
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

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
    data class Failed(val error: InstallError, val detail: String = "") : InstallStatus

    /** [relink]: the watch app was new or removed first, so it has to be linked to the account again. */
    data class Done(val version: AppVersion, val relink: Boolean) : InstallStatus
}

/** Everything the install-on-watch screen shows. */
data class InstallerState(
    /** Android 17+: the local network permission is missing, nothing on the Wi-Fi can be reached. */
    val needsPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val bundled: BundledApk = BundledApk.Loading,
    val pairServices: List<WatchService> = emptyList(),
    val connectServices: List<WatchService> = emptyList(),
    val pairHost: String = "",
    val pairPort: String = "",
    val pairCode: String = "",
    val connectAddress: String = "",
    val pair: PairStatus = PairStatus.Idle,
    val connection: ConnectionStatus = ConnectionStatus.Idle,
    val install: InstallStatus = InstallStatus.Idle,
    /** A connection attempt found that the watch does not know this phone yet. */
    val notPaired: Boolean = false,
) {
    val connected: WatchInfo? get() = (connection as? ConnectionStatus.Connected)?.info
    val busy: Boolean
        get() = pair == PairStatus.Working || connection == ConnectionStatus.Working ||
            install is InstallStatus.Uploading || install == InstallStatus.Installing || install == InstallStatus.Removing
}

/** What the screen can ask for; the defaults do nothing (screenshots). */
interface InstallerActions {
    fun onPairHost(text: String) {}
    fun onPairPort(text: String) {}
    fun onPairCode(text: String) {}
    fun onConnectAddress(text: String) {}
    fun selectPairService(service: WatchService) {}
    fun selectConnectService(service: WatchService) {}
    fun pair() {}
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
 * Installs the bundled watch app over wireless debugging: finds the watch, pairs once, connects, streams the APK. All
 * ADB work runs off the main thread in [viewModelScope]; leaving the screen cancels it and closes the connection.
 */
class InstallWatchViewModel(app: PhoneContainer) : ViewModel(), InstallerActions {
    private val context = app.appContext
    private val adb = WatchAdb(File(context.noBackupFilesDir, "adb"), "FullSkat@" + app.deviceName.replace(Regex("\\s+"), "-"))
    private val discovery = AdbDiscovery(context)

    var state by mutableStateOf(InstallerState(needsPermission = !hasLocalNetworkAccess(context)))
        private set

    private var work: Job? = null

    /** Connect services already tried by themselves (name@address): each is tried once. */
    private val tried = mutableSetOf<String>()

    /** The pairing service last filled in by discovery (the fields follow it until the user types). */
    private var autoPair: WatchService? = null
    private var autoConnectAddress: String? = null

    /** The name of the watch paired in this session, to pick its connect service. */
    private var pairedName: String? = null

    init {
        viewModelScope.launch {
            val bundled = withContext(Dispatchers.IO) { runCatching { BundledWatchApk.load(context) }.getOrDefault(BundledApk.Broken) }
            state = state.copy(bundled = bundled)
        }
        viewModelScope.launch { discovery.pairing.collect(::onPairServices) }
        viewModelScope.launch { discovery.connect.collect(::onConnectServices) }
        if (!state.needsPermission) discovery.start()
    }

    fun onPermissionResult(granted: Boolean) {
        state = state.copy(needsPermission = !granted, permissionDenied = !granted)
        if (granted) discovery.start()
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
        val target = pickConnectService(list) ?: return
        if (state.connectAddress.isBlank() || state.connectAddress == autoConnectAddress) {
            autoConnectAddress = target.address
            state = state.copy(connectAddress = target.address)
        }
        maybeAutoConnect()
    }

    private fun pickConnectService(list: List<WatchService>): WatchService? {
        val paired = pairedName
        return list.firstOrNull { paired != null && sameWatch(it.name, paired) }
            ?: list.firstOrNull { it.host == state.pairHost }
            ?: list.lastOrNull()
    }

    /** A watch that was paired before connects by itself as soon as its wireless debugging is seen: pairing is skipped. */
    private fun maybeAutoConnect() {
        if (state.busy || state.connected != null || state.needsPermission) return
        val candidate = state.connectServices.firstOrNull { "${it.name}@${it.address}" !in tried } ?: return
        tried += "${candidate.name}@${candidate.address}"
        work = viewModelScope.launch { doConnect(candidate.host, candidate.port, auto = true) }
    }

    // ------------------------------------------------------------------ fields

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

    override fun onConnectAddress(text: String) {
        state = state.copy(connectAddress = text.trim())
        if (state.connection is ConnectionStatus.Failed) state = state.copy(connection = ConnectionStatus.Idle)
    }

    override fun selectPairService(service: WatchService) {
        fillPair(service)
        clearPairError()
    }

    override fun selectConnectService(service: WatchService) {
        autoConnectAddress = service.address
        state = state.copy(connectAddress = service.address)
        connect()
    }

    private fun clearPairError() {
        if (state.pair is PairStatus.Failed) state = state.copy(pair = PairStatus.Idle)
    }

    private fun pairTarget(): Pair<String, Int>? {
        val host = state.pairHost.trim()
        val port = state.pairPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return if (isPlausibleHost(host)) host to port else null
    }

    // ------------------------------------------------------------------ pair, connect

    override fun pair() {
        if (state.connection == ConnectionStatus.Working && state.pair != PairStatus.Working) {
            // A connection attempt of its own (to a watch that is not paired yet) gives way to pairing.
            work?.cancel()
            state = state.copy(connection = ConnectionStatus.Idle)
        }
        if (state.busy || state.connected != null) return
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
            pairedName = state.pairServices.firstOrNull { it.host == host && it.port == port }?.name
            tried.clear()
            state = state.copy(pair = PairStatus.Paired, pairCode = "", notPaired = false)
            // The watch now trusts this phone: connect to its wireless-debugging port right away when it is known.
            val target = state.connectServices.firstOrNull { pairedName != null && sameWatch(it.name, pairedName!!) }
                ?: state.connectServices.firstOrNull { it.host == host }
            if (target != null) {
                tried += "${target.name}@${target.address}"
                doConnect(target.host, target.port, auto = false)
            } else if (state.connectAddress.isBlank()) {
                state = state.copy(connectAddress = "$host:")
            }
        }
    }

    override fun connect() {
        if (state.busy) return
        val target = parseHostPort(state.connectAddress)
        if (target == null) {
            state = state.copy(connection = ConnectionStatus.Failed(AdbProblem.BAD_ADDRESS))
            return
        }
        work = viewModelScope.launch { doConnect(target.first, target.second, auto = false) }
    }

    /** [auto]: tried by itself on discovery; a failure there stays quiet (only "not paired yet" is shown). */
    private suspend fun doConnect(host: String, port: Int, auto: Boolean) {
        state = state.copy(connection = ConnectionStatus.Working, connectAddress = hostPort(host, port), install = InstallStatus.Idle)
        try {
            adb.connect(host, port)
            val info = adb.watchInfo()
            state = state.copy(connection = ConnectionStatus.Connected(info), notPaired = false)
            if (state.pair !is PairStatus.Paired) state = state.copy(pair = PairStatus.Idle)
            discovery.stop() // found; no more multicast while installing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { adb.disconnect() }
            val problem = classifyConnectError(e)
            state = if (auto) {
                state.copy(connection = ConnectionStatus.Idle, notPaired = state.notPaired || problem == AdbProblem.NOT_PAIRED)
            } else {
                state.copy(connection = ConnectionStatus.Failed(problem, describe(e)), notPaired = problem == AdbProblem.NOT_PAIRED)
            }
        }
    }

    override fun disconnect() {
        work?.cancel()
        thread(name = "adb-disconnect", isDaemon = true) { adb.disconnect() }
        state = state.copy(connection = ConnectionStatus.Idle, install = InstallStatus.Idle)
        discovery.start()
    }

    // ------------------------------------------------------------------ install

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
                        state = state.copy(install = InstallStatus.Done(version, relink = removed || info.installed == null))
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
                    discovery.start()
                }
            }
        }
    }

    override fun onCleared() {
        work?.cancel()
        discovery.stop()
        // Closing joins libadb's reader thread and deleting the APK copy is disk work: not on the main thread.
        thread(name = "adb-close", isDaemon = true) {
            adb.close()
            BundledWatchApk.clear(context)
        }
    }

    /** Screenshots and previews. */
    internal fun show(state: InstallerState) {
        this.state = state
    }

    private companion object {
        const val PAIR_TIMEOUT_MS = 30_000L
    }
}
