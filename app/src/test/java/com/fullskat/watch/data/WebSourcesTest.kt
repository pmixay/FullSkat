package com.fullskat.watch.data

import com.fullskat.watch.data.net.WebSource
import com.fullskat.watch.data.net.WebSources
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourcesTest {
    @Test fun domainIsTheHostWithoutWwwAndInUnicode() {
        assertEquals("ria.ru", WebSources.domainOf("https://www.ria.ru/20261124/news.html?x=1"))
        assertEquals("en.wikipedia.org", WebSources.domainOf("https://EN.Wikipedia.org/wiki/Moscow#History"))
        assertEquals("сайт.рф", WebSources.domainOf("https://xn--80aswg.xn--p1ai/page"))
        assertEquals("example.com", WebSources.domainOf("http://user:pw@example.com:8080/a"))
        assertEquals("not a url", WebSources.domainOf("not a url"))
    }

    @Test fun onlyHttpLinksAreAccepted() {
        assertTrue(WebSources.isWebUrl("https://example.com"))
        assertTrue(WebSources.isWebUrl("HTTP://example.com/a"))
        assertFalse(WebSources.isWebUrl("javascript:alert(1)"))
        assertFalse(WebSources.isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(WebSources.isWebUrl("file:///sdcard/x"))
        assertFalse(WebSources.isWebUrl("https://"))
        assertFalse(WebSources.isWebUrl("https://exa mple.com"))
    }

    @Test fun parseKeepsValidUniqueLinksUpToTheCap() {
        val array = JSONArray()
            .put(org.json.JSONObject().put("url", "https://a.example/").put("title", " A "))
            .put(org.json.JSONObject().put("url", "javascript:alert(1)").put("title", "bad"))
            .put(org.json.JSONObject().put("url", "https://a.example/").put("title", "dup"))
            .put("not an object")
            .put(org.json.JSONObject().put("url", "https://b.example/x"))
        assertEquals(listOf(WebSource("https://a.example/", "A"), WebSource("https://b.example/x", "")), WebSources.parse(array))
        val many = JSONArray().apply { repeat(12) { put(org.json.JSONObject().put("url", "https://s$it.example/")) } }
        assertEquals(WebSources.MAX, WebSources.parse(many).size)
        assertEquals(emptyList<WebSource>(), WebSources.parse(null))
    }

    @Test fun storedTextRoundTripsAndToleratesOldRows() {
        val sources = listOf(WebSource("https://a.example/", "Погода \"сегодня\""), WebSource("https://b.example/", ""))
        val text = WebSources.encode(sources)
        assertEquals(sources, WebSources.decode(text))
        assertEquals("", WebSources.encode(emptyList()))
        assertEquals(emptyList<WebSource>(), WebSources.decode("")) // rows from before v3
        assertEquals(emptyList<WebSource>(), WebSources.decode("{broken"))
    }
}
