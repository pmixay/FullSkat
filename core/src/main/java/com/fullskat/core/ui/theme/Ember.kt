package com.fullskat.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The "Ember" palette both apps share: charcoal ground, amber accent, chat bubbles. The watch's and the phone's
 * Material themes are built from these, and the rich-text renderer reads them directly.
 */
object Ember {
    val accent = Color(0xFFFFB35C)
    val accentDim = Color(0xFFD9903F)
    val onAccent = Color(0xFF1A1200)
    val background = Color(0xFF121212)
    val surface = Color(0xFF1E1F24)
    val surfaceLow = Color(0xFF17181C)
    val surfaceHigh = Color(0xFF2A2A2A)
    val userBubble = Color(0xFF3A2A12)
    val onUserBubble = Color(0xFFFFE1BE)
    val assistantBubble = Color(0xFF1E1F24)
    val onAssistantBubble = Color(0xFFF1F1F1)
    val userText = Color(0xFFB8B3AA)
    val assistantText = Color(0xFFF1F1F1)
    val label = Color(0xFF8A857C)
    val outline = Color(0xFF3F3F44)
    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF3A0A06)
}
