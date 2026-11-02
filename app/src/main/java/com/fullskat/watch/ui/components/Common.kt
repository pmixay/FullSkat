package com.fullskat.watch.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.fullskat.watch.domain.ErrorKind
import com.fullskat.watch.R
import com.fullskat.watch.ui.theme.Tokens
import kotlin.math.roundToInt

@Composable
fun errorText(kind: ErrorKind): String = when (kind) {
    ErrorKind.NO_CONNECTION -> androidx.compose.ui.res.stringResource(R.string.err_network)
    ErrorKind.UNAUTHORIZED -> androidx.compose.ui.res.stringResource(R.string.err_token)
    ErrorKind.RATE_LIMITED, ErrorKind.DAILY_LIMIT, ErrorKind.QUOTA, ErrorKind.PROVIDER_BUSY ->
        androidx.compose.ui.res.stringResource(R.string.err_rate) // usage limits normally use UsageLimitCard
    ErrorKind.PROVIDER_AUTH -> androidx.compose.ui.res.stringResource(R.string.err_provider_auth)
    ErrorKind.NO_PROVIDER -> androidx.compose.ui.res.stringResource(R.string.err_no_provider)
    ErrorKind.MODEL_UNAVAILABLE -> androidx.compose.ui.res.stringResource(R.string.err_model_unavailable)
    ErrorKind.SERVER -> androidx.compose.ui.res.stringResource(R.string.err_server)
    ErrorKind.NOTHING_HEARD -> androidx.compose.ui.res.stringResource(R.string.err_nothing_heard)
    ErrorKind.MIC -> androidx.compose.ui.res.stringResource(R.string.err_no_mic)
    ErrorKind.SPEECH -> androidx.compose.ui.res.stringResource(R.string.err_speech)
}

@Composable
fun ErrorChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * The block shown when a usage limit is hit: an icon, what happened and, when the server said so, how long to wait.
 * Hard limits (daily cap, spent credits) get a "blocked" icon, temporary pauses an hourglass.
 */
@Composable
fun UsageLimitCard(kind: ErrorKind, retryAfterSec: Int, modifier: Modifier = Modifier) {
    val icon = if (kind == ErrorKind.DAILY_LIMIT || kind == ErrorKind.QUOTA) R.drawable.ic_block else R.drawable.ic_hourglass
    val title = when (kind) {
        ErrorKind.DAILY_LIMIT -> R.string.limit_daily_title
        ErrorKind.QUOTA -> R.string.limit_quota_title
        ErrorKind.PROVIDER_BUSY -> R.string.limit_busy_title
        else -> R.string.limit_rate_title
    }
    val body = when (kind) {
        ErrorKind.DAILY_LIMIT -> R.string.limit_daily_body
        ErrorKind.QUOTA -> R.string.limit_quota_body
        ErrorKind.PROVIDER_BUSY -> R.string.limit_busy_body
        else -> R.string.limit_rate_body
    }
    val onCard = MaterialTheme.colorScheme.onErrorContainer
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(30.dp).background(onCard.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = onCard, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(title), color = onCard, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
        Text(stringResource(body), color = onCard.copy(alpha = 0.85f), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(retryHint(retryAfterSec), color = onCard, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** "Try again in 5 min", or a generic hint when the server gave no wait time. */
@Composable
private fun retryHint(sec: Int): String {
    if (sec <= 0) return stringResource(R.string.limit_retry_later)
    val duration = when {
        sec < 60 -> stringResource(R.string.duration_s, sec)
        sec < 3600 -> stringResource(R.string.duration_min, (sec + 59) / 60)
        else -> stringResource(R.string.duration_h, (sec + 3599) / 3600)
    }
    return stringResource(R.string.limit_retry_in, duration)
}

/** Ring showing the share of a provider's budget that is left; turns red when it runs low. */
@Composable
fun UsageRing(remaining: Float, modifier: Modifier = Modifier, diameter: Dp = 22.dp) {
    val share = remaining.coerceIn(0f, 1f)
    val color = if (share < 0.15f) Tokens.error else Tokens.accent
    val description = stringResource(R.string.usage_left, (share * 100).roundToInt())
    Canvas(modifier.size(diameter).semantics { contentDescription = description }) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arc = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(Tokens.surfaceHigh, 0f, 360f, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
        if (share > 0f) drawArc(color, -90f, 360f * share, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
    }
}

fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
