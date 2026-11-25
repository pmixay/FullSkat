package com.fullskat.phone.watchinstall

import android.os.SystemClock
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.NoSuchAlgorithmException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * An ADB client for the watch over wireless debugging (libadb-android): pairing with the 6-digit code, the TLS
 * connection, shell commands and a streamed `cmd package install`.
 *
 * libadb's AbsAdbConnectionManager holds one lock through connecting, opening streams and disconnecting, so a hung
 * connection could not be abandoned; this uses the public AdbConnection and PairingConnectionCtx it wraps instead and
 * closes the socket itself. Every call blocks a thread; all of them are cancellable: cancelling closes the connection
 * (or, for pairing, stops waiting for it). One instance serves one screen; [close] ends it.
 *
 * TLS 1.3 (both for pairing and for the connection) comes from the bundled Conscrypt: libadb instantiates
 * `org.conscrypt.OpenSSLProvider` itself by reflection and calls `Conscrypt.exportKeyingMaterial`, so nothing is
 * registered as a global security provider (the app's own HTTPS keeps the platform's TLS).
 */
class WatchAdb(private val keyDir: File, private val deviceName: String) {
    private val keys: AdbKeys by lazy { AdbKeys.load(keyDir) }
    private val connection = AtomicReference<AdbConnection?>(null)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "adb-watchdog").apply { isDaemon = true } }

    val isConnected: Boolean
        get() = connection.get()?.let { it.isConnected && it.isConnectionEstablished } == true

    /** Pairs with the code shown under "Pair new device" on the watch; throws when the watch refuses it. */
    suspend fun pair(host: String, port: Int, code: String) {
        detached("adb-pair") { _ ->
            ensureTls()
            PairingConnectionCtx(host, port, code.toByteArray(Charsets.UTF_8), keys.privateKey, keys.certificate, deviceName).use { it.start() }
        }
    }

    /**
     * Connects to the watch's wireless-debugging port; throws when it cannot be reached or does not accept this phone
     * (not paired). Replaces an earlier connection.
     */
    suspend fun connect(host: String, port: Int) {
        disconnect()
        val conn = detached("adb-connect") { cancelled ->
            ensureTls()
            // The library opens its socket without a timeout: find out quickly whether anything listens there at all.
            Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
            val c = AdbConnection.Builder(host, port)
                .setApi(API_WIRELESS_DEBUGGING)
                .setPrivateKey(keys.privateKey)
                .setCertificate(keys.certificate)
                .setDeviceName(deviceName)
                .build()
            cancelled.register { c.closeQuietly() }
            if (!c.connect(CONNECT_TIMEOUT_S, TimeUnit.SECONDS, false)) {
                c.closeQuietly()
                throw SocketTimeoutException("The watch did not complete the connection")
            }
            c
        }
        connection.set(conn)
    }

    /** Runs [command] in the watch's shell and returns what it printed. */
    suspend fun shell(command: String, idleTimeoutMs: Long = SHELL_IDLE_MS): String =
        // The end marker tells when the output is complete without waiting for the stream to close.
        exchange("shell:$command; echo $END_MARK", write = null, done = { it.contains(END_MARK) }, idleTimeoutMs).substringBefore(END_MARK)

    /** The watch's model, system and the FullSkat installed on it. */
    suspend fun watchInfo(): WatchInfo = watchInfo(parseGetprop(shell("getprop")), shell("dumpsys package $WATCH_PACKAGE"))

    suspend fun installedVersion(): AppVersion? = parseInstalledVersion(shell("dumpsys package $WATCH_PACKAGE"))

    /**
     * Streams [apk] into `cmd package install -r` ([onProgress] gets the bytes sent so far, on the sending thread) and
     * returns what the package manager answered.
     */
    suspend fun install(apk: File, onProgress: (Long) -> Unit): InstallOutcome {
        val size = apk.length()
        val output = exchange(
            "exec:cmd package install -r -S $size",
            write = { conn, stream ->
                val out = stream.openOutputStream()
                // libadb sends one packet per write and waits for the watch's OKAY only between writes: a write must not
                // exceed the packet size the watch accepts.
                val chunk = ByteArray(minOf(conn.maxData, MAX_CHUNK))
                var sent = 0L
                apk.inputStream().use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (Thread.interrupted()) throw InterruptedException()
                        out.write(chunk, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
                out.flush()
            },
            done = ::installFinished,
            idleTimeoutMs = INSTALL_IDLE_MS, // verification and compilation on the watch take a while
        )
        return parseInstallOutput(output)
    }

    /** Removes FullSkat from the watch; true when it is gone. */
    suspend fun uninstall(pkg: String = WATCH_PACKAGE): Boolean =
        uninstallSucceeded(exchange("exec:cmd package uninstall $pkg", write = null, done = ::installFinished, SHELL_IDLE_MS))

    fun disconnect() {
        connection.getAndSet(null)?.closeQuietly()
    }

    /** Disconnects and stops the helper thread. */
    fun close() {
        disconnect()
        watchdog.shutdownNow()
    }

    private suspend fun exchange(
        destination: String,
        write: ((AdbConnection, AdbStream) -> Unit)?,
        done: (String) -> Boolean,
        idleTimeoutMs: Long,
    ): String = runInterruptible(Dispatchers.IO) {
        val conn = connection.get()?.takeIf { it.isConnected } ?: throw IOException("Not connected to ADB.")
        val stream = conn.open(destination)
        try {
            if (write != null) {
                try {
                    write(conn, stream)
                } catch (e: IOException) {
                    // The command may have stopped reading and said why (e.g. an early failure): read that.
                    if (!stream.isClosed) throw e
                }
            }
            readOutput(stream, done, idleTimeoutMs)
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Reads the stream's text until [done] accepts it, the stream ends or nothing arrives for [idleTimeoutMs]. (libadb
     * can block forever on the read after a stream's last data, so the end is recognised from the text itself, and a
     * watchdog closes a silent stream.)
     */
    private fun readOutput(stream: AdbStream, done: (String) -> Boolean, idleTimeoutMs: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        val input = stream.openInputStream()
        val last = AtomicLong(SystemClock.elapsedRealtime())
        val dog = watchdog.scheduleWithFixedDelay({
            if (SystemClock.elapsedRealtime() - last.get() > idleTimeoutMs) runCatching { stream.close() }
        }, 250, 250, TimeUnit.MILLISECONDS)
        try {
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    if (stream.isClosed) -1 else throw e
                }
                if (n < 0) break
                if (n == 0) continue
                out.write(buf, 0, n)
                last.set(SystemClock.elapsedRealtime())
                if (done(out.toString("UTF-8"))) break
            }
        } finally {
            dog.cancel(false)
        }
        return out.toString("UTF-8")
    }

    private fun ensureTls() {
        if (!Conscrypt.isAvailable()) throw NoSuchAlgorithmException("Conscrypt is not available on this phone")
    }

    /** Lets a blocking call learn that its caller gave up, to close what it opened. */
    private class Cancellation {
        private val cancelled = AtomicBoolean(false)
        private val actions = mutableListOf<() -> Unit>()

        fun register(action: () -> Unit) {
            val runNow = synchronized(actions) {
                if (!cancelled.get()) actions += action
                cancelled.get()
            }
            if (runNow) action()
        }

        fun cancel() {
            cancelled.set(true)
            synchronized(actions) { actions.toList().also { actions.clear() } }.forEach { runCatching(it) }
        }
    }

    /**
     * Runs a blocking call on its own thread. On cancellation the caller stops waiting at once; the call's registered
     * clean-ups run (closing its socket), and whatever it still returns is closed and dropped.
     */
    private suspend fun <T> detached(name: String, block: (Cancellation) -> T): T = suspendCancellableCoroutine { cont ->
        val cancellation = Cancellation()
        val worker = thread(name = name, isDaemon = true, start = false) {
            val result = runCatching { block(cancellation) }
            if (!cont.isActive) (result.getOrNull() as? AdbConnection)?.closeQuietly()
            cont.resumeWith(result)
        }
        cont.invokeOnCancellation {
            worker.interrupt()
            thread(name = "$name-cancel", isDaemon = true) { cancellation.cancel() }
        }
        worker.start()
    }

    private fun AdbConnection.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        /** Wireless debugging exists from Android 11; libadb picks the protocol version and packet size from this. */
        private const val API_WIRELESS_DEBUGGING = 30
        private const val PROBE_TIMEOUT_MS = 4_000
        private const val CONNECT_TIMEOUT_S = 15L
        private const val SHELL_IDLE_MS = 15_000L
        private const val INSTALL_IDLE_MS = 180_000L
        private const val MAX_CHUNK = 256 * 1024
        private const val END_MARK = "__fullskat_end__"
    }
}
