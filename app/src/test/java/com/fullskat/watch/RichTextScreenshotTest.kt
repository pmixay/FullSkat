package com.fullskat.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.fullskat.watch.data.db.DialogEntity
import com.fullskat.watch.data.db.MessageEntity
import com.fullskat.watch.data.db.Role
import com.fullskat.watch.richtext.MathExamples
import com.fullskat.watch.ui.AppViewModel
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.rich.RichText
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
 * Renders Markdown / LaTeX / code answers through the real Compose renderer, as they appear inside an
 * assistant bubble of a 240 dp wide watch (tall, square window so a whole page fits one PNG).
 * Skipped unless run with `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w240dp-h1400dp-notround-xhdpi")
class RichTextScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun enabled() = assumeTrue(System.getProperty("fullskat.screenshots") != null)

        private val SAMPLES = mapOf(
            "r01_quadratic" to "Решим уравнение \$ax^2 + bx + c = 0\$. Дискриминант \$D = b^2 - 4ac\$, корни:\n\n\$\$x_{1,2} = \\frac{-b \\pm \\sqrt{D}}{2a}\$\$\n\nЕсли \$D > 0\$ — два корня, если \$D = 0\$ — один.",
            "r02_python" to "Вот пример:\n\n```python\n@lru_cache(maxsize=None)\ndef fib(n: int) -> int:\n    \"\"\"Return the n-th Fibonacci number.\"\"\"\n    if n < 2:\n        return n  # base case\n    return fib(n - 1) + fib(n - 2)\n\nprint([fib(i) for i in range(10)])\n```\n\nФункция использует `lru_cache`.",
            "r03_kotlin" to "```kotlin\nsealed interface Shape\ndata class Circle(val r: Double) : Shape\n\nfun area(s: Shape): Double = when (s) {\n    is Circle -> Math.PI * s.r * s.r // πr²\n}\n\nval total = listOf(Circle(1.0), Circle(2.5)).sumOf(::area)\nprintln(\"total = \$total\")\n```",
            "r04_shell_json_sql" to "```bash\n#!/bin/bash\nfor f in *.log; do\n  grep -c \"ERROR\" \"\$f\" >> \"\$HOME/errors.txt\"\ndone\n```\n\n```json\n{\"name\": \"FullSkat\", \"version\": 1.5, \"tags\": [\"watch\", \"ai\"], \"ok\": true, \"next\": null}\n```\n\n```sql\nSELECT u.name, COUNT(*) AS n\nFROM users u JOIN orders o ON o.user_id = u.id\nWHERE o.total > 100 -- big orders\nGROUP BY u.name ORDER BY n DESC LIMIT 5;\n```",
            "r05_html_diff_c" to "```html\n<div class=\"card\" id=main>\n  <a href=\"/x\">Link</a> &amp; <b>bold</b>\n</div><!-- end -->\n```\n\n```diff\n@@ -1,3 +1,3 @@\n-old line\n+new line\n unchanged\n```\n\n```c\n#include <stdio.h>\nint main(void) {\n    printf(\"%d\\n\", 42); /* answer */\n    return 0;\n}\n```",
            "r06_markdown_mix" to "## План\n\n1. Собрать **данные**\n2. Посчитать *среднее* — формула \$\\bar{x} = \\frac{1}{n}\\sum_{i=1}^{n} x_i\$\n   - отдельно для каждой группы\n   - и ~~без выбросов~~\n3. Вывод через `print()`\n\n> Помни: корреляция не равна причинности.\n\n- [x] данные\n- [ ] график\n\n---\n\n| Метод | Время | Память |\n|:--|--:|:-:|\n| Быстрая | \$O(n \\log n)\$ | \$O(1)\$ |\n| Пузырёк | \$O(n^2)\$ | \$O(1)\$ |",
            "r07_wide_equation" to "Длинная формула:\n\n\$\$\\sum_{i=0}^{n} \\sum_{j=0}^{m} \\frac{a_{ij} x_i y_j}{\\sqrt{x_i^2 + y_j^2 + \\epsilon}} \\cdot \\left( \\prod_{k=1}^{K} (1 + z_k) \\right)\$\$\n\nи ещё шире:\n\n\$\$f(x_1, x_2, \\ldots, x_n) = a_0 + a_1 x_1 + a_2 x_2 + \\cdots + a_n x_n + \\epsilon\$\$",
            "r08_physics" to "Уравнения Максвелла:\n\n\\begin{align}\n\\nabla \\cdot \\mathbf{E} &= \\frac{\\rho}{\\varepsilon_0} \\\\\n\\nabla \\times \\mathbf{B} &= \\mu_0 \\mathbf{J} + \\mu_0\\varepsilon_0 \\frac{\\partial \\mathbf{E}}{\\partial t}\n\\end{align}\n\nПри \$v \\ll c\$ получаем \$E = \\tfrac{1}{2}mv^2\$, а \$E = mc^2\$ — энергия покоя.",
            "r09_inline_heavy" to "Известно, что \$\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}\$, \$\\binom{n}{2} = \\frac{n(n-1)}{2}\$ и \$\\lim_{x\\to 0}\\frac{\\sin x}{x}=1\$; в частности \$e^{i\\pi} = -1\$. Цена \$5 и \$10 — это не формула.",
        )
    }

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(System.getProperty("fullskat.screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }

    private fun page(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            FullSkatTheme {
                AppScaffold {
                    Column(Modifier.fillMaxWidth().background(Tokens.background).padding(6.dp)) { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Composable
    private fun Bubble(markdown: String) {
        RichText(
            markdown = markdown,
            color = Tokens.onAssistantBubble,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(Tokens.assistantBubble, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }

    // ---- a catalogue of complex display equations, six per page ----

    private fun mathPage(index: Int) {
        val from = index * 6
        val items = MathExamples.display.drop(from).take(6)
        page("m%02d_formulas".format(index + 1)) { for (f in items) Bubble("\$\$$f\$\$") }
    }

    @Test fun math_page_01() = mathPage(0)
    @Test fun math_page_02() = mathPage(1)
    @Test fun math_page_03() = mathPage(2)
    @Test fun math_page_04() = mathPage(3)
    @Test fun math_page_05() = mathPage(4)
    @Test fun math_page_06() = mathPage(5)
    @Test fun math_page_07() = mathPage(6)
    @Test fun math_page_08() = mathPage(7)
    @Test fun math_page_09() = mathPage(8)

    @Test fun inline_math_page() = page("m10_inline") {
        for (f in MathExamples.inline) Bubble("Вот формула \$$f\$ прямо в тексте, а дальше обычные слова, чтобы строка переносилась.")
    }

    // ---- markdown and code answers ----

    private fun sample(key: String) = page(key) { Bubble(SAMPLES.getValue(key)) }

    @Test fun sample_quadratic() = sample("r01_quadratic")
    @Test fun sample_python() = sample("r02_python")
    @Test fun sample_kotlin() = sample("r03_kotlin")
    @Test fun sample_shell_json_sql() = sample("r04_shell_json_sql")
    @Test fun sample_html_diff_c() = sample("r05_html_diff_c")
    @Test fun sample_markdown_mix() = sample("r06_markdown_mix")
    @Test fun sample_wide_equation() = sample("r07_wide_equation")
    @Test fun sample_physics() = sample("r08_physics")
    @Test fun sample_inline_heavy() = sample("r09_inline_heavy")

    @Test fun streaming_partial_states() = page("r10_streaming") {
        // What the user sees while the answer is still arriving: nothing breaks on half-written markup.
        for (partial in listOf("Корни: \$x = \\frac{-b \\pm", "Смотри:\n\$\$\\int_0^1 x^2", "```python\nfor i in range(3", "**жирный и *кури", "| a | b |\n|--|--|\n| 1 |")) Bubble(partial)
    }

    // ---- the real chat screen, round display ----

    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    @Test fun chat_rich_math() = chat("c01_chat_math", "Решение:\n\n\$\$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\$\$\n\nгде \$D = b^2 - 4ac\$.")

    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    @Test fun chat_rich_code() = chat("c02_chat_code", "```python\ndef hi(name):\n    return f\"Hello, {name}!\"\n```")

    private fun chat(name: String, answer: String) {
        val container = FullSkatApp.container(ApplicationProvider.getApplicationContext())
        val id = runBlocking {
            val dao = container.db.dialogDao()
            val now = System.currentTimeMillis()
            dao.insertDialog(DialogEntity("rich", "Вопрос", now, now))
            dao.insertMessage(MessageEntity(dialogId = "rich", role = Role.USER, content = "Объясни коротко", createdAt = now))
            dao.insertMessage(MessageEntity(dialogId = "rich", role = Role.ASSISTANT, content = answer, createdAt = now + 1))
            "rich"
        }
        compose.setContent { FullSkatTheme { AppScaffold { ChatScreen(AppViewModel(container), id, autoListen = false) } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }
}
