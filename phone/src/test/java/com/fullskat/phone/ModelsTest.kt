package com.fullskat.phone

import com.fullskat.core.net.ApiException
import com.fullskat.core.net.ModelInfo
import com.fullskat.core.net.ProviderInfo
import com.fullskat.phone.chat.ChatError
import com.fullskat.phone.ui.filterLinkCode
import com.fullskat.phone.ui.modelDisplayName
import com.fullskat.phone.ui.previewText
import com.fullskat.phone.ui.providerGroups
import com.fullskat.phone.ui.providerName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun displayNames() {
        assertEquals("Sonnet 5.5", modelDisplayName("claude-sonnet-5-5"))
        assertEquals("Haiku 5.5", modelDisplayName("claude-haiku-5-5"))
        assertEquals("Sonnet 5", modelDisplayName("claude-sonnet-5"))
        assertEquals("gpt-5.5", modelDisplayName("openai/gpt-5.5"))
        assertEquals("k3.1-pro", modelDisplayName("k3.1-pro"))
        assertEquals("OpenAI", providerName("openai"))
        assertEquals("Claude", providerName("anthropic"))
    }

    @Test fun groupsFollowTheServerAndFileStrayModels() {
        val info = ModelInfo(
            model = "claude-haiku-5-5",
            models = listOf("claude-haiku-5-5", "claude-opus-5-5", "kimi-k3", "moonshot/k2"),
            providers = listOf(
                ProviderInfo("openai", listOf("gpt-6.1-terra"), 0.4f, "$4.20"),
                ProviderInfo("anthropic", listOf("claude-haiku-5-5"), null, ""),
            ),
        )
        val groups = providerGroups(info)
        assertEquals(listOf("openai", "anthropic", "kimi", "openrouter"), groups.map { it.id })
        assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), groups[1].models)
        assertEquals(0.4f, groups[0].usageRemaining)
    }

    @Test fun groupsBeforeTheServerAnswered() {
        val groups = providerGroups(ModelInfo())
        assertEquals(listOf("anthropic"), groups.map { it.id })
        assertTrue(groups[0].models.size >= 3)
    }

    @Test fun linkCodeKeepsOnlyTheAlphabet() {
        assertEquals("K7Q2ZP", filterLinkCode("k7q-2zp"))
        assertEquals("K7Q2ZP", filterLinkCode(" K7Q 2ZP X9 "))
        assertEquals("ABC", filterLinkCode("a0b1cOI"))
    }

    @Test fun previewDropsMarkupAndFigures() {
        val content = "## Result\n**Area** is \$\\pi r^2\$.\n```figure\n{\"items\":[]}\n```\nSee [docs](https://x.y)."
        assertEquals("Result Area is π r². [drawing] See docs.", previewText(content, "drawing", "code"))
        assertEquals("S = π r² ≈ 28,27 см²", previewText("\$\$S = \\pi r^2 \\approx 28{,}27\\ \\text{см}^2\$\$", "d", "c"))
        assertEquals("x = 1/2, costs \$5", previewText("\$x = \\frac{1}{2}\$, costs \$5", "d", "c"))
        assertEquals("Use [code] then run", previewText("Use\n```kotlin\nval x = 1\n```\nthen run", "drawing", "code"))
        assertEquals("x > 3 and snake_case", previewText("x > 3 and snake_case", "d", "c"))
        assertEquals("[drawing]", previewText("```figure\n{\"title\":\"half", "drawing", "code"))
    }

    @Test fun errorsMapToKinds() {
        assertEquals(ChatError.NO_CONNECTION, ChatError.of(ApiException.Network("x")))
        assertEquals(ChatError.DAILY_LIMIT, ChatError.of(ApiException.RateLimited(60, "daily_limit")))
        assertEquals(ChatError.QUOTA, ChatError.of(ApiException.Server(502, "m", "upstream_quota")))
        assertEquals(ChatError.IMAGE_UNSUPPORTED, ChatError.of(ApiException.Server(415, "m", "")))
        assertEquals(ChatError.IMAGE_UNREADABLE, ChatError.of(IllegalArgumentException("unreadable image"), preparing = true))
        assertEquals(ChatError.SERVER, ChatError.of(IllegalStateException(), preparing = false))
        assertEquals(90, ChatError.retryAfter(ApiException.Server(502, "m", "upstream_rate_limited", 90)))
        assertTrue(ChatError.PROVIDER_BUSY.isUsageLimit)
        assertFalse(ChatError.SERVER.isUsageLimit)
    }
}
