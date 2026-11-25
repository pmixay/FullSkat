package com.fullskat.phone.watchinstall

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

// The pure parts of the watch installer: what the watch's tools print, addresses typed by hand, mDNS service names and
// how failures read. Kept free of Android classes so they are tested on the JVM (WatchInstallTest).

/** The package the bundled watch app installs as. */
const val WATCH_PACKAGE = "com.fullskat.watch"

/** An app version as Android sees it. */
data class AppVersion(val name: String, val code: Long) {
    /** "v2.0 (7)"; just the name when it already ends with the code ("1.0.42" for 42, as CI numbers builds). */
    val label: String get() = when {
        name.isBlank() -> "$code"
        name == "$code" || name.endsWith(".$code") -> name
        else -> "$name ($code)"
    }
}

/** A wireless-debugging service found on the Wi-Fi: the pairing one (open while "Pair new device" is) or the connect one. */
data class WatchService(val name: String, val host: String, val port: Int) {
    val address: String get() = hostPort(host, port)

    /** The serial number in the service name, when the watch named it the standard way (`adb-<serial>-<suffix>`). */
    val serial: String? get() = serialOf(name)
}

/** What the watch says about itself once connected. */
data class WatchInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdk: Int,
    val abis: List<String>,
    /** FullSkat on the watch; null when it is not installed. */
    val installed: AppVersion?,
)

/** Why pairing or connecting failed, in terms the screen explains. */
enum class AdbProblem { WRONG_CODE, TIMEOUT, UNREACHABLE, REFUSED, NOT_PAIRED, LOST, NO_TLS, BAD_ADDRESS, OTHER }

/** Why the package manager refused the APK. */
enum class InstallError { UPDATE_INCOMPATIBLE, INSUFFICIENT_STORAGE, VERSION_DOWNGRADE, NO_MATCHING_ABIS, OLDER_SDK, INVALID_APK, LOST, OTHER }

/** The outcome of `cmd package install` as printed: `Success`, `Failure [CODE: message]`, or nothing usable. */
sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failure(val error: InstallError, val code: String, val detail: String) : InstallOutcome
}

/** What the install button does, given the bundled app and the one on the watch. */
enum class InstallAction { INSTALL, UPDATE, REINSTALL, DOWNGRADE }

private val INSTALL_FAILURE = Regex("""Failure \[([A-Z0-9_]+)(?::\s*([^\]]*))?]""")

/** True once the output of `cmd package install`/`uninstall` holds its final line. */
fun installFinished(output: String): Boolean = output.endsWith("\n") &&
    output.lineSequence().map { it.trim() }.any { it == "Success" || it.startsWith("Failure") || it.startsWith("Error:") }

fun parseInstallOutput(output: String): InstallOutcome {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.any { it == "Success" }) return InstallOutcome.Success
    lines.firstNotNullOfOrNull { INSTALL_FAILURE.find(it) }?.let { m ->
        val code = m.groupValues[1]
        val message = m.groupValues[2].trim()
        return InstallOutcome.Failure(installError(code), code, if (message.isEmpty()) code else "$code: $message")
    }
    val error = lines.firstOrNull { it.startsWith("Error") || it.startsWith("Exception") }
    return if (error != null) InstallOutcome.Failure(InstallError.OTHER, "", error)
    else InstallOutcome.Failure(InstallError.LOST, "", lines.lastOrNull().orEmpty())
}

fun installError(code: String): InstallError = when (code) {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" -> InstallError.UPDATE_INCOMPATIBLE
    "INSTALL_FAILED_INSUFFICIENT_STORAGE", "INSTALL_FAILED_MEDIA_UNAVAILABLE" -> InstallError.INSUFFICIENT_STORAGE
    "INSTALL_FAILED_VERSION_DOWNGRADE" -> InstallError.VERSION_DOWNGRADE
    "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> InstallError.NO_MATCHING_ABIS
    "INSTALL_FAILED_OLDER_SDK" -> InstallError.OLDER_SDK
    "INSTALL_FAILED_INVALID_APK", "INSTALL_PARSE_FAILED_NOT_APK", "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
    "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION", "INSTALL_PARSE_FAILED_BAD_MANIFEST", "INSTALL_FAILED_BAD_SIGNATURE",
    "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> InstallError.INVALID_APK
    else -> InstallError.OTHER
}

