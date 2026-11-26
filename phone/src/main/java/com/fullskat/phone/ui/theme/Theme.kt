package com.fullskat.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.fullskat.core.attachments.AttachmentRepository
import com.fullskat.core.ui.rich.LocalAttachments
import com.fullskat.core.ui.rich.LocalRichTextStyle
import com.fullskat.core.ui.theme.Ember

/** Material 3 colours from the shared Ember palette: charcoal ground, amber accent, warm user bubbles. */
val EmberColors = darkColorScheme(
    primary = Ember.accent,
    onPrimary = Ember.onAccent,
    primaryContainer = Ember.userBubble,
    onPrimaryContainer = Ember.onUserBubble,
    inversePrimary = Ember.accentDim,
    secondary = Ember.userText,
    onSecondary = Color(0xFF1B1A17),
    secondaryContainer = Color(0xFF33302B),
    onSecondaryContainer = Ember.onUserBubble,
    tertiary = Ember.accentDim,
    onTertiary = Ember.onAccent,
    tertiaryContainer = Ember.surfaceHigh,
    onTertiaryContainer = Ember.accent,
    background = Ember.background,
    onBackground = Ember.assistantText,
    surface = Ember.background,
    onSurface = Ember.assistantText,
    surfaceVariant = Ember.surfaceHigh,
    onSurfaceVariant = Ember.userText,
    surfaceTint = Color(0xFF5A4A36),
    inverseSurface = Ember.assistantText,
    inverseOnSurface = Ember.background,
    error = Ember.error,
    onError = Ember.onError,
    errorContainer = Color(0xFF4A1A15),
    onErrorContainer = Ember.error,
    outline = Ember.outline,
    outlineVariant = Color(0xFF2C2C31),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38383D),
    surfaceDim = Ember.background,
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Ember.surfaceLow,
    surfaceContainer = Ember.surface,
    surfaceContainerHigh = Color(0xFF25262B),
    surfaceContainerHighest = Ember.surfaceHigh,
)

/**
 * The app theme plus what the shared renderer reads: the body text style for [com.fullskat.core.ui.rich.RichText]
 * and the attachment repository for [com.fullskat.core.ui.rich.AttachmentImage].
 */
@Composable
fun PhoneTheme(attachments: AttachmentRepository?, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EmberColors) {
        CompositionLocalProvider(
            LocalRichTextStyle provides MaterialTheme.typography.bodyLarge,
            LocalAttachments provides attachments,
            content = content,
        )
    }
}
