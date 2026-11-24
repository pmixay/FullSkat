package com.fullskat.core

import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.ChatEvent
import com.fullskat.core.net.ChatRequest
import com.fullskat.core.net.LinkStatus
import com.fullskat.core.net.LiveEvent
import com.fullskat.core.net.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * The Kotlin client against the real Python server (protocol v2): a phone signs up, a watch links with a code,
 * the phone asks with a photo, the watch syncs the dialog, preferences travel back, unlinking ends the watch's
 * session. Needs a running server with a scripted model:
 *
 *     python3 tools/interop/fake_server.py 8765 /tmp/fullskat-interop &
 *     ./gradlew :core:testDebugUnitTest -Pinterop=http://127.0.0.1:8765 --tests '*ServerInteropTest*'
 */
class ServerInteropTest {
    private val url: String? = System.getProperty("fullskat.interop.url")

    private fun jpeg(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) img.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x40)
        return ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
    }

    @Test fun phoneAndWatchShareOneAccountThroughTheServer() = runBlocking {
        assumeTrue("set -Pinterop=<server url>", url != null)
        val api = ApiClient(url!!)
        val phoneId = UUID.randomUUID().toString()
        val watchId = UUID.randomUUID().toString()

        // 1. a phone signs up (the first one sets the passcode)
        val phone = try {
            api.register("2468", "Миша", phoneId, "Pixel 9", "phone")
        } catch (e: ApiException.WrongPasscode) {
            throw AssertionError("start the interop server with an empty data dir", e)
        }
        assertEquals("Миша", phone.user.name)
        assertEquals("phone", phone.device.kind)
        assertTrue(phone.info.models.isNotEmpty())

        // 2. a watch shows a code, the phone enters it, the watch's long-poll returns its session
        val start = api.linkStart(watchId, "Galaxy Watch7", "watch")
        assertEquals(6, start.code.length)
        assertTrue(start.code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        assertTrue(start.expiresIn in 1..30)
        val claimed = api.linkClaim(phone.token, start.code.lowercase()) // case does not matter
        assertEquals(watchId, claimed.id)
        val linked = withTimeout(30_000) {
            var st = api.linkStatus(start.linkId, waitSec = 2)
            while (st !is LinkStatus.Linked) st = api.linkStatus(start.linkId, waitSec = 2)
            st
        }
        val watch = linked.session
        assertEquals(phone.user.id, watch.user.id)
        assertEquals("watch", watch.device.kind)
        assertEquals(setOf("phone", "watch"), api.devices(phone.token).map { it.kind }.toSet())
        val wrong = runCatching { api.linkClaim(phone.token, "AAAAAA") }.exceptionOrNull()
        assertTrue("a wrong code is NotFound, got $wrong", wrong is ApiException.NotFound)

        // 3. the phone asks with a photo; the answer streams with thinking and a figure
        val photo = api.uploadAttachment(phone.token, jpeg(), "image/jpeg")
        assertTrue(photo.width in 1..1568 && photo.height in 1..1568)
        val dialogId = UUID.randomUUID().toString()
        val answerId = UUID.randomUUID().toString()
        val events = api.chat(
            phone.token,
            ChatRequest(dialogId, UUID.randomUUID().toString(), "Что на фото?", listOf(photo.id), System.currentTimeMillis(), answerId, "ru-RU"),
        ).toList()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertTrue(text, text.startsWith("Вижу 1 изображ.") && "```figure\n" in text && text.endsWith("Это синус."))
        assertEquals("Думаю. ", events.filterIsInstance<ChatEvent.Thinking>().joinToString("") { it.text })
        val done = events.last() as ChatEvent.Done
        assertEquals(answerId, done.message!!.id)
        assertEquals("done", done.message!!.status)
        assertTrue(done.seq > 0)

        // 4. the watch syncs: the dialog, the question with its photo and the answer with its figure
        val page = api.sync(watch.token, 0)
        val dialog = page.dialogs.single { it.id == dialogId }
        assertEquals("Что на фото?", dialog.title)
        val messages = page.messages.filter { it.dialogId == dialogId }.sortedBy { it.createdAt }
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals(photo.id, messages[0].attachments.single().id)
        assertTrue("```figure" in messages[1].content)
        assertNotNull(api.downloadAttachment(watch.token, photo.id, thumb = true))
        assertEquals(page.seq, api.sync(watch.token, page.seq).seq) // nothing new

        // 5. the watch changes a shared preference; the phone hears about it live and gets it with sync
        val since = api.sync(phone.token, 0).seq
        val changed = async {
            val t0 = System.currentTimeMillis()
            withTimeout(15_000) {
                api.events(phone.token).first {
                    println("live event after ${System.currentTimeMillis() - t0} ms: $it (since $since)")
                    it is LiveEvent.Changed && it.seq > since
                }
            }
        }
        delay(500)
        api.putPrefs(watch.token, Prefs(model = "claude-sonnet-5-5", webSearch = false))
        assertTrue(changed.await() is LiveEvent.Changed)
        val phonePage = api.sync(phone.token, since)
        assertEquals("claude-sonnet-5-5", phonePage.prefs?.model)
        assertEquals(false, phonePage.prefs?.webSearch)

        // 6. the phone unlinks the watch: the watch's token stops working
        api.unlink(phone.token, watch.device.id)
        val gone = runCatching { api.me(watch.token) }.exceptionOrNull()
        assertTrue("an unlinked watch is Unauthorized, got $gone", gone is ApiException.Unauthorized)
        assertEquals(listOf("phone"), api.devices(phone.token).map { it.kind })
    }
}
