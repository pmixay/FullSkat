package com.fullskat.watch.data.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/** A web page an answer drew on: `sources` in the server's `done` event (docs/PROTOCOL.md). */
data class WebSource(val url: String, val title: String) {
    /** The site to show for it, e.g. "ria.ru" (see [WebSources.domainOf]). */
    val domain: String get() = WebSources.domainOf(url)
}

/** Parsing, validation and the JSON text stored with a message in Room. */
object WebSources {
    /** The server sends at most 8; anything beyond that is ignored. */
    const val MAX = 8

    /** Sources from the `sources` array of a `done` event; only http(s) links are kept. */
    fun parse(array: JSONArray?): List<WebSource> {
        if (array == null) return emptyList()
        val out = ArrayList<WebSource>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (!isWebUrl(url) || out.any { it.url == url }) continue
            out += WebSource(url, o.optString("title").trim())
            if (out.size == MAX) break
        }
        return out
    }

    /** JSON text for the `sources` column; empty when there are none, so old rows and new ones look alike. */
    fun encode(sources: List<WebSource>): String {
        if (sources.isEmpty()) return ""
        val array = JSONArray()
        sources.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title)) }
        return array.toString()
    }

    /** The column's text back as sources; blank or broken text means none. */
    fun decode(text: String): List<WebSource> {
        if (text.isBlank()) return emptyList()
        return runCatching { parse(JSONArray(text)) }.getOrDefault(emptyList())
    }

    /** Only http(s) links with a host are opened; anything else (javascript:, intent:, file:) is dropped. */
    fun isWebUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && host(url).isNotEmpty() && url.none { it.isWhitespace() }
    }

    /**
     * The host of [url] without "www.", lower case, internationalized domains in Unicode ("xn--80aswg.xn--p1ai" is
     * shown as "сайт.рф"); the URL itself if it has no host.
     */
    fun domainOf(url: String): String {
        val host = host(url).removePrefix("www.")
        if (host.isEmpty()) return url
        return runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)
    }

    private fun host(url: String): String {
        val authority = url.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase()
    }
}
