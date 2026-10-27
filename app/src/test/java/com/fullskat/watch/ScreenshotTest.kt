package com.fullskat.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.fullskat.watch.data.db.DialogEntity
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.domain.ErrorKind
import com.fullskat.watch.domain.TurnState
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.PairUiState
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.dialogs.DialogsScreen
import com.fullskat.watch.ui.pair.PairScreen
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
 * Renders the real screens on the JVM (Robolectric native graphics) as a 240 dp round display
 * (Galaxy Watch7 44 mm class). Skipped unless run with `-Pscreenshots`; PNGs go to app/build/screenshots.
 * The bezel crop is applied afterwards by a script; the PNGs here are the full square window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w240dp-h240dp-round-xhdpi")
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
        val dao = container.db.dialogDao()
        val now = System.currentTimeMillis()
        dao.insertDialog(DialogEntity("d1", "Какая завтра погода в Москве?", now - 120_000, now - 120_000))
        dao.insertDialog(DialogEntity("d2", "Сколько будет 15 процентов от 2400", now - 3_600_000, now - 3_600_000))
        dao.insertDialog(DialogEntity("d3", "Напомни, как варить гречку", now - 86_400_000, now - 86_400_000))
        dao.insertMessage(MessageEntity(dialogId = "d1", role = Role.USER, content = "Какая завтра погода в Москве?", createdAt = now - 120_000))
        dao.insertMessage(
            MessageEntity(
                dialogId = "d1", role = Role.ASSISTANT, createdAt = now - 119_000,
                content = "Завтра в Москве облачно, днём около 8 градусов, к вечеру возможен небольшой дождь.",
            ),
        )
        "d1"
    }

    @Test fun pair_empty() = shot("01_pair_empty") { PairScreen(AppViewModel(container)) {} }

    @Test fun pair_typing() {
        val vm = AppViewModel(container)
        shot("02_pair_typing") { PairScreen(vm) {} }
        listOf("4", "8", "2").forEach { compose.onNodeWithText(it).performClick() }
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage(File(dir, "02_pair_typing.png").path)
    }

    @Test fun pair_wrong() {
        val vm = AppViewModel(container)
        flowOf<PairUiState>(vm, "_pairState").value = PairUiState.Failed(PairUiState.Failed.Reason.WRONG)
        shot("03_pair_wrong") { PairScreen(vm) {} }
    }

    @Test fun pair_first_setup() {
        val vm = AppViewModel(container)
        flowOf<PairUiState>(vm, "_pairState").value = PairUiState.Success(firstSetup = true)
        shot("04_pair_first_setup") { PairScreen(vm) {} }
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
}
