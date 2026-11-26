package com.fullskat.phone.watchinstall

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A watch this phone paired with. The watch keeps trusting this phone's ADB key (AdbKeys), so the phone remembers the
 * watch too: next time it connects by itself as soon as the watch's wireless debugging shows up on the Wi-Fi.
 */
data class PairedWatch(
    /** This record's own id, stable across updates. */
    val key: String,
    /** The wireless-debugging service name (`adb-<serial>-<id>`, the same for pairing and connecting); null when paired by address. */
    val serviceName: String? = null,
    /** The watch's serial (`ro.serialno`, also part of the service name); null until known. */
    val serial: String? = null,
    /** What the user sees: the watch's own name ("Galaxy Watch7 (D4F2)") once connected, before that its serial or address. */
    val name: String,
    /** The address last used; wireless debugging keeps it while the watch stays on the same Wi-Fi. */
    val host: String,
    /** The connect port last used; 0 when not known. It changes each time wireless debugging is switched on. */
    val port: Int = 0,
    val pairedAt: Long,
    val lastUsedAt: Long,
) {
    /** Whether a wireless-debugging service found on the Wi-Fi is this watch's. */
    fun matches(service: WatchService): Boolean =
        (serviceName != null && sameWatch(service.name, serviceName)) ||
            (serial != null && service.serial == serial) ||
            (serviceName == null && serial == null && service.host == host)

    /** The same physical watch as [other] (a newer pairing replaces the older record). */
    fun sameWatchAs(other: PairedWatch): Boolean =
        key == other.key ||
            (serial != null && serial == other.serial) ||
            (serviceName != null && other.serviceName != null && sameWatch(serviceName, other.serviceName))
}

/** At most this many watches are remembered; the least recently used is dropped first. */
const val MAX_PAIRED_WATCHES = 8

/** [watch] first (it was used last), replacing any older record of the same watch. */
fun List<PairedWatch>.remember(watch: PairedWatch): List<PairedWatch> =
    (listOf(watch) + filterNot { it.sameWatchAs(watch) }).take(MAX_PAIRED_WATCHES)

fun List<PairedWatch>.forget(watch: PairedWatch): List<PairedWatch> = filterNot { it.key == watch.key }

/** The address `host:port` a connection attempt is remembered by, so each is made once. */
fun attemptKey(host: String, port: Int): String = hostPort(host, port)

/**
 * What to connect to next: the first service on the Wi-Fi that belongs to one of [watches] (most recently used watch
 * first, newest service first) and was not tried yet ([tried] holds [attemptKey]s).
 */
fun nextConnectTarget(services: List<WatchService>, watches: List<PairedWatch>, tried: Set<String>): Pair<WatchService, PairedWatch>? {
    for (watch in watches) {
        val service = services.lastOrNull { watch.matches(it) && attemptKey(it.host, it.port) !in tried } ?: continue
        return service to watch
    }
    return null
}

fun encodePairedWatches(watches: List<PairedWatch>): String = JSONArray().apply {
    for (w in watches) {
        put(
            JSONObject()
                .put("key", w.key)
                .put("serviceName", w.serviceName ?: JSONObject.NULL)
                .put("serial", w.serial ?: JSONObject.NULL)
                .put("name", w.name)
                .put("host", w.host)
                .put("port", w.port)
                .put("pairedAt", w.pairedAt)
                .put("lastUsedAt", w.lastUsedAt),
        )
    }
}.toString()

/** The watches saved by [encodePairedWatches]; unreadable records (or text) are skipped, never fatal. */
fun decodePairedWatches(text: String): List<PairedWatch> {
    val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        runCatching {
            val o = array.getJSONObject(i)
            fun opt(name: String): String? = if (o.isNull(name)) null else o.optString(name).takeIf { it.isNotBlank() }
            PairedWatch(
                key = o.getString("key"),
                serviceName = opt("serviceName"),
                serial = opt("serial"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 0),
                pairedAt = o.optLong("pairedAt", 0),
                lastUsedAt = o.optLong("lastUsedAt", 0),
            )
        }.getOrNull()
    }
}

/**
 * The paired watches as a small JSON file next to the ADB key in no-backup storage: the pairing belongs to that key, so
 * a phone restored from a backup (with a new key) starts with an empty list and pairs again.
 */
class PairedWatchStore(private val file: File) {
    @Synchronized
    fun load(): List<PairedWatch> = if (file.exists()) decodePairedWatches(runCatching { file.readText() }.getOrDefault("")) else emptyList()

    @Synchronized
    fun save(watches: List<PairedWatch>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encodePairedWatches(watches))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Cannot save $file" }
        }
    }
}
