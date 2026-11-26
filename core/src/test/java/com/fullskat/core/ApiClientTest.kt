package com.fullskat.core

import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.ChatEvent
import com.fullskat.core.net.ChatRequest
import com.fullskat.core.net.LinkStatus
import com.fullskat.core.net.LiveEvent
import com.fullskat.core.net.Prefs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The v2 client against a scripted server: request shapes and parsing (docs/PROTOCOL.md). */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(server.url("").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.close()

    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val b = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun sse(body: String) =
        server.enqueue(MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "text/event-stream").build())

    private val modelInfo = """"model":"claude-haiku-5-5","models":["claude-haiku-5-5","k3.1-pro"],"effort":"medium",
        "model_efforts":{"claude-haiku-5-5":{"efforts":["low","medium","high"],"default":"medium"},"k3.1-pro":{"efforts":[],"default":""}},
        "providers":{"anthropic":{"ok":true,"reason":"","models":["claude-haiku-5-5"],"usage":{"remaining":0.4,"label":"$4"}},
                     "kimi":{"ok":false,"reason":"upstream_auth","models":[],"usage":null}},
        "web_search":true,"figures":false"""

    @Test fun registerSendsTheAccountAndParsesTheSession() = runBlocking {
        json(201, """{"token":"tok","first_setup":true,"server_name":"FullSkat","user":{"id":"u_1","name":"Миша"},
            "device":{"id":"dev","kind":"phone","name":"Pixel"},$modelInfo}""")
        val s = api.register("1234", "Миша", "dev", "Pixel", "phone")
        val req = server.takeRequest()
        assertEquals("/v1/register", req.url.encodedPath)
        val body = JSONObject(req.body!!.utf8())
        assertEquals("1234", body.getString("passcode"))
        assertEquals("phone", body.getString("device_kind"))
        assertEquals("tok", s.token)
        assertTrue(s.firstSetup)
        assertEquals("Миша", s.user.name)
        assertEquals(listOf("claude-haiku-5-5", "k3.1-pro"), s.info.models)
        assertEquals(listOf("anthropic"), s.info.providers.map { it.id }) // only working providers
        assertEquals(0.4f, s.info.providers[0].usageRemaining)
        assertEquals(emptyList<String>(), s.info.modelEfforts["k3.1-pro"]!!.efforts)
        assertFalse(s.info.figures)
    }

    @Test fun errorsMapToExceptions() = runBlocking {
        json(401, """{"error":"invalid_passcode","message":"no"}""")
        assertTrue(runCatching { api.register("1", "a", "d", "n", "phone") }.exceptionOrNull() is ApiException.WrongPasscode)
        json(429, """{"error":"locked","message":"wait"}""", mapOf("Retry-After" to "900"))
        val locked = runCatching { api.register("1", "a", "d", "n", "phone") }.exceptionOrNull()
        assertTrue(locked is ApiException.Locked && locked.retryAfterSec == 900)
        json(404, """{"error":"invalid_code","message":"wrong code"}""")
        val wrong = runCatching { api.linkClaim("tok", "AAAAAA") }.exceptionOrNull()
        assertTrue(wrong is ApiException.NotFound && wrong.code == "invalid_code")
        json(401, """{"error":"invalid_token","message":"gone"}""")
        assertTrue(runCatching { api.me("tok") }.exceptionOrNull() is ApiException.Unauthorized)
        json(429, """{"error":"daily_limit","message":"enough"}""", mapOf("Retry-After" to "3600"))
        val daily = runCatching { api.sync("tok", 0) }.exceptionOrNull()
        assertTrue(daily is ApiException.RateLimited && daily.code == "daily_limit" && daily.retryAfterSec == 3600)
    }

    @Test fun linkingStartsPollsAndFinishes() = runBlocking {
        json(200, """{"link_id":"secret","code":"K7Q2ZP","expires_in":23,"period":30}""")
        json(200, """{"status":"pending","code":"XA3M9Q","expires_in":30,"period":30}""")
        json(200, """{"status":"linked","token":"wtok","user":{"id":"u_1","name":"Миша"},"device":{"id":"w","kind":"watch","name":"Watch"},$modelInfo}""")
        val start = api.linkStart("w", "Watch", "watch")
        assertEquals("K7Q2ZP", start.code)
        assertEquals(23, start.expiresIn)
        val pending = api.linkStatus(start.linkId)
        assertEquals(LinkStatus.Pending("XA3M9Q", 30, 30), pending)
        val linked = api.linkStatus(start.linkId) as LinkStatus.Linked
        assertEquals("wtok", linked.session.token)
        assertEquals("watch", linked.session.device.kind)
        server.takeRequest()
        val poll = server.takeRequest()
        assertEquals("secret", poll.url.queryParameter("link_id"))
        assertEquals("25", poll.url.queryParameter("wait"))
        assertNull(poll.headers["Authorization"])
    }

    @Test fun syncParsesDialogsMessagesAndPrefs() = runBlocking {
        json(200, """{"dialogs":[{"id":"d1","title":"Погода","created_at":1,"updated_at":2,"deleted":false,"seq":5}],
            "messages":[{"id":"m1","dialog_id":"d1","role":"assistant","content":"Солнечно","thinking":"",
              "sources":[{"url":"https://ya.ru/x","title":"Яндекс"},{"url":"javascript:alert(1)","title":"bad"}],
              "attachments":[{"id":"a_1","mime":"image/jpeg","width":10,"height":20}],"model":"claude-haiku-5-5",
              "status":"done","created_at":3,"updated_at":4,"deleted":false,"seq":6}],
            "prefs":{"model":null,"effort":"high","web_search":false,"figures":true},"seq":6,"more":true}""")
        val page = api.sync("tok", 4)
        assertEquals("/v1/sync", server.takeRequest().url.encodedPath)
        assertEquals("Погода", page.dialogs.single().title)
        val m = page.messages.single()
        assertEquals(listOf("https://ya.ru/x"), m.sources.map { it.url }) // only http(s)
        assertEquals("a_1", m.attachments.single().id)
        assertEquals(Prefs(model = null, effort = "high", webSearch = false, figures = true), page.prefs)
        assertEquals(6L, page.seq)
        assertTrue(page.more)
    }

    @Test fun putPrefsSendsChangesAndNullsForCleared() = runBlocking {
        json(200, """{"prefs":{"model":"claude-opus-5-5","effort":null,"web_search":true,"figures":true},"updated_at":1}""")
        val saved = api.putPrefs("tok", Prefs(model = "claude-opus-5-5"), clear = setOf("effort"))
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertTrue(body.isNull("effort"))
        assertFalse(body.has("web_search"))
        assertEquals("claude-opus-5-5", saved.model)
        assertNull(saved.effort)
    }

    @Test fun chatStreamsEventsAndEndsAtDone() = runBlocking {
        sse(
            "event: thinking\ndata: {\"text\":\"Хм. \"}\n\n" +
                "event: delta\ndata: {\"text\":\"При\"}\n\n" +
                ": keep-alive\n\n" +
                "event: delta\ndata: {\"text\":\"вет\"}\n\n" +
                "event: done\ndata: {\"text\":\"Привет\",\"model\":\"m\",\"thinking\":\"Хм. \",\"sources\":[],\"seq\":9," +
                "\"message\":{\"id\":\"ans\",\"dialog_id\":\"d\",\"role\":\"assistant\",\"content\":\"Привет\",\"status\":\"done\",\"seq\":9}}\n\n" +
                "event: delta\ndata: {\"text\":\"never read\"}\n\n",
        )
        val events = api.chat("tok", ChatRequest("d", "q", "Привет?", listOf("a_1"), 5, "ans", "ru-RU", webSearch = false)).toList()
        assertEquals(listOf(ChatEvent.Thinking("Хм. "), ChatEvent.Delta("При"), ChatEvent.Delta("вет")), events.dropLast(1))
        val done = events.last() as ChatEvent.Done
        assertEquals("ans", done.message!!.id)
        assertEquals(9L, done.seq)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("ans", body.getString("answer_id"))
        assertEquals("q", body.getJSONObject("message").getString("id"))
        assertEquals("a_1", body.getJSONObject("message").getJSONArray("attachments").getString(0))
        assertFalse(body.getBoolean("web_search"))
        assertFalse(body.has("model")) // null = the user's prefs on the server
    }

    @Test fun eventsStreamReportsChangesAndRevocation() = runBlocking {
        sse("event: ping\ndata: {}\n\nevent: changed\ndata: {\"seq\":42}\n\nevent: revoked\ndata: {}\n\nevent: changed\ndata: {\"seq\":43}\n\n")
        assertEquals(listOf(LiveEvent.Changed(42), LiveEvent.Revoked), api.events("tok").toList())
    }
}
