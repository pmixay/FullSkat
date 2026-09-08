package com.fullskat.watch.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.fullskat.watch.domain.ErrorKind
import com.fullskat.watch.R

@Composable
fun errorText(kind: ErrorKind): String = when (kind) {
    ErrorKind.NO_CONNECTION -> androidx.compose.ui.res.stringResource(R.string.err_network)
    ErrorKind.UNAUTHORIZED -> androidx.compose.ui.res.stringResource(R.string.err_token)
    ErrorKind.RATE_LIMITED -> androidx.compose.ui.res.stringResource(R.string.err_rate)
    ErrorKind.SERVER -> androidx.compose.ui.res.stringResource(R.string.err_server)
    ErrorKind.NOTHING_HEARD -> androidx.compose.ui.res.stringResource(R.string.err_nothing_heard)
    ErrorKind.MIC -> androidx.compose.ui.res.stringResource(R.string.err_no_mic)
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

fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