/** True when `cmd package uninstall` removed the app or it was not there in the first place. */
fun uninstallSucceeded(output: String): Boolean =
    output.lines().any { it.trim() == "Success" } || output.contains("not installed for", ignoreCase = true)

/** FullSkat's version from `dumpsys package <pkg>`; null when the package section is missing (not installed). */
fun parseInstalledVersion(dumpsys: String, pkg: String = WATCH_PACKAGE): AppVersion? {
    val start = dumpsys.indexOf("Package [$pkg]")
    if (start < 0) return null
    var code: Long? = null
    var name: String? = null
    for (line in dumpsys.substring(start).lineSequence().drop(1)) {
        val t = line.trim()
        if (t.startsWith("Package [") || (t.isNotEmpty() && !line.startsWith(" "))) break // the next package or section
        if (code == null) Regex("""(?:^|\s)versionCode=(\d+)""").find(t)?.let { code = it.groupValues[1].toLongOrNull() }
        if (name == null && t.startsWith("versionName=")) name = t.removePrefix("versionName=").trim()
        if (code != null && name != null) break
    }
    return if (code == null && name == null) null else AppVersion(name.orEmpty(), code ?: 0)
}

/** `getprop` output (`[key]: [value]` lines) as a map. */
fun parseGetprop(text: String): Map<String, String> {
    val line = Regex("""^\[([^\]]+)]:\s*\[(.*)]\s*$""")
    return text.lineSequence().mapNotNull { line.find(it.trim()) }.associate { it.groupValues[1] to it.groupValues[2] }
}

/** The watch's details from its properties and the dumpsys of FullSkat. */
fun watchInfo(props: Map<String, String>, dumpsys: String): WatchInfo = WatchInfo(
    model = props["ro.product.model"].orEmpty().ifBlank { props["ro.product.device"].orEmpty() },
    manufacturer = props["ro.product.manufacturer"].orEmpty(),
    androidVersion = props["ro.build.version.release"].orEmpty(),
    sdk = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
    abis = props["ro.product.cpu.abilist"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOfNotNull(props["ro.product.cpu.abi"]?.takeIf { it.isNotBlank() }) },
    installed = parseInstalledVersion(dumpsys),
)

/** The Wear OS release a watch's Android API level belongs to; null when unknown. */
fun wearOsVersion(sdk: Int): String? = when (sdk) {
    30 -> "3"
    33 -> "4"
    34 -> "5"
    35 -> "5.1"
    36 -> "6"
    else -> null
}

fun installAction(bundled: AppVersion, installed: AppVersion?): InstallAction = when {
    installed == null -> InstallAction.INSTALL
    installed.code < bundled.code -> InstallAction.UPDATE
    installed.code > bundled.code -> InstallAction.DOWNGRADE
    else -> InstallAction.REINSTALL
}

/** A host and port typed by hand: `192.168.1.23:41235`, `[fe80::1]:5555`, `watch.local:5555`; null when incomplete. */
fun parseHostPort(text: String): Pair<String, Int>? {
    val t = text.trim().replace(" ", "")
    if (t.isEmpty()) return null
    val (host, port) = if (t.startsWith("[")) {
        val end = t.indexOf("]:")
        if (end < 0) return null
        t.substring(1, end) to t.substring(end + 2)
    } else {
        val colon = t.lastIndexOf(':')
        if (colon <= 0 || t.indexOf(':') != colon) return null
        t.substring(0, colon) to t.substring(colon + 1)
    }
    val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return if (isPlausibleHost(host)) host to p else null
}

