package com.fullskat.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.fullskat.core.ui.rich.LocalRichTextStyle
import androidx.compose.ui.graphics.Color
import com.fullskat.core.ui.theme.Ember
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Visual tokens for the "Ember" variant: charcoal ground, amber accent, chat bubbles,
 * a round floating mic button and a bezel ring that breathes while listening.
 */
object Tokens {
    val accent = Ember.accent
    val onAccent = Ember.onAccent
    val background = Ember.background
    val surface = Ember.surface
    val surfaceHigh = Ember.surfaceHigh
    val userBubble = Ember.userBubble
    val onUserBubble = Ember.onUserBubble
    val assistantBubble = Ember.assistantBubble
    val onAssistantBubble = Ember.onAssistantBubble
    val userText = Ember.userText
    val assistantText = Ember.assistantText
    val label = Ember.label
    val error = Ember.error
    val onError = Ember.onError
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
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalRichTextStyle provides MaterialTheme.typography.bodyLarge, content = content)
    }
}
