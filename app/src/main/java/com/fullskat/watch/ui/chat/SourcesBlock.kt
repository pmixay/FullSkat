package com.fullskat.watch.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import com.fullskat.watch.R
import com.fullskat.core.net.WebSource
import com.fullskat.watch.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * The web pages an answer drew on, under its bubble: a "Sources (n)" header with a chevron, collapsed until
 * tapped (like the thinking block). Expanded, one row per page with its site and title; a tap opens the page
 * on the phone (or in a browser on the watch) and confirms it.
 */
@Composable
fun SourcesBlock(sources: List<WebSource>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, animationSpec = tween(150), label = "chevron")
    val context = LocalContext.current
    val opener = remember(context) { LinkOpener(context) }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<LinkOpener.Result?>(null) }
    // Opened at the end of the chat, the rows would land below the screen edge: scroll them into view. Only on a
    // tap (not saved), so an expanded block that scrolls back into the list does not pull the list along.
    val revealRows = remember { BringIntoViewRequester() }
    var reveals by remember { mutableIntStateOf(0) }
    LaunchedEffect(reveals) { if (reveals > 0) revealRows.bringIntoView() }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp).bringIntoViewRequester(revealRows)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    expanded = !expanded
                    if (expanded) reveals++
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.sources_title, sources.size), color = Tokens.label, fontSize = 12.sp)
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = Tokens.label,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(rotation),
            )
        }
        if (expanded) {
            sources.forEach { source ->
                SourceRow(source) {
                    if (!opening) {
                        opening = true
                        scope.launch {
                            try {
                                outcome = opener.open(source.url).takeIf { it != LinkOpener.Result.WATCH }
                            } finally {
                                opening = false
                            }
                        }
                    }
                }
            }
        }
    }

    val openedText = stringResource(R.string.opened_on_phone)
    val openedStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    OpenOnPhoneDialog(
        visible = outcome == LinkOpener.Result.PHONE,
        onDismissRequest = { outcome = null },
        curvedText = { openOnPhoneDialogCurvedText(text = openedText, style = openedStyle) },
    )
    val failedText = stringResource(if (outcome == LinkOpener.Result.NO_PHONE) R.string.phone_not_connected else R.string.link_failed)
    val failedStyle = ConfirmationDialogDefaults.curvedTextStyle
    FailureConfirmationDialog(
        visible = outcome == LinkOpener.Result.NO_PHONE || outcome == LinkOpener.Result.FAILED,
        onDismissRequest = { outcome = null },
        curvedText = { confirmationDialogCurvedText(text = failedText, style = failedStyle) },
    )
}

/** One page: the site in amber, its title below in one line when there is one. */
@Composable
private fun SourceRow(source: WebSource, onClick: () -> Unit) {
    val domain = source.domain
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.surface)
            .clickable(onClickLabel = stringResource(R.string.open_link), onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = domain, color = Tokens.accent, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (source.title.isNotBlank() && !source.title.equals(domain, ignoreCase = true)) {
                Text(
                    text = source.title,
                    color = Tokens.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = Tokens.label,
            modifier = Modifier.padding(start = 6.dp).size(12.dp),
        )
    }
}
