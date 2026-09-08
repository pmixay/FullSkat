package com.fullskat.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Visual tokens for the "Ember" variant: charcoal ground, amber accent, chat bubbles,
 * a round floating mic button and a bezel ring that breathes while listening.
 */
object Tokens {
    val accent = Color(0xFFFFB35C)
    val onAccent = Color(0xFF1A1200)
    val background = Color(0xFF121212)
    val surface = Color(0xFF1E1F24)
    val surfaceHigh = Color(0xFF2A2A2A)
    val userBubble = Color(0xFF3A2A12)
    val onUserBubble = Color(0xFFFFE1BE)
    val assistantBubble = Color(0xFF1E1F24)
    val onAssistantBubble = Color(0xFFF1F1F1)
    val userText = Color(0xFFB8B3AA)
    val assistantText = Color(0xFFF1F1F1)
    val label = Color(0xFF8A857C)
    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF3A0A06)
}

private val scheme = ColorScheme(
    primary = Tokens.accent,
    primaryDim = Color(0xFFD9903F),
    primaryContainer = Tokens.userBubble,
    onPrimary = Tokens.onAccent,
    onPrimaryContainer = Tokens.onUserBubble,
    secondary = Tokens.userText,
    secondaryDim = Color(0xFF8E8A82),
    secondaryContainer = Tokens.surfaceHigh,
    onSecondary = Color(0xFF000000),
    onSecondaryContainer = Tokens.assistantText,
    tertiary = Tokens.accent,
    tertiaryDim = Color(0xFFD9903F),
    tertiaryContainer = Tokens.surfaceHigh,
    onTertiary = Tokens.onAccent,
    onTertiaryContainer = Tokens.accent,
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Tokens.surface,
    surfaceContainerHigh = Tokens.surfaceHigh,
    onSurface = Tokens.assistantText,
    onSurfaceVariant = Tokens.userText,
    outline = Color(0xFF3F3F44),
    outlineVariant = Color(0xFF2A2A2E),
    background = Tokens.background,
    onBackground = Tokens.assistantText,
    error = Tokens.error,
    onError = Tokens.onError,
    errorContainer = Color(0xFF5A1A14),
    onErrorContainer = Tokens.error,
)

@Composable
fun FullSkatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
