package com.fullskat.watch

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.fullskat.watch.data.db.DialogEntity
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.theme.FullSkatTheme
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
 * Real-looking conversations (voice question, rich answer) on the round 240 dp chat screen, captured at the
 * end of the chat and after scrolling up. The text uses § for the dollar sign so the LaTeX stays readable.
 * Skipped unless run with `-Pscreenshots`; PNGs go next to the other screenshots as conv_<name>_<n>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w240dp-h240dp-round-xhdpi")
class RichChatScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun enabled() = assumeTrue(System.getProperty("fullskat.screenshots") != null)
    }

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(System.getProperty("fullskat.screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun conversation(name: String, vararg turns: Pair<Role, String>, states: Int = 9) {
        val container = FullSkatApp.container(ApplicationProvider.getApplicationContext())
        val id = "conv-$name"
        runBlocking {
            val dao = container.db.dialogDao()
            val t0 = System.currentTimeMillis() - 600_000
            val first = turns.first { it.first == Role.USER }.second
            dao.insertDialog(DialogEntity(id, first.take(60), t0, t0))
            turns.forEachIndexed { i, (role, text) ->
                dao.insertMessage(MessageEntity(dialogId = id, role = role, content = text.replace('§', '$').trim(), createdAt = t0 + i * 1000L))
            }
        }
        compose.setContent { FullSkatTheme { AppScaffold { ChatScreen(AppViewModel(container), id, autoListen = false) } } }
        compose.waitForIdle()
        // The messages arrive from Room asynchronously: wait until the first question is on screen.
        val probe = turns.first { it.first == Role.USER }.second.take(10)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(probe, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (_: Throwable) {
        }
        compose.waitForIdle()
        // Go to the end of the chat in half-screen steps, then photograph upwards until the top: files are numbered
        // end-first, the post-processing puts them in reading order.
        repeat(12) { compose.onRoot().performTouchInput { swipeUp(startY = 330f, endY = 90f, durationMillis = 1500) }; compose.waitForIdle() }
        compose.onRoot().captureRoboImage(File(dir, "conv_${name}_1.png").path)
        for (n in 2..states) {
            compose.onRoot().performTouchInput { swipeDown(startY = 90f, endY = 330f, durationMillis = 1500) } // about half a screen, slow enough not to fling
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "conv_${name}_$n.png").path)
        }
    }

    private fun user(s: String) = Role.USER to s
    private fun bot(s: String) = Role.ASSISTANT to s

    @Test fun quadratic() = conversation(
        "a01_quadratic",
        user("Реши уравнение икс в квадрате минус пять икс плюс шесть равно нулю"),
        bot(
            """
Дискриминант: §D = b^2 - 4ac = 25 - 24 = 1§.

§§x_{1,2} = \frac{5 \pm \sqrt{1}}{2}§§

Ответ: §x_1 = 3§ и §x_2 = 2§.
            """,
        ),
    )

    @Test fun derivative() = conversation(
        "a02_derivative",
        user("Найди производную икс квадрат на синус икс"),
        bot(
            """
Правило произведения:

§§\frac{d}{dx}\left(x^2 \sin x\right) = 2x \sin x + x^2 \cos x§§

Итого: §f'(x) = x\,(2\sin x + x\cos x)§.
            """,
        ),
    )

    @Test fun integral_thread() = conversation(
        "a03_integral",
        user("Посчитай интеграл икс в квадрате от нуля до единицы"),
        bot("""§§\int_0^1 x^2\,dx = \left.\frac{x^3}{3}\right|_0^1 = \frac{1}{3}§§"""),
        user("А от нуля до а?"),
        bot(
            """
§§\int_0^a x^2\,dx = \frac{a^3}{3}§§

При §a = 1§ получаем прежний ответ, §\tfrac{1}{3}§.
            """,
        ),
    )

    @Test fun kinetic_energy() = conversation(
        "a04_energy",
        user("Что такое кинетическая энергия? Формулу и пример"),
        bot(
            """
Энергия движения тела:

§§E_k = \frac{m v^2}{2}§§

Пример: §m = 2\,\text{кг}§, §v = 3\,\text{м/с}§:

§§E_k = \frac{2 \cdot 3^2}{2} = 9\,\text{Дж}§§

Если скорость растёт вдвое, энергия растёт в **четыре** раза.
            """,
        ),
    )

    @Test fun python_code() = conversation(
        "a05_code",
        user("Напиши функцию факториала на питоне"),
        bot(
            """
Вот рекурсивная версия:

```python
def factorial(n: int) -> int:
    if n <= 1:
        return 1
    return n * factorial(n - 1)
```

Для `n = 5` получится §5! = 120§. Для больших `n` лучше цикл или `math.factorial`.
            """,
        ),
    )

    @Test fun matrices() = conversation(
        "a06_matrices",
        user("Умножь матрицы один два три четыре на ноль один один ноль"),
        bot(
            """
§§\begin{pmatrix}1 & 2 \\ 3 & 4\end{pmatrix}\begin{pmatrix}0 & 1 \\ 1 & 0\end{pmatrix} = \begin{pmatrix}2 & 1 \\ 4 & 3\end{pmatrix}§§

Правый множитель переставляет столбцы.
            """,
        ),
    )

    @Test fun statistics_english() = conversation(
        "a07_statistics",
        user("How is standard deviation computed?"),
        bot(
            """
For a sample of §n§ values with mean §\bar{x}§:

§§s = \sqrt{\frac{1}{n-1}\sum_{i=1}^{n}(x_i - \bar{x})^2}§§

For a whole population divide by §n§ and write it as §\sigma§.
            """,
        ),
    )

    @Test fun piecewise() = conversation(
        "a08_piecewise",
        user("Задай модуль икс по частям"),
        bot(
            """
§§|x| = \begin{cases} x, & x \ge 0 \\ -x, & x < 0 \end{cases}§§

График — «галочка» с вершиной в нуле.
            """,
        ),
    )

    @Test fun chemistry() = conversation(
        "a09_chemistry",
        user("Сколько молей в тридцати шести граммах воды?"),
        bot(
            """
Молярная масса §M(\mathrm{H_2O}) = 18\ \text{г/моль}§:

§§n = \frac{m}{M} = \frac{36}{18} = 2\ \text{моль}§§

Реакция образования: §2\mathrm{H}_2 + \mathrm{O}_2 \to 2\mathrm{H}_2\mathrm{O}§.
            """,
        ),
    )

    @Test fun euler_thread() = conversation(
        "a10_euler",
        user("What is Euler's identity?"),
        bot(
            """
§§e^{i\pi} + 1 = 0§§

It ties together §e§, §i§, §\pi§, 1 and 0.
            """,
        ),
        user("Show the general form"),
        bot(
            """
§§e^{i\theta} = \cos\theta + i\sin\theta§§

Setting §\theta = \pi§ gives the identity.
            """,
        ),
        user("Plot it in python"),
        bot(
            """
```python
import numpy as np
import matplotlib.pyplot as plt

t = np.linspace(0, 2 * np.pi, 200)
plt.plot(np.cos(t), np.sin(t))
plt.axis("equal")
plt.show()
```
            """,
        ),
    )

    @Test fun series_and_limits() = conversation(
        "a11_series",
        user("Разложи экспоненту в ряд"),
        bot(
            """
§§e^x = \sum_{k=0}^{\infty} \frac{x^k}{k!} = 1 + x + \frac{x^2}{2!} + \cdots§§

Сходится при любом §x§. Предел: §\lim_{n\to\infty}\left(1+\frac{1}{n}\right)^n = e§.
            """,
        ),
    )

    @Test fun probability_table() = conversation(
        "a12_probability",
        user("Вероятность хотя бы одной шестёрки при трёх бросках кубика"),
        bot(
            """
Считаем через противоположное событие:

§§P = 1 - \left(\frac{5}{6}\right)^3 = 1 - \frac{125}{216} = \frac{91}{216} \approx 0{,}42§§

| Бросков | Вероятность |
|--:|--:|
| 1 | §\tfrac{1}{6}§ |
| 3 | §\tfrac{91}{216}§ |
            """,
        ),
    )
}
