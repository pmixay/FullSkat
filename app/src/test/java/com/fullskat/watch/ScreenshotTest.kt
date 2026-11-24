package com.fullskat.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.fullskat.core.db.DialogEntity
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.Roles
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.ModelEfforts
import com.fullskat.core.net.ProviderInfo
import com.fullskat.core.net.WebSource
import com.fullskat.core.net.WebSources
import com.fullskat.watch.domain.ErrorKind
import com.fullskat.watch.domain.TurnState
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.dialogs.DialogsScreen
import com.fullskat.watch.ui.link.LinkContent
import com.fullskat.core.account.LinkState
import com.fullskat.core.net.Device
import com.fullskat.core.net.ModelInfo
import com.fullskat.core.net.Prefs
import com.fullskat.core.net.Session
import com.fullskat.core.net.User
import com.fullskat.watch.ui.settings.EffortScreen
import com.fullskat.watch.ui.settings.ModelScreen
import com.fullskat.watch.ui.settings.SettingsScreen
import com.fullskat.watch.ui.theme.FullSkatTheme
import com.fullskat.watch.ui.theme.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens on the JVM (Robolectric native graphics) as a 216 dp round display:
 * the Galaxy Watch7 40 mm (SM-L305F), 432 px at 2x. Skipped unless run with `-Pscreenshots`; PNGs go to app/build/screenshots.
 * The bezel crop is applied afterwards (tools/render/circle.py); the PNGs here are the full square window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w216dp-h216dp-round-xhdpi")
class ScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun enabled() = assumeTrue(System.getProperty("fullskat.screenshots") != null)
    }

    @get:Rule
    val compose = createComposeRule()

    private val container get() = FullSkatApp.container(ApplicationProvider.getApplicationContext())
    private val dir = File(System.getProperty("fullskat.screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false // spinners and the level ring animate forever
        compose.setContent {
            FullSkatTheme {
                AppScaffold { Box(Modifier.fillMaxSize().background(Tokens.background)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flowOf(target: Any, field: String): MutableStateFlow<T> =
        target.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(target) as MutableStateFlow<T>

    private fun turnOf(vm: AppViewModel): MutableStateFlow<TurnState> {
        val turn = vm.javaClass.getDeclaredField("turn").apply { isAccessible = true }.get(vm)!!
        return flowOf(turn, "_state")
    }

    private fun seed(): String = runBlocking {
        val dao = container.core.db.dao()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity("d1", "Какая завтра погода в Москве?", now - 120_000, now - 120_000))
        dao.upsertDialog(DialogEntity("d2", "Сколько будет 15 процентов от 2400", now - 3_600_000, now - 3_600_000))
        dao.upsertDialog(DialogEntity("d3", "Напомни, как варить гречку", now - 86_400_000, now - 86_400_000))
        dao.upsertMessage(MessageEntity(id = java.util.UUID.randomUUID().toString(), dialogId = "d1", role = Roles.USER, content = "Какая завтра погода в Москве?", createdAt = now - 120_000))
        dao.upsertMessage(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(), dialogId = "d1", role = Roles.ASSISTANT, createdAt = now - 119_000,
                content = "Завтра в Москве облачно, днём около 8 градусов, к вечеру возможен небольшой дождь.",
            ),
        )
        "d1"
    }

    private fun seedMany() = runBlocking {
        val dao = container.core.db.dao()
        val now = System.currentTimeMillis()
        val titles = listOf(
            "Какая завтра погода в Москве?" to 2L * 60_000,
            "Сколько будет 15 процентов от 2400" to 50L * 60_000,
            "Напомни, как варить гречку" to 3L * 3_600_000,
            "Переведи на английский: где ближайшая аптека" to 5L * 3_600_000,
            "Курс доллара сегодня" to 26L * 3_600_000,
            "Почему небо голубое?" to 2L * 86_400_000,
            "Рецепт блинов без молока" to 3L * 86_400_000,
            "Столица Австралии" to 4L * 86_400_000,
            "Сколько калорий в банане" to 6L * 86_400_000,
            "Как выключить будильник на часах" to 9L * 86_400_000,
        )
        titles.forEachIndexed { i, (title, ago) ->
            dao.upsertDialog(DialogEntity("m$i", title, now - ago, now - ago))
            dao.upsertMessage(MessageEntity(id = java.util.UUID.randomUUID().toString(), dialogId = "m$i", role = Roles.USER, content = title, createdAt = now - ago))
        }
    }

    private fun swipeUpTwice() {
        repeat(2) { compose.onRoot().performTouchInput { swipeUp() }; compose.mainClock.advanceTimeBy(600) }
    }

    @Test fun dialogs_list_top() {
        seedMany()
        shot("17_dialogs_list_top") { DialogsScreen(AppViewModel(container), {}, {}, {}) }
    }

    @Test fun dialogs_list_scrolled() {
        seedMany()
        shot("18_dialogs_list_scrolled") { DialogsScreen(AppViewModel(container), {}, {}, {}) }
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 400) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage(File(dir, "18_dialogs_list_scrolled.png").path)
    }

    @Test fun dialogs_list_end() {
        seedMany()
        shot("19_dialogs_list_end") { DialogsScreen(AppViewModel(container), {}, {}, {}) }
        repeat(6) { swipeUpTwice() }
        compose.onRoot().captureRoboImage(File(dir, "19_dialogs_list_end.png").path)
    }

    private fun seedThinking(): String = runBlocking {
        val dao = container.core.db.dao()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity("d4", "Почему небо голубое?", now, now))
        dao.upsertMessage(MessageEntity(id = java.util.UUID.randomUUID().toString(), dialogId = "d4", role = Roles.USER, content = "Почему небо голубое?", createdAt = now - 2_000))
        dao.upsertMessage(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(), dialogId = "d4", role = Roles.ASSISTANT, createdAt = now - 1_000,
                content = "Из-за рассеяния света: короткие синие волны рассеиваются в атмосфере сильнее красных.",
                thinking = "Вопрос про рассеяние Рэлея. Нужно ответить коротко, без формул и разметки.",
            ),
        )
        "d4"
    }

    @Test fun link_code() = shot("01_link_code") { LinkContent(LinkState.Code("K7Q2ZP", 23, 30), left = 23f / 30f, secondsLeft = 23) }

    @Test fun link_code_ending() = shot("02_link_code_ending") { LinkContent(LinkState.Code("XA3M9Q", 4, 30), left = 4f / 30f, secondsLeft = 4) }

    @Test fun link_connecting() = shot("03_link_connecting") { LinkContent(null, left = 0f, secondsLeft = 0) }

    @Test fun link_offline() = shot("03b_link_offline") { LinkContent(LinkState.Offline, left = 0f, secondsLeft = 0) }

    @Test fun link_done() = shot("04_link_done") {
        LinkContent(LinkState.Linked(Session("t", "FullSkat", User("u1", "Миша"), Device("w1", "watch", "Galaxy Watch7"), ModelInfo())), left = 1f, secondsLeft = 0)
    }

    @Test fun chat_with_photo() {
        val id = runBlocking {
            val dao = container.core.db.dao()
            val now = System.currentTimeMillis()
            dao.upsertDialog(DialogEntity("p1", "Что на фото?", now, now))
            dao.upsertMessage(MessageEntity(id = "pm1", dialogId = "p1", role = Roles.USER, content = "Что на фото?", createdAt = now - 2_000,
                attachments = """[{"id":"a_1","mime":"image/jpeg","width":1568,"height":1176}]"""))
            dao.upsertMessage(MessageEntity(id = "pm2", dialogId = "p1", role = Roles.ASSISTANT, content = "Кот породы **мейн-кун** на подоконнике.", createdAt = now - 1_000))
            "p1"
        }
        shot("21_chat_photo") { ChatScreen(AppViewModel(container), id, autoListen = false) }
    }

    @Test fun dialogs() {
        seed()
        shot("05_dialogs") { DialogsScreen(AppViewModel(container), {}, {}, {}) }
    }

    @Test fun dialogs_empty() = shot("06_dialogs_empty") { DialogsScreen(AppViewModel(container), {}, {}, {}) }

    @Test fun chat_answer() {
        val id = seed()
        shot("07_chat_answer") { ChatScreen(AppViewModel(container), id, autoListen = false) }
    }

    @Test fun chat_empty() = shot("08_chat_empty") { ChatScreen(AppViewModel(container), "new", autoListen = false) }

    @Test fun chat_listening() {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Listening("Какая завтра погода в Москве и нужен ли зонт", 0.35f, true)
        shot("09_chat_listening") { ChatScreen(vm, id, autoListen = false) }
    }

    @Test fun chat_listening_long() {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Listening(
            "Расскажи подробно, как работает рассеяние света в атмосфере, почему небо голубое днём, " +
                "а на закате становится красным, и чем это отличается от рассеяния на облаках и тумане",
            0.35f, true,
        )
        shot("22_chat_listening_long") { ChatScreen(vm, id, autoListen = false) }
    }

    @Test fun chat_listening_silent() {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Listening("", 0f, false)
        shot("10_chat_listening_silent") { ChatScreen(vm, id, autoListen = false) }
    }

    @Test fun chat_thinking() {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Thinking
        shot("11_chat_thinking") { ChatScreen(vm, id, autoListen = false) }
    }

    @Test fun chat_error() {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Failed(ErrorKind.NO_CONNECTION, null)
        shot("12_chat_error") { ChatScreen(vm, id, autoListen = false) }
    }

    @Test fun settings() = shot("13_settings") { SettingsScreen(AppViewModel(container), {}, {}, {}) }

    @Test fun models() = shot("14_models") { ModelScreen(AppViewModel(container)) {} }

    @Test fun chat_thinking_collapsed() {
        val id = seedThinking()
        shot("15_chat_thinking_collapsed") { ChatScreen(AppViewModel(container), id, autoListen = false) }
    }

    @Test fun chat_thinking_expanded() {
        val id = seedThinking()
        shot("16_chat_thinking_expanded") { ChatScreen(AppViewModel(container), id, autoListen = false) }
        compose.mainClock.autoAdvance = true // no endless animation on this screen
        compose.onNodeWithText("Thinking").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "16_chat_thinking_expanded.png").path)
    }

    @Test fun effort() = shot("20_effort") { EffortScreen(AppViewModel(container)) {} }

    @Test fun chat_scrolled_up() {
        seedLong()
        shot("21_chat_scrolled_up") { ChatScreen(AppViewModel(container), "long", autoListen = false) }
        compose.mainClock.autoAdvance = true
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "21_chat_scrolled_up.png").path)
    }

    private fun seedLong() = runBlocking {
        val dao = container.core.db.dao()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity("long", "Длинный диалог", now, now))
        (0 until 5).forEach { i ->
            dao.upsertMessage(MessageEntity(id = java.util.UUID.randomUUID().toString(), dialogId = "long", role = Roles.USER, content = "Вопрос номер ${i + 1}", createdAt = now - 20_000 + i * 2_000L))
            dao.upsertMessage(
                MessageEntity(
                    id = java.util.UUID.randomUUID().toString(), dialogId = "long", role = Roles.ASSISTANT, createdAt = now - 19_000 + i * 2_000L,
                    content = "Ответ номер ${i + 1}: короткий текст, чтобы список можно было прокручивать вверх и вниз.",
                ),
            )
        }
    }

    // ---- multi-provider model picker, per-model efforts, usage-limit errors ----

    private val claudeModels = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1")
    private val allLevels = listOf("low", "medium", "high", "xhigh", "max")

    private fun seedProviders() = runBlocking {
        val providers = listOf(
            ProviderInfo("anthropic", claudeModels, 0.72f, ""),
            ProviderInfo("openrouter", listOf("openai/gpt-6.1-terra", "openai/gpt-6-luna"), 0.24f, "$4.20"),
            ProviderInfo("kimi", listOf("kimi-k2.8-preview", "kimi-k3.1-pro"), 0.08f, "$1.60"),
        )
        val efforts = (claudeModels + providers[1].models).associateWith { ModelEfforts(allLevels, "medium") } +
            mapOf("kimi-k3.1-pro" to ModelEfforts(listOf("low", "high", "max"), "high"), "kimi-k2.8-preview" to ModelEfforts(emptyList(), null))
        container.core.session.saveInfo(ModelInfo("claude-haiku-5-5", providers.flatMap { it.models }, "medium", efforts, providers))
    }

    @Test fun models_grouped_collapsed() {
        seedProviders()
        shot("23_models_grouped") { ModelScreen(AppViewModel(container)) {} }
    }

    @Test fun models_grouped_expanded() {
        seedProviders()
        shot("24_models_grouped_open") { ModelScreen(AppViewModel(container)) {} }
        compose.onNodeWithText("OpenRouter").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage(File(dir, "24_models_grouped_open.png").path)
    }

    @Test fun effort_limited_model() {
        seedProviders()
        runBlocking { container.core.session.savePrefs(Prefs(model = "kimi-k3.1-pro")) }
        shot("25_effort_limited") { EffortScreen(AppViewModel(container)) {} }
    }

    private fun limit(name: String, kind: ErrorKind, retryAfterSec: Int) {
        val id = seed()
        val vm = AppViewModel(container)
        turnOf(vm).value = TurnState.Failed(kind, null, retryAfterSec)
        shot(name) { ChatScreen(vm, id, autoListen = false) }
    }

    // ---- web sources under an answer ----

    private fun seedSources(): String = runBlocking {
        val dao = container.core.db.dao()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity("d5", "Какая завтра погода в Москве?", now, now))
        dao.upsertMessage(MessageEntity(id = java.util.UUID.randomUUID().toString(), dialogId = "d5", role = Roles.USER, content = "Какая завтра погода в Москве?", createdAt = now - 2_000))
        val sources = listOf(
            WebSource("https://www.gismeteo.ru/weather-moscow-4368/tomorrow/", "Погода в Москве на завтра — прогноз погоды"),
            WebSource("https://meteoinfo.ru/forecasts/russia/moscow-area/moscow", "Гидрометцентр России: прогноз по Москве"),
            WebSource("https://xn--80aswg.xn--p1ai/pogoda", ""),
        )
        dao.upsertMessage(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(), dialogId = "d5", role = Roles.ASSISTANT, createdAt = now - 1_000,
                content = "Завтра в Москве облачно, днём около +8 °C, вечером небольшой дождь.",
                sources = WebSources.encode(sources),
            ),
        )
        "d5"
    }

    @Test fun chat_sources_collapsed() {
        val id = seedSources()
        shot("31_chat_sources_collapsed") { ChatScreen(AppViewModel(container), id, autoListen = false) }
    }

    @Test fun chat_sources_expanded() {
        val id = seedSources()
        shot("32_chat_sources_expanded") { ChatScreen(AppViewModel(container), id, autoListen = false) }
        compose.mainClock.autoAdvance = true // no endless animation on this screen
        compose.onNodeWithText("Sources (3)").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "32_chat_sources_expanded.png").path)
        repeat(2) { compose.onRoot().performTouchInput { swipeUp(startY = 330f, endY = 90f, durationMillis = 1500) }; compose.waitForIdle() }
        compose.onRoot().captureRoboImage(File(dir, "33_chat_sources_expanded_scrolled.png").path)
    }

    @Test fun limit_daily() = limit("26_limit_daily", ErrorKind.DAILY_LIMIT, 7 * 3600)
    @Test fun limit_quota() = limit("27_limit_quota", ErrorKind.QUOTA, 0)
    @Test fun limit_rate() = limit("28_limit_rate", ErrorKind.RATE_LIMITED, 40)
    @Test fun limit_busy() = limit("29_limit_busy", ErrorKind.PROVIDER_BUSY, 90)
    @Test fun error_no_provider() = limit("30_error_no_provider", ErrorKind.NO_PROVIDER, 0)
}