/** An IPv4/IPv6 literal or a host name, as far as one can tell without resolving it. */
fun isPlausibleHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (host.contains(':')) return host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' || it == '_' }
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() <= 255 }
    }
    return host.split('.').all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } }
}

fun hostPort(host: String, port: Int): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

/** `adb-R5CT1234567-AbCdEf` (maybe with a " (2)" added on name clashes) to `R5CT1234567`; null for other names. */
fun serialOf(serviceName: String): String? {
    val name = serviceName.trim().replace(Regex("""\s+\(\d+\)$"""), "")
    if (!name.startsWith("adb-")) return null
    val rest = name.removePrefix("adb-")
    val dash = rest.lastIndexOf('-')
    return (if (dash > 0) rest.substring(0, dash) else rest).takeIf { it.isNotEmpty() }
}

/** The same watch's two services share the name (its persistent wireless-debugging id). */
fun sameWatch(a: String, b: String): Boolean =
    a.trim().replace(Regex("""\s+\(\d+\)$"""), "") == b.trim().replace(Regex("""\s+\(\d+\)$"""), "")

/** Keeps six digits of whatever was typed or pasted into the pairing code field. */
fun filterPairingCode(raw: String): String = raw.filter { it in '0'..'9' }.take(6)

private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { e -> e.cause?.takeIf { it !== e } }

/** A socket that could not be opened: wrong address, a different network, or nothing listening. */
private fun socketProblem(e: Throwable): AdbProblem? {
    for (c in e.chain()) {
        val message = c.message.orEmpty()
        when (c) {
            is UnknownHostException -> return AdbProblem.BAD_ADDRESS
            is NoRouteToHostException -> return AdbProblem.UNREACHABLE
            is SocketTimeoutException -> return AdbProblem.TIMEOUT
            is ConnectException -> return when {
                message.contains("refused", ignoreCase = true) -> AdbProblem.REFUSED
                message.contains("timed out", ignoreCase = true) -> AdbProblem.TIMEOUT
                else -> AdbProblem.UNREACHABLE
            }
            is NoSuchAlgorithmException -> return AdbProblem.NO_TLS
        }
        if (message.contains("ENETUNREACH") || message.contains("EHOSTUNREACH")) return AdbProblem.UNREACHABLE
    }
    return null
}

/** Pairing failures: after the TLS handshake the watch hangs up on a wrong code (EOF / no peer info). */
fun classifyPairError(e: Throwable): AdbProblem {
    socketProblem(e)?.let { return it }
    for (c in e.chain()) {
        val message = c.message.orEmpty()
        if (c is EOFException) return AdbProblem.WRONG_CODE
        if (message.contains("peer info", ignoreCase = true) || message.contains("Exchanging message", ignoreCase = true)) return AdbProblem.WRONG_CODE
        if (c is SSLHandshakeException) return AdbProblem.OTHER
        if (c is SocketException && message.contains("reset", ignoreCase = true)) return AdbProblem.WRONG_CODE
    }
    return AdbProblem.OTHER
}

/** Connection failures: once the port answered, a refused TLS handshake means this phone is not paired. */
fun classifyConnectError(e: Throwable): AdbProblem {
    for (c in e.chain()) {
        if (c is AdbPairingRequiredException || c is AdbAuthenticationFailedException) return AdbProblem.NOT_PAIRED
    }
    socketProblem(e)?.let { return it }
    for (c in e.chain()) {
        val message = c.message.orEmpty()
        if (message.contains("Connection failed") || c is SSLHandshakeException) return AdbProblem.NOT_PAIRED
        if (message.contains("Stream closed") || message.contains("Not connected") || message.contains("closed", ignoreCase = true)) return AdbProblem.LOST
    }
    return AdbProblem.OTHER
}

/** A short technical line for the "other" errors. */
fun describe(e: Throwable): String {
    val root = e.chain().last()
    val message = root.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() }
    return listOfNotNull(root.javaClass.simpleName, message).joinToString(": ")
}
