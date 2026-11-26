package com.fullskat.phone.watchinstall

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds watches with wireless debugging on the Wi-Fi through mDNS: the pairing service (advertised while "Pair new
 * device" is open on the watch) and the connect service (while wireless debugging is on; its port changes each time it
 * is switched on). Services of this phone itself are skipped. Callbacks are handled on the main thread.
 */
class AdbDiscovery(context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _pairing = MutableStateFlow<List<WatchService>>(emptyList())
    private val _connect = MutableStateFlow<List<WatchService>>(emptyList())
    val pairing: StateFlow<List<WatchService>> = _pairing.asStateFlow()
    val connect: StateFlow<List<WatchService>> = _connect.asStateFlow()

    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, MutableStateFlow<List<WatchService>>>>()
    private var resolving = false
    private var generation = 0

    val running: Boolean get() = listeners.isNotEmpty()

    fun start() {
        val nsd = nsd ?: return
        if (listeners.isNotEmpty()) return
        generation++
        listeners += discover(nsd, PAIRING_TYPE, _pairing)
        listeners += discover(nsd, CONNECT_TYPE, _connect)
    }

    fun stop() {
        val nsd = nsd ?: return
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        resolveQueue.clear()
        generation++
        _pairing.value = emptyList()
        _connect.value = emptyList()
    }

    private fun discover(nsd: NsdManager, type: String, into: MutableStateFlow<List<WatchService>>): NsdManager.DiscoveryListener {
        val gen = generation // callbacks of a stopped discovery may still arrive: ignore them
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery of $serviceType failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (gen != generation) return@post
                    resolveQueue.addLast(info to into)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (gen == generation) into.update { list -> list.filterNot { it.name == info.serviceName } } }
            }
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "Cannot discover $type", it) }
        return listener
    }

    /** Android before 14 resolves one service at a time: resolve them in turn. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val nsd = nsd ?: return
        if (resolving) return
        val (info, into) = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val started = generation
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && started == generation) {
                        main.postDelayed({ resolveQueue.addLast(info to into); resolveNext() }, 300)
                    } else {
                        resolveNext()
                    }
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (started == generation) {
                        val host = hostOf(serviceInfo)
                        if (host != null && serviceInfo.port > 0 && !isLocal(host)) {
                            val service = WatchService(serviceInfo.serviceName, host.hostAddress.orEmpty(), serviceInfo.port)
                            into.update { list -> list.filterNot { it.name == service.name } + service }
                        }
                    }
                    resolveNext()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    }

    private fun isLocal(address: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif -> nif.inetAddresses.toList().any { it == address } }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AdbDiscovery"
        const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
        const val CONNECT_TYPE = "_adb-tls-connect._tcp"
    }
}
