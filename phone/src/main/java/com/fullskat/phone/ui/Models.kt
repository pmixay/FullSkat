package com.fullskat.phone.ui

import com.fullskat.core.account.AccountRepository
import com.fullskat.core.net.ModelInfo

/** One provider's section of the model picker. */
data class ProviderGroup(val id: String, val models: List<String>, val usageRemaining: Float?, val usageLabel: String)

/** Effort levels the server knows, lowest first (offered when it did not say what a model accepts). */
val ALL_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

/** Picker list until the server has reported its models (it validates every request anyway). */
val FALLBACK_MODELS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1")

fun providerName(id: String): String = when (id) {
    "anthropic" -> "Claude"
    "openrouter" -> "OpenRouter"
    "kimi" -> "Kimi"
    "openai" -> "OpenAI"
    else -> id.replaceFirstChar { it.uppercase() }
}

/** Provider of a model id the server listed outside any group (older servers), judged by the naming convention. */
private fun inferProvider(model: String): String = when {
    model.startsWith("claude-") -> "anthropic"
    model.startsWith("kimi-") -> "kimi"
    '/' in model -> "openrouter"
    else -> "other"
}

/** The server's working providers with their models, in its order; stray models are filed by their names. */
fun providerGroups(info: ModelInfo): List<ProviderGroup> {
    val offered = (listOf(info.model) + info.models.ifEmpty { if (info.providers.isEmpty()) FALLBACK_MODELS else emptyList() })
        .filterNotNull().distinct()
    val groups = LinkedHashMap<String, ProviderGroup>()
    info.providers.forEach { groups[it.id] = ProviderGroup(it.id, it.models, it.usageRemaining, it.usageLabel) }
    offered.filter { m -> groups.values.none { m in it.models } }.forEach { m ->
        val id = inferProvider(m)
        val group = groups[id]
        groups[id] = group?.copy(models = group.models + m) ?: ProviderGroup(id, listOf(m), null, "")
    }
    return groups.values.filter { it.models.isNotEmpty() }
}

/**
 * Friendly model name: "claude-sonnet-5-5" -> "Sonnet 5.5", "claude-sonnet-5" -> "Sonnet 5";
 * OpenRouter ids such as "openai/gpt-5.5" -> "gpt-5.5".
 */
fun modelDisplayName(id: String): String {
    if (!id.startsWith("claude-")) return id.substringAfter('/')
    val parts = id.removePrefix("claude-").split('-').filter { it.isNotEmpty() }
    val family = parts.takeWhile { p -> !p.all { it.isDigit() } }
    val version = parts.drop(family.size).takeWhile { p -> p.length <= 2 && p.all { it.isDigit() } }
    if (family.isEmpty() && version.isEmpty()) return id
    val familyName = family.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    return listOf(familyName, version.joinToString(".")).filter { it.isNotEmpty() }.joinToString(" ")
}

/** What the user typed as a link code: upper case, only characters of the server's code alphabet, at most 6. */
fun filterLinkCode(raw: String): String =
    raw.uppercase().filter { it in AccountRepository.CODE_ALPHABET }.take(AccountRepository.CODE_LENGTH)

/** A message as one line for the dialog list: figures and code as short labels, Markdown marks dropped. */
fun previewText(content: String, figureLabel: String, codeLabel: String): String {
    var s = FENCE.replace(content) { m -> if (m.groupValues[1].equals("figure", ignoreCase = true)) " [$figureLabel] " else " [$codeLabel] " }
    s = LINK.replace(s, "$1")
    s = s.replace("$$", "").replace("$", "").replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "")
    s = LINE_MARKS.replace(s, "")
    s = MARKS.replace(s, "")
    return s.replace(SPACE, " ").trim()
}

private val FENCE = Regex("```[ \\t]*([A-Za-z0-9_+-]*)[^\\n]*(?:\\n.*?(?:\\n```|\\z)|\\z)", RegexOption.DOT_MATCHES_ALL)
private val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
private val LINE_MARKS = Regex("^[ \\t]*(#{1,6}|>|[-*+](?= ))[ \\t]*", RegexOption.MULTILINE)
private val MARKS = Regex("(\\*\\*|__|[*`~])")
private val SPACE = Regex("\\s+")
