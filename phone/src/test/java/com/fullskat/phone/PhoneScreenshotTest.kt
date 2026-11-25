package com.fullskat.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.fullskat.core.db.DialogEntity
import com.fullskat.core.db.MessageEntity
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.net.Device
import com.fullskat.core.net.DeviceKind
import com.fullskat.core.net.JsonModels
import com.fullskat.core.net.MessageStatus
import com.fullskat.core.net.ModelEfforts
import com.fullskat.core.net.ModelInfo
import com.fullskat.core.net.Prefs
import com.fullskat.core.net.ProviderInfo
import com.fullskat.core.net.Roles
import com.fullskat.core.net.Session
import com.fullskat.core.net.User
import com.fullskat.core.net.WebSource
import com.fullskat.core.net.WebSources
import com.fullskat.core.ui.rich.LocalFigureOpener
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.chat.ChatError
import com.fullskat.phone.chat.Draft
import com.fullskat.phone.chat.Turn
import com.fullskat.phone.ui.chat.ChatScreen
import com.fullskat.phone.ui.chat.ChatViewModel
import com.fullskat.phone.ui.chat.ImageViewer
import com.fullskat.phone.ui.dialogs.DialogsScreen
import com.fullskat.phone.ui.dialogs.DialogsViewModel
import com.fullskat.phone.ui.figure.FigureViewer
import com.fullskat.phone.ui.onboarding.OnboardingScreen
import com.fullskat.phone.ui.onboarding.OnboardingViewModel
import com.fullskat.phone.ui.onboarding.SignUpError
import com.fullskat.phone.ui.settings.ConnectionStatus
import com.fullskat.phone.ui.settings.DevicesContent
import com.fullskat.phone.ui.settings.DevicesState
import com.fullskat.phone.ui.settings.InstallStatus
import com.fullskat.phone.ui.settings.InstallWatchContent
import com.fullskat.phone.ui.settings.InstallerActions
import com.fullskat.phone.ui.settings.InstallerState
import com.fullskat.phone.ui.settings.PairStatus
import com.fullskat.phone.watchinstall.AdbProblem
import com.fullskat.phone.watchinstall.AppVersion
import com.fullskat.phone.watchinstall.BundledApk
import com.fullskat.phone.watchinstall.InstallError
import com.fullskat.phone.watchinstall.WatchInfo
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.ui.settings.LinkState
import com.fullskat.phone.ui.settings.LinkWatchContent
import com.fullskat.phone.ui.settings.SettingsScreen
import com.fullskat.phone.ui.settings.SettingsViewModel
import com.fullskat.phone.ui.theme.PhoneTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
 * Renders the real phone screens on the JVM (Robolectric native graphics) at 411 x 891 dp, xxhdpi (a Pixel-sized
 * phone). Skipped unless run with `-Pscreenshots`; PNGs go to phone/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = ScreenshotApp::class)
class PhoneScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun enabled() = assumeTrue(System.getProperty("fullskat.screenshots") != null)

        private const val RICH = """На первом фото — рыжий кот на подоконнике, на втором — чашка капучино с рисунком на пене.

**Площадь круга** радиусом §r = 3§ см:

§§S = \pi r^2 = 9\pi \approx 28{,}27\ \text{см}^2§§

```figure
{"title":"Круг радиусом §r = 3§","equal":true,"x_range":[-4,4],"y_range":[-3.6,3.6],"items":[{"type":"circle","center":[0,0],"r":3,"fill":true,"label":"§S = 9\\pi§"},{"type":"segment","from":[0,0],"to":[3,0],"color":"amber","label":"§r = 3§"},{"type":"point","at":[0,0]}]}
```

Для сравнения:

| величина | формула | значение |
|---|---|---|
| площадь | §\pi r^2§ | 28,27 см² |
| длина окружности | §2\pi r§ | 18,85 см |

```kotlin
val area = PI * r * r // 28.27
```"""

        private const val THINKING = "Два фото и вопрос про круг. Сначала коротко опишу фото, потом формула S = πr² при r = 3: " +
            "S = 9π ≈ 28,27 см². Добавлю рисунок круга и таблицу для сравнения с длиной окружности."

        private const val SINE = """{"title":"§y = \\sin x§ и §y = \\cos x§","x_range":[-6.28319,6.28319],"items":[{"type":"function","expr":"sin(x)","label":"§\\sin x§"},{"type":"function","expr":"cos(x)","label":"§\\cos x§","dash":true}]}"""
    }

    @get:Rule
    val compose = createComposeRule()

    private val app get() = PhoneApp.container(ApplicationProvider.getApplicationContext())
    private val dir = File(System.getProperty("fullskat.screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false // typing dots and spinners animate forever
        compose.setContent {
            PhoneTheme(app.core.attachments) {
                CompositionLocalProvider(LocalFigureOpener provides {}) {
                    Box(Modifier.fillMaxSize().background(Ember.background)) { content() }
                }
            }
        }
        settle()
        capture(name)
    }

    /** Lets Room, DataStore and image loading finish on their threads, then advances the frame clock. */
    private fun settle(ms: Long = 800) {
        repeat(12) {
            compose.waitForIdle()
            Thread.sleep(40)
            compose.mainClock.advanceTimeBy(ms / 12)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage(File(dir, "$name.png").path)

    private fun d(s: String) = s.replace('§', '$')

    // ---------------------------------------------------------------- seeds

    private fun seedSession() = runBlocking {
        val now = System.currentTimeMillis()
        val info = ModelInfo(
            model = "claude-haiku-5-5",
            models = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1", "moonshotai/kimi-k3", "gpt-6.1-terra", "gpt-6-luna"),
            effort = "medium",
            modelEfforts = mapOf(
                "claude-haiku-5-5" to ModelEfforts(listOf("low", "medium", "high"), "medium"),
                "claude-sonnet-5-5" to ModelEfforts(listOf("low", "medium", "high", "xhigh", "max"), "high"),
            ),
            providers = listOf(
                ProviderInfo("anthropic", listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"), 0.62f, "$31.00"),
                ProviderInfo("openrouter", listOf("moonshotai/kimi-k3"), 0.08f, "$0.40"),
                ProviderInfo("openai", listOf("gpt-6.1-terra", "gpt-6-luna"), null, ""),
            ),
        )
        app.core.session.save(
            Session(
                token = "", // a real token needs the Android Keystore, which Robolectric lacks; the screens do not need it
                serverName = "FullSkat",
                user = User("u_1", "Миша", now),
                device = Device("p1", DeviceKind.PHONE, "Pixel 9", now, now, current = true),
                info = info,
            ),
        )
        app.core.session.savePrefs(Prefs(model = "claude-sonnet-5-5", effort = "high", webSearch = true, figures = true))
    }

    private fun seedDialogs() = runBlocking {
        val dao = app.core.db.dao()
        val now = System.currentTimeMillis()
        val items = listOf(
            Triple("Что на фото и площадь круга", d("**Площадь** круга: §S = \\pi r^2 \\approx 28{,}27§ см²"), 20_000L),
            Triple("Какая завтра погода в Москве?", "Завтра облачно, днём около +8, к вечеру небольшой дождь. Зонт пригодится.", 50L * 60_000),
            Triple("График синуса и косинуса", "```figure\n{\"items\":[{\"type\":\"function\",\"expr\":\"sin(x)\"}]}\n```\nСинус и косинус сдвинуты на π/2.", 3L * 3_600_000),
            Triple("Напиши функцию на Kotlin для факториала", "```kotlin\nfun fact(n: Int): Long = if (n < 2) 1 else n * fact(n - 1)\n```\nРекурсивно, с Long.", 26L * 3_600_000),
            Triple("Переведи на английский: где ближайшая аптека", "Where is the nearest pharmacy?", 2L * 86_400_000),
            Triple("Почему небо голубое?", "Из-за рассеяния Рэлея: короткие синие волны рассеиваются в атмосфере сильнее красных.", 3L * 86_400_000),
            Triple("Рецепт блинов без молока", "1. Смешайте 2 яйца, 500 мл воды и щепотку соли.\n2. Добавьте 250 г муки…", 5L * 86_400_000),
            Triple("Столица Австралии", "Канберра.", 9L * 86_400_000),
        )
        items.forEachIndexed { i, (title, answer, ago) ->
            val t = now - ago
            dao.upsertDialog(DialogEntity("l$i", title, t - 5_000, t))
            dao.upsertMessage(MessageEntity("lq$i", "l$i", Roles.USER, title, t - 5_000))
            dao.upsertMessage(MessageEntity("la$i", "l$i", Roles.ASSISTANT, answer, t))
        }
    }

    /** A small JPEG in the attachment cache, as if it had been downloaded before. */
    private fun cacheImage(name: String, w: Int, h: Int, from: Int, to: Int): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        canvas.drawCircle(w * 0.62f, h * 0.42f, minOf(w, h) * 0.22f, p)
        p.color = 0x55000000
        canvas.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
        val dir = File(app.appContext.cacheDir, "attachments").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return file
    }

    private fun seedChat(): String = runBlocking {
        val dao = app.core.db.dao()
        val now = System.currentTimeMillis()
        cacheImage("a_1_thumb.jpg", 320, 240, 0xFFB86B2B.toInt(), 0xFF2B1A10.toInt())
        cacheImage("a_2_thumb.jpg", 240, 320, 0xFF3C2A20.toInt(), 0xFFD9B48A.toInt())
        dao.upsertDialog(DialogEntity("d1", "Что на фото и площадь круга", now - 60_000, now - 30_000))
        dao.upsertMessage(
            MessageEntity(
                id = "m1", dialogId = "d1", role = Roles.USER, createdAt = now - 60_000,
                content = "Что на этих фото? И посчитай площадь круга радиусом 3 см.",
                attachments = JsonModels.attachmentsJson(listOf(AttachmentRef("a_1", width = 1568, height = 1176), AttachmentRef("a_2", width = 1176, height = 1568))),
            ),
        )
        dao.upsertMessage(
            MessageEntity(
                id = "m2", dialogId = "d1", role = Roles.ASSISTANT, createdAt = now - 55_000, model = "claude-sonnet-5-5",
                content = d(RICH),
                thinking = THINKING,
                sources = WebSources.encode(
                    listOf(
                        WebSource("https://ru.wikipedia.org/wiki/Круг", "Круг — Википедия"),
                        WebSource("https://www.mathsisfun.com/geometry/circle-area.html", "Area of a Circle"),
                        WebSource("https://xn--80aswg.xn--p1ai/formuly", "Формулы площади и длины окружности"),
                    ),
                ),
            ),
        )
        "d1"
    }

    private fun seedStates(): String = runBlocking {
        val dao = app.core.db.dao()
        val now = System.currentTimeMillis()
        dao.upsertDialog(DialogEntity("d2", "Курс доллара сегодня", now - 600_000, now))
        dao.upsertMessage(MessageEntity("s1", "d2", Roles.USER, "Какой курс доллара сегодня?", now - 600_000, status = MessageStatus.ERROR, error = "No connection"))
        dao.upsertMessage(MessageEntity("s2", "d2", Roles.USER, "Какой курс доллара сегодня?", now - 300_000))
        dao.upsertMessage(
            MessageEntity("s3", "d2", Roles.ASSISTANT, "По данным ЦБ на сегодня курс составляет", now - 299_000, status = MessageStatus.ERROR, error = "upstream_quota"),
        )
        dao.upsertMessage(MessageEntity("s4", "d2", Roles.USER, "А евро?", now - 2_000))
        dao.upsertMessage(MessageEntity("s5", "d2", Roles.ASSISTANT, "", now - 1_000, status = MessageStatus.STREAMING))
        "d2"
    }

    // ---------------------------------------------------------------- onboarding and the list

    @Test fun onboarding() {
        val vm = OnboardingViewModel(app)
        shot("01_onboarding") { OnboardingScreen(vm) {} }
    }

    @Test fun onboarding_error() {
        val vm = OnboardingViewModel(app)
        vm.onPasscode("4821")
        vm.showError(SignUpError.WrongPasscode)
        shot("02_onboarding_wrong") { OnboardingScreen(vm) {} }
    }

    @Test fun dialogs() {
        seedDialogs()
        val vm = DialogsViewModel(app)
        shot("03_dialogs") { DialogsScreen(vm, {}, {}) }
    }

    @Test fun dialogs_empty() {
        val vm = DialogsViewModel(app)
        shot("04_dialogs_empty") { DialogsScreen(vm, {}, {}) }
    }

    // ---------------------------------------------------------------- chat

    @Test fun chat_rich() {
        seedSession()
        val id = seedChat()
        val vm = ChatViewModel(app, id)
        shot("05_chat_rich_top") { ChatScreen(vm, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        settle()
        capture("05_chat_rich_top")
    }

    @Test fun chat_rich_expanded() {
        seedSession()
        val id = seedChat()
        val vm = ChatViewModel(app, id)
        shot("06_chat_rich_bottom") { ChatScreen(vm, {}, {}) }
        compose.onNodeWithText("Sources (3)").performClick()
        settle()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        settle()
        capture("06_chat_rich_bottom")
    }

    @Test fun chat_thinking_expanded() {
        seedSession()
        val id = seedChat()
        val vm = ChatViewModel(app, id)
        shot("07_chat_thinking") { ChatScreen(vm, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        settle()
        compose.onNodeWithText("Thinking").performClick()
        settle()
        capture("07_chat_thinking")
    }

    @Test fun chat_composer() {
        seedSession()
        val id = seedChat()
        val vm = ChatViewModel(app, id)
        val a = cacheImage("pick_1.jpg", 400, 300, 0xFF6B8E23.toInt(), 0xFF1E3A14.toInt())
        val b = cacheImage("pick_2.jpg", 300, 400, 0xFF2B4C7E.toInt(), 0xFF0E1A2B.toInt())
        vm.addImages(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        vm.setText("А здесь что? Сравни с первыми двумя фото")
        shot("08_chat_composer") { ChatScreen(vm, {}, {}) }
    }

    @Test fun chat_uploading() {
        seedSession()
        val id = seedChat()
        val files = listOf(
            cacheImage("up_1.jpg", 400, 300, 0xFF8E2341.toInt(), 0xFF2B0E14.toInt()),
            cacheImage("up_2.jpg", 400, 300, 0xFF23768E.toInt(), 0xFF0E222B.toInt()),
            cacheImage("up_3.jpg", 400, 300, 0xFF8E7A23.toInt(), 0xFF2B250E.toInt()),
        ).map { Uri.fromFile(it) }
        app.turns.show(id, Turn.Uploading(Draft("Что общего у этих снимков?", files), done = 1))
        val vm = ChatViewModel(app, id)
        shot("09_chat_uploading") { ChatScreen(vm, {}, {}) }
    }

    @Test fun chat_limit() {
        seedSession()
        val id = seedChat()
        app.turns.show(id, Turn.Failed(ChatError.DAILY_LIMIT, retryAfterSec = 3 * 3600))
        val vm = ChatViewModel(app, id)
        shot("10_chat_limit") { ChatScreen(vm, {}, {}) }
    }

    @Test fun chat_states() {
        seedSession()
        val id = seedStates()
        val vm = ChatViewModel(app, id)
        shot("11_chat_states") { ChatScreen(vm, {}, {}) }
    }

    @Test fun chat_empty() {
        seedSession()
        val id = runBlocking { app.core.sync.newDialog() }
        val vm = ChatViewModel(app, id)
        shot("12_chat_empty") { ChatScreen(vm, {}, {}) }
    }

    @Test fun figure_viewer() {
        shot("13_figure_viewer") { FigureViewer(d(SINE)) {} }
        compose.mainClock.advanceTimeBy(5_000) // the drawing animation
        settle()
        capture("13_figure_viewer")
    }

    @Test fun figure_viewer_circle() {
        val json = d(RICH).substringAfter("```figure\n").substringBefore("\n```")
        shot("14_figure_viewer_circle") { FigureViewer(json) {} }
        compose.mainClock.advanceTimeBy(5_000)
        settle()
        capture("14_figure_viewer_circle")
    }

    @Test fun image_viewer() {
        cacheImage("a_1_full.jpg", 1200, 900, 0xFFB86B2B.toInt(), 0xFF2B1A10.toInt())
        shot("15_image_viewer") { ImageViewer(AttachmentRef("a_1", width = 1200, height = 900)) {} }
    }

    // ---------------------------------------------------------------- settings

    @Test fun settings() {
        seedSession()
        val vm = SettingsViewModel(app)
        shot("16_settings") { SettingsScreen(vm, {}, {}, {}, {}) }
    }

    @Test fun model_menu_chat() {
        seedSession()
        val vm = ChatViewModel(app, seedChat())
        shot("17_model_menu_chat") { ChatScreen(vm, {}, {}) }
        compose.onNodeWithText("Sonnet 5.5").performClick() // the model under the title opens the list in place
        settle()
        captureScreen("17_model_menu_chat")
    }

    @Test fun model_menu_settings() {
        seedSession()
        val vm = SettingsViewModel(app)
        shot("18_model_menu_settings") { SettingsScreen(vm, {}, {}, {}, {}) }
        compose.onNodeWithText("Sonnet 5.5").performClick()
        settle()
        captureScreen("18_model_menu_settings")
    }

    @Test fun link_watch() = shot("19_link_watch") { LinkWatchContent("K7Q2", LinkState.Entering, {}, {}, {}, {}) }

    @Test fun link_watch_wrong() = shot("20_link_watch_wrong") { LinkWatchContent("", LinkState.WrongCode, {}, {}, {}, {}) }

    @Test fun link_watch_done() = shot("21_link_watch_done") {
        LinkWatchContent("K7Q2ZP", LinkState.Linked(Device("w1", DeviceKind.WATCH, "Galaxy Watch7")), {}, {}, {}, {})
    }

    @Test fun devices() {
        val now = System.currentTimeMillis()
        val list = listOf(
            Device("p1", DeviceKind.PHONE, "Pixel 9", now - 86_400_000L * 40, now, current = true),
            Device("w1", DeviceKind.WATCH, "Galaxy Watch7", now - 86_400_000L * 30, now - 5 * 60_000),
            Device("t1", DeviceKind.OTHER, "Galaxy Tab S10", now - 86_400_000L * 20, now - 3 * 86_400_000L),
        )
        shot("22_devices") { DevicesContent(DevicesState.Loaded(list), false, {}, {}, {}, {}, {}) }
    }

    // ---------------------------------------------------------------- popups (own windows: the whole screen is captured)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) = captureScreenRoboImage(File(dir, "$name.png").path)

    @Test fun effort_dialog() {
        seedSession()
        val vm = SettingsViewModel(app)
        shot("23_effort_dialog") { SettingsScreen(vm, {}, {}, {}, {}) }
        compose.onNodeWithText("Thinking effort").performClick()
        settle()
        captureScreen("23_effort_dialog")
    }

    @Test fun attach_menu() {
        seedSession()
        val id = seedChat()
        val vm = ChatViewModel(app, id)
        shot("24_attach_menu") { ChatScreen(vm, {}, {}) }
        compose.onNodeWithContentDescription("Attach images").performClick()
        settle()
        captureScreen("24_attach_menu")
    }

    @Test fun dialog_menu() {
        seedDialogs()
        val vm = DialogsViewModel(app)
        shot("25_dialog_menu") { DialogsScreen(vm, {}, {}) }
        compose.onNodeWithText("Какая завтра погода в Москве?").performTouchInput { longClick() }
        settle()
        captureScreen("25_dialog_menu")
    }

    // ---------------------------------------------------------------- Russian

    @Config(qualifiers = "+ru")
    @Test fun onboarding_ru() {
        val vm = OnboardingViewModel(app)
        shot("26_onboarding_ru") { OnboardingScreen(vm) {} }
    }

    @Config(qualifiers = "+ru")
    @Test fun settings_ru() {
        seedSession()
        val vm = SettingsViewModel(app)
        shot("27_settings_ru") { SettingsScreen(vm, {}, {}, {}, {}) }
    }

    @Config(qualifiers = "+ru")
    @Test fun chat_uploading_ru() {
        seedSession()
        val id = seedStates()
        val files = listOf(cacheImage("ru_1.jpg", 400, 300, 0xFF8E2341.toInt(), 0xFF2B0E14.toInt())).map { Uri.fromFile(it) }
        app.turns.show(id, Turn.Uploading(Draft("Что на этом снимке?", files), done = 0))
        val vm = ChatViewModel(app, id)
        shot("28_chat_uploading_ru") { ChatScreen(vm, {}, {}) }
    }

    @Config(qualifiers = "+ru")
    @Test fun link_watch_ru() = shot("29_link_watch_ru") { LinkWatchContent("K7Q2Z", LinkState.Locked(240), {}, {}, {}, {}) }

    // ---------------------------------------------------------------- install the watch app

    private val bundled = BundledApk.Ready(AppVersion("1.0.42", 42), minSdk = 33, file = File("fullskat-watch.apk"), size = 43_400_000)
    private val pairService = WatchService("adb-R5CT1234567-AbCdEf", "192.168.1.23", 37145)
    private val connectService = WatchService("adb-R5CT1234567-AbCdEf", "192.168.1.23", 41235)
    private val watch = WatchInfo("SM-L305F", "samsung", "16", 36, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), AppVersion("1.0.41", 41))
    private val connected = InstallerState(
        bundled = bundled,
        connectAddress = connectService.address,
        pair = PairStatus.Paired,
        connection = ConnectionStatus.Connected(watch),
    )

    private fun installer(name: String, state: InstallerState, scrollTo: String? = null) {
        shot(name) { InstallWatchContent(state, object : InstallerActions {}, {}, {}, {}) }
        if (scrollTo != null) {
            compose.onNodeWithText(scrollTo, substring = true).performScrollTo()
            settle()
            capture(name)
        }
    }

    @Test fun install_instructions() = installer("30_install_instructions", InstallerState(bundled = bundled))

    @Test fun install_pair() = installer(
        "31_install_pair",
        InstallerState(
            bundled = bundled,
            pairServices = listOf(pairService),
            pairHost = pairService.host,
            pairPort = pairService.port.toString(),
            pairCode = "4829",
            notPaired = true,
        ),
    )

    @Test fun install_pair_wrong() = installer(
        "32_install_pair_wrong",
        InstallerState(
            bundled = bundled,
            pairServices = listOf(pairService),
            connectServices = listOf(connectService),
            pairHost = pairService.host,
            pairPort = pairService.port.toString(),
            connectAddress = connectService.address,
            pair = PairStatus.Failed(AdbProblem.WRONG_CODE),
        ),
    )

    @Test fun install_connected() = installer("33_install_connected", connected)

    @Test fun install_progress() = installer("34_install_progress", connected.copy(install = InstallStatus.Uploading(18_200_000, bundled.size)))

    @Test fun install_mismatch() = installer(
        "35_install_mismatch",
        connected.copy(
            install = InstallStatus.Failed(
                InstallError.UPDATE_INCOMPATIBLE,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.fullskat.watch signatures do not match newer version; ignoring!",
            ),
        ),
        scrollTo = "Cancel",
    )

    @Test fun install_done() = installer("36_install_done", connected.copy(install = InstallStatus.Done(AppVersion("1.0.42", 42), relink = true)))

    @Test fun install_not_bundled() = installer(
        "37_install_not_bundled",
        InstallerState(bundled = BundledApk.NotBundled, connectServices = listOf(connectService), connectAddress = connectService.address),
        scrollTo = "only bundled in release builds",
    )

    @Test fun install_permission() = installer("38_install_permission", InstallerState(bundled = bundled, needsPermission = true))

    @Config(qualifiers = "+ru")
    @Test fun install_pair_ru() = installer(
        "39_install_pair_ru",
        InstallerState(bundled = bundled, pairServices = listOf(pairService), pairHost = pairService.host, pairPort = pairService.port.toString(), pairCode = "482913", pair = PairStatus.Working),
    )

    @Config(qualifiers = "+ru")
    @Test fun install_mismatch_ru() = installer(
        "40_install_mismatch_ru",
        connected.copy(install = InstallStatus.Failed(InstallError.UPDATE_INCOMPATIBLE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")),
        scrollTo = "Отмена",
    )
}
