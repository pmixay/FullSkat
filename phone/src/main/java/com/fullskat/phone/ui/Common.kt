package com.fullskat.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.chat.ChatError
import kotlin.math.roundToInt

/** Ring showing the share of a provider's budget that is left; turns red when it runs low. */
@Composable
fun UsageRing(remaining: Float, modifier: Modifier = Modifier, diameter: Dp = 24.dp) {
    val share = remaining.coerceIn(0f, 1f)
    val color = if (share < 0.15f) Ember.error else Ember.accent
    val description = stringResource(R.string.usage_left, (share * 100).roundToInt())
    Canvas(modifier.size(diameter).semantics { contentDescription = description }) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arc = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(Ember.surfaceHigh, 0f, 360f, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
        if (share > 0f) drawArc(color, -90f, 360f * share, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
    }
}

@Composable
fun errorText(error: ChatError): String = stringResource(
    when (error) {
        ChatError.NO_CONNECTION -> R.string.err_network
        ChatError.SIGNED_OUT -> R.string.err_signed_out
        ChatError.RATE_LIMITED -> R.string.err_rate
        ChatError.DAILY_LIMIT -> R.string.limit_daily_body
        ChatError.QUOTA -> R.string.limit_quota_body
        ChatError.PROVIDER_BUSY -> R.string.limit_busy_body
        ChatError.PROVIDER_AUTH -> R.string.err_provider_auth
        ChatError.NO_PROVIDER -> R.string.err_no_provider
        ChatError.MODEL_UNAVAILABLE -> R.string.err_model_unavailable
        ChatError.IMAGE_UNSUPPORTED -> R.string.err_image_unsupported
        ChatError.IMAGE_UNREADABLE -> R.string.err_image_unreadable
        ChatError.SERVER -> R.string.err_server
    },
)

/** "5 min", "2 h": how long to wait. */
@Composable
fun durationText(sec: Int): String = when {
    sec < 60 -> stringResource(R.string.duration_s, sec)
    sec < 3600 -> stringResource(R.string.duration_min, (sec + 59) / 60)
    else -> stringResource(R.string.duration_h, (sec + 3599) / 3600)
}

/**
 * A failed turn above the composer. Usage limits get a title, an explanation and the wait time; other errors one
 * line. Dismissed with the close button.
 */
@Composable
fun ErrorCard(error: ChatError, retryAfterSec: Int, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val onCard = MaterialTheme.colorScheme.onErrorContainer
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (error) {
            ChatError.DAILY_LIMIT, ChatError.QUOTA -> painterResource(R.drawable.ic_block)
            ChatError.RATE_LIMITED, ChatError.PROVIDER_BUSY -> painterResource(R.drawable.ic_hourglass)
            else -> null
        }
        Box(Modifier.size(32.dp).background(onCard.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = onCard, modifier = Modifier.size(18.dp))
            else Icon(Icons.Default.Warning, contentDescription = null, tint = onCard, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (error.isUsageLimit) {
                val (title, body) = when (error) {
                    ChatError.DAILY_LIMIT -> R.string.limit_daily_title to R.string.limit_daily_body
                    ChatError.QUOTA -> R.string.limit_quota_title to R.string.limit_quota_body
                    ChatError.PROVIDER_BUSY -> R.string.limit_busy_title to R.string.limit_busy_body
                    else -> R.string.limit_rate_title to R.string.limit_rate_body
                }
                Text(stringResource(title), color = onCard, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(body), color = onCard.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (retryAfterSec > 0) stringResource(R.string.limit_retry_in, durationText(retryAfterSec)) else stringResource(R.string.limit_retry_later),
                    color = onCard,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(errorText(error), color = onCard, style = MaterialTheme.typography.bodyMedium)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss), tint = onCard)
        }
    }
}

/** "3 min ago", "now"; older dates as the system abbreviates them. */
fun relativeTime(context: Context, epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (now - epochMs in 0 until 60_000) return context.getString(R.string.time_now)
    return DateUtils.getRelativeTimeSpanString(epochMs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

/** Puts [text] on the clipboard; Android 13+ shows its own confirmation, older versions get a toast. */
fun copyToClipboard(context: Context, text: String, toast: Int = R.string.copied) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("FullSkat", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** The small amber title over a group of settings. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}
