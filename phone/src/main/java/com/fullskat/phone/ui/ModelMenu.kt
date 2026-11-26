package com.fullskat.phone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fullskat.core.session.SessionState
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R

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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
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

/** A provider's name above its models, with its budget left (label and ring) when the server knows it. */
@Composable
private fun ProviderLabel(group: ProviderGroup) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            providerName(group.id),
            style = MaterialTheme.typography.labelLarge,
            color = Ember.label,
            modifier = Modifier.weight(1f),
        )
        if (group.usageLabel.isNotBlank()) {
            Text(group.usageLabel, style = MaterialTheme.typography.labelMedium, color = Ember.label, modifier = Modifier.padding(end = 6.dp))
        }
        group.usageRemaining?.let { UsageRing(it, diameter = 16.dp) }
    }
}
