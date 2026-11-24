package com.fullskat.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.fullskat.watch.data.db.DialogEntity
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.richtext.figure.Figure
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.rich.FigureBlock
import com.fullskat.watch.ui.rich.FigureScreen
import com.fullskat.watch.ui.theme.FullSkatTheme
import com.fullskat.watch.ui.theme.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
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
 * Figures the model draws, on the 216 dp round display: inside real conversations (fig_chat_*), frames of the
 * drawing animation (fig_anim_<name>_<ms>), and the full-screen viewer (fig_full_*).
 * Skipped unless run with `-Pscreenshots`; PNGs go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w216dp-h216dp-round-xhdpi")
class FigureScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun enabled() = assumeTrue(System.getProperty("fullskat.screenshots") != null)

        const val SINE = """{"title":"${'$'}y = \\sin x${'$'} и ${'$'}y = \\cos x${'$'}","x_range":[-6.28319,6.28319],"items":[{"type":"function","expr":"sin(x)","label":"${'$'}\\sin x${'$'}"},{"type":"function","expr":"cos(x)","label":"${'$'}\\cos x${'$'}","dash":true}]}"""
        const val PARABOLA = """{"x_range":[-1,5],"items":[{"type":"function","expr":"x^2 - 4x + 3","label":"${'$'}f(x)${'$'}","area":[1,3]},{"type":"point","at":[2,-1],"label":"вершина"},{"type":"point","at":[1,0],"label":"1"},{"type":"point","at":[3,0],"label":"3"}]}"""
        const val TAN = """{"title":"tg x","x_range":[-4.71239,4.71239],"y_range":[-4,4],"items":[{"type":"function","expr":"tan(x)"},{"type":"vline","value":-1.5708,"dash":true,"color":"gray"},{"type":"vline","value":1.5708,"dash":true,"color":"gray"}]}"""
        const val BARS = """{"title":"Население, млн","items":[{"type":"bars","labels":["Москва","СПб","Новосиб.","Екб"],"values":[13.1,5.6,1.6,1.5]}]}"""
        const val GROUPED = """{"title":"Продажи по кварталам","y_label":"тыс.","items":[{"type":"bars","labels":["Q1","Q2","Q3","Q4"],"values":[12,15,9,18],"label":"2025"},{"type":"bars","values":[14,17,13,21],"label":"2026"}]}"""
        const val PIE = """{"title":"Состав воздуха","items":[{"type":"pie","labels":["Азот","Кислород","Аргон и др."],"values":[78,21,1]}]}"""
        const val TRIANGLE = """{"title":"Египетский треугольник","axes":false,"items":[{"type":"polygon","points":[[0,0],[4,0],[4,3]],"fill":true},{"type":"angle","at":[4,0],"from":[0,0],"to":[4,3]},{"type":"angle","at":[0,0],"from":[4,0],"to":[4,3],"label":"${'$'}\\alpha${'$'}"},{"type":"segment","from":[0,0],"to":[4,0],"label":"4"},{"type":"segment","from":[4,0],"to":[4,3],"label":"3"},{"type":"segment","from":[0,0],"to":[4,3],"label":"5","color":"amber"}]}"""
        const val CIRCLE = """{"axes":true,"equal":true,"x_range":[-1.6,1.6],"y_range":[-1.3,1.3],"items":[{"type":"circle","center":[0,0],"r":1},{"type":"segment","from":[0,0],"to":[0.7071,0.7071],"color":"amber"},{"type":"segment","from":[0.7071,0],"to":[0.7071,0.7071],"dash":true,"color":"blue","label":"${'$'}\\sin\\varphi${'$'}"},{"type":"segment","from":[0,0],"to":[0.7071,0],"color":"magenta","label":"${'$'}\\cos\\varphi${'$'}"},{"type":"arc","center":[0,0],"r":0.25,"start":0,"end":45,"label":"${'$'}\\varphi${'$'}"},{"type":"point","at":[0.7071,0.7071]}]}"""
        const val FLOW = """{"title":"Цикл while","axes":false,"items":[{"type":"rect","from":[0,4],"to":[4,5],"label":"начало"},{"type":"arrow","from":[2,4],"to":[2,3.2]},{"type":"rect","from":[0,2.2],"to":[4,3.2],"label":"i < n ?","color":"amber"},{"type":"arrow","from":[2,2.2],"to":[2,1.4],"label":"да"},{"type":"rect","from":[0,0.4],"to":[4,1.4],"label":"i = i + 1"},{"type":"path","d":"M 4 0.9 C 5.2 0.9 5.2 2.7 4.15 2.7"},{"type":"arrow","from":[4.4,2.7],"to":[4.05,2.7]}]}"""
        const val ROSE = """{"title":"Роза ${'$'}r = \\sin 4\\theta${'$'}","equal":true,"x_range":[-1.2,1.2],"y_range":[-1.2,1.2],"items":[{"type":"polar","expr":"sin(4t)","label":"роза"},{"type":"parametric","x":"0.5cos(t)","y":"0.5sin(t)","t":[0,6.28319],"color":"blue","dash":true,"label":"${'$'}r=0.5${'$'}"}]}"""
        const val TEMPS = """{"title":"Температура за неделю","y_label":"°C","items":[{"type":"line","points":[[1,-3],[2,-1],[3,2],[4,4],[5,1],[6,-2],[7,-5]],"label":"день"},{"type":"line","points":[[1,-9],[2,-7],[3,-4],[4,-1],[5,-5],[6,-8],[7,-11]],"label":"ночь"}],"x_label":"день"}"""
        const val VECTORS = """{"title":"Сумма векторов","equal":true,"x_range":[-0.5,4.5],"y_range":[-0.5,3.5],"items":[{"type":"arrow","from":[0,0],"to":[3,1],"color":"blue","label":"${'$'}\\vec a${'$'}"},{"type":"arrow","from":[3,1],"to":[4,3],"color":"magenta","label":"${'$'}\\vec b${'$'}"},{"type":"arrow","from":[0,0],"to":[4,3],"color":"amber","label":"${'$'}\\vec a + \\vec b${'$'}"}]}"""
    }

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(System.getProperty("fullskat.screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun fence(json: String) = "```figure\n$json\n```"

    private fun chat(name: String, question: String, before: String, json: String, after: String) {
        val container = FullSkatApp.container(ApplicationProvider.getApplicationContext())
        val id = "fig-$name"
        runBlocking {
            val dao = container.db.dialogDao()
            val t0 = System.currentTimeMillis() - 600_000
            dao.insertDialog(DialogEntity(id, question.take(60), t0, t0))
            dao.insertMessage(MessageEntity(dialogId = id, role = Role.USER, content = question, createdAt = t0))
            val answer = listOf(before, fence(json), after).filter { it.isNotBlank() }.joinToString("\n\n")
            dao.insertMessage(MessageEntity(dialogId = id, role = Role.ASSISTANT, content = answer, createdAt = t0 + 1000))
        }
        compose.setContent { FullSkatTheme { AppScaffold { ChatScreen(AppViewModel(container), id, autoListen = false) } } }
        compose.waitForIdle()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(question.take(10), substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (_: Throwable) {
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "fig_chat_${name}_1.png").path)
        // scroll so the figure is in the middle of the round screen
        compose.onRoot().performTouchInput { swipeUp(startY = 300f, endY = 160f, durationMillis = 1500) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "fig_chat_${name}_2.png").path)
        repeat(4) { compose.onRoot().performTouchInput { swipeUp(startY = 330f, endY = 90f, durationMillis = 1500) }; compose.waitForIdle() }
        compose.onRoot().captureRoboImage(File(dir, "fig_chat_${name}_3.png").path)
    }

    /** The bubble-width figure at several moments of its drawing animation, centred on the round screen. */
    private fun frames(name: String, json: String, times: List<Long>) {
        val figure = Figure.parse(json)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FullSkatTheme {
                AppScaffold {
                    Box(Modifier.fillMaxSize().background(Tokens.background), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .background(Tokens.assistantBubble, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                        ) { FigureBlock(figure, json, animate = true) }
                    }
                }
            }
        }
        var now = 0L
        for (t in times) {
            compose.mainClock.advanceTimeBy(t - now)
            now = t
            compose.onRoot().captureRoboImage(File(dir, "fig_anim_${name}_${"%04d".format(t)}.png").path)
        }
    }

    private fun full(name: String, json: String, at: Long = 6000) {
        compose.mainClock.autoAdvance = false
        compose.setContent { FullSkatTheme { AppScaffold { FigureScreen(json) } } }
        compose.mainClock.advanceTimeBy(at)
        compose.onRoot().captureRoboImage(File(dir, "fig_full_$name.png").path)
    }

    @Test fun chatSine() = chat("a_sine", "Нарисуй синус и косинус", "", SINE, "Косинус — это синус, сдвинутый на ${'$'}\\pi/2${'$'}.")
    @Test fun chatParabola() = chat("b_parabola", "Покажи параболу икс квадрат минус четыре икс плюс три", "Корни 1 и 3, вершина в (2; −1):", PARABOLA, "Закрашена площадь под осью между корнями.")
    @Test fun chatBars() = chat("c_bars", "Сравни население крупнейших городов России", "", BARS, "Москва больше трёх следующих вместе.")
    @Test fun chatTriangle() = chat("d_triangle", "Нарисуй египетский треугольник", "", TRIANGLE, "${'$'}3^2 + 4^2 = 5^2${'$'}.")
    @Test fun chatPie() = chat("e_pie", "Из чего состоит воздух", "", PIE, "")
    @Test fun chatFlow() = chat("f_flow", "Покажи схему цикла while", "", FLOW, "")
    @Test fun chatCircle() = chat("g_circle", "Объясни синус и косинус на единичной окружности", "", CIRCLE, "Для угла ${'$'}\\varphi${'$'} точка на окружности — ${'$'}(\\cos\\varphi, \\sin\\varphi)${'$'}.")
    @Test fun chatTemps() = chat("h_temps", "Какая погода будет на неделе", "", TEMPS, "Теплее всего в четверг.")

    @Test fun animSine() = frames("sine", SINE, listOf(150, 500, 900, 1400, 1900, 5000))
    @Test fun animTriangle() = frames("triangle", TRIANGLE, listOf(300, 800, 1300, 1800, 2400, 5000))
    @Test fun animBars() = frames("grouped", GROUPED, listOf(200, 500, 800, 1100, 5000))
    @Test fun animPie() = frames("pie", PIE, listOf(250, 600, 900, 5000))

    @Test fun fullSine() = full("sine", SINE)
    @Test fun fullTan() = full("tan", TAN)
    @Test fun fullRose() = full("rose", ROSE)
    @Test fun fullTriangle() = full("triangle", TRIANGLE)
    @Test fun fullBars() = full("bars", BARS)
    @Test fun fullPie() = full("pie", PIE)
    @Test fun fullVectors() = full("vectors", VECTORS)
    @Test fun fullParabolaMidDrawing() = full("parabola_mid", PARABOLA, at = 1100)
}
