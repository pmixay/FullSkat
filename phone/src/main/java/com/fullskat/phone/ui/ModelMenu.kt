package com.fullskat.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fullskat.core.net.UsageWindow
import com.fullskat.core.session.SessionState
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import kotlin.math.roundToInt

/**
 * The active model's name with a chevron; tapping it drops down the models of the working providers, grouped by
 * provider (with each provider's budget left), right where it is, instead of a separate screen. [onPick] gets the
 * chosen model id. [expandedInitially] is for screenshots.
 */
@Composable
fun ModelMenu(
    session: SessionState,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    expandedInitially: Boolean = false,
) {
    var open by remember { mutableStateOf(expandedInitially) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(150), label = "chevron")
    val selected = session.activeModel
    Box(modifier) {
        Row(
            Modifier.clickable(role = Role.DropdownList, onClickLabel = stringResource(R.string.settings_model)) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let(::modelDisplayName) ?: stringResource(R.string.effort_default),
                style = style,
                color = Ember.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = stringResource(if (open) R.string.collapse else R.string.expand),
                tint = Ember.accent,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp), shape = MenuShape) {
            val groups = providerGroups(session.info)
            groups.forEachIndexed { i, group ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ProviderLabel(group)
                for (model in group.models) {
                    val isSelected = model == selected
                    DropdownMenuItem(
                        leadingIcon = {
                            if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Ember.accent)
                            else Spacer(Modifier.width(24.dp))
                        },
                        text = {
                            Column {
                                Text(
                                    modelDisplayName(model),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) Ember.accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Medium else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (modelDisplayName(model) != model) {
                                    Text(model, style = MaterialTheme.typography.bodySmall, color = Ember.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        onClick = {
                            open = false
                            if (!isSelected) onPick(model)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A provider's name above its models, with its budget left (label and ring) when the server knows it. When the
 * provider has separate quotas (a 5-hour and a weekly one), the ring shows the tightest and a tap on it unfolds
 * each quota with its share left and when it resets; folded by default to keep the menu short.
 */
@Composable
private fun ProviderLabel(group: ProviderGroup) {
    var details by remember(group.id) { mutableStateOf(false) }
    val expandable = group.usageWindows.isNotEmpty()
    val chevron by animateFloatAsState(if (details) 180f else 0f, tween(150), label = "usage chevron")
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerName(group.id),
                style = MaterialTheme.typography.labelLarge,
                color = Ember.label,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (expandable) Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(if (details) R.string.usage_hide_limits else R.string.usage_show_limits),
                        ) { details = !details } else Modifier,
                    )
                    .padding(start = 6.dp, end = if (expandable) 2.dp else 0.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.usageLabel.isNotBlank()) {
                    Text(group.usageLabel, style = MaterialTheme.typography.labelMedium, color = Ember.label, modifier = Modifier.padding(end = 6.dp))
                }
                group.usageRemaining?.let { UsageRing(it, diameter = 16.dp) }
                if (expandable) {
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        contentDescription = null,
                        tint = Ember.label,
                        modifier = Modifier.size(16.dp).rotate(chevron),
                    )
                }
            }
        }
        AnimatedVisibility(visible = details, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = System.currentTimeMillis() / 1000
                group.usageWindows.forEach { UsageWindowRow(it, now) }
            }
        }
    }
}

/** One quota: "5 h · resets in 2 h 10 min" with the share left, above a thin bar. */
@Composable
private fun UsageWindowRow(window: UsageWindow, nowSec: Long) {
    val share = window.remaining?.coerceIn(0f, 1f)
    val color = if (share != null && share < 0.15f) Ember.error else Ember.accent
    val name = windowName(window.seconds)
    val left = window.resetsAt - nowSec
    val title = if (window.resetsAt > 0 && left > 0) stringResource(R.string.usage_window_resets, name, untilText(left)) else name
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Ember.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                share?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = if (share != null && share < 0.15f) Ember.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ember.surfaceHigh),
        ) {
            if (share != null && share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** "5 h", "Week", "Day", "Month": the length of a quota. */
@Composable
private fun windowName(seconds: Long): String = when {
    seconds == 7 * DAY -> stringResource(R.string.usage_window_week)
    seconds == DAY -> stringResource(R.string.usage_window_day)
    seconds in 28 * DAY..31 * DAY -> stringResource(R.string.usage_window_month)
    seconds % DAY == 0L -> stringResource(R.string.duration_d, (seconds / DAY).toInt())
    seconds % 3600 == 0L -> stringResource(R.string.duration_h, (seconds / 3600).toInt())
    else -> stringResource(R.string.duration_min, ((seconds + 59) / 60).toInt())
}

/** "45 min", "2 h 10 min", "3 d 4 h": time until a quota resets, to two units at most. */
@Composable
private fun untilText(sec: Long): String {
    val minutes = (sec + 59) / 60
    return when {
        minutes < 60 -> stringResource(R.string.duration_min, minutes.toInt())
        minutes < 24 * 60 -> listOfNotNull(
            stringResource(R.string.duration_h, (minutes / 60).toInt()),
            (minutes % 60).takeIf { it > 0 }?.let { stringResource(R.string.duration_min, it.toInt()) },
        ).joinToString(" ")
        else -> {
            val hours = minutes / 60
            listOfNotNull(
                stringResource(R.string.duration_d, (hours / 24).toInt()),
                (hours % 24).takeIf { it > 0 }?.let { stringResource(R.string.duration_h, it.toInt()) },
            ).joinToString(" ")
        }
    }
}

private const val DAY = 86_400L

/** The dropdown's corners: rounder than Material's 4 dp default, like the app's cards and sheets. */
private val MenuShape = RoundedCornerShape(16.dp)
