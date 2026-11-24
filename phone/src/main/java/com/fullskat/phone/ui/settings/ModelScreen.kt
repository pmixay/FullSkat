package com.fullskat.phone.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.R
import com.fullskat.phone.ui.ProviderGroup
import com.fullskat.phone.ui.UsageRing
import com.fullskat.phone.ui.modelDisplayName
import com.fullskat.phone.ui.providerGroups
import com.fullskat.phone.ui.providerName

/**
 * The model picker: the server's working providers, each a collapsible group with the ring of budget left, and
 * their models. The choice is a shared preference (the watch asks the same model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: SettingsViewModel, onBack: () -> Unit, initiallyExpanded: Set<String> = emptySet()) {
    val session by vm.session.collectAsStateWithLifecycle()
    // Every provider starts collapsed (its header names the selected model); opening one leaves the others alone.
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded.toList()) }
    LaunchedEffect(Unit) { vm.refresh() } // the usage rings go stale

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_model)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val s = session ?: return@Scaffold
        val selected = s.activeModel
        val groups = remember(s.info) { providerGroups(s.info) }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                val isOpen = group.id in expanded
                item(key = "provider-${group.id}") {
                    ProviderHeader(group, isOpen, selected.takeIf { it in group.models }) {
                        expanded = if (isOpen) expanded - group.id else expanded + group.id
                    }
                }
                if (isOpen) {
                    group.models.forEach { m ->
                        item(key = "model-${group.id}-$m") {
                            ModelRow(m, m == selected) {
                                vm.setModel(m)
                                onBack()
                            }
                        }
                    }
                }
                item(key = "divider-${group.id}") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            }
            item {
                Text(
                    stringResource(R.string.settings_model_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ember.label,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** Collapsible provider row: chevron, name, the selected model when collapsed, and the ring of budget left. */
@Composable
private fun ProviderHeader(group: ProviderGroup, expanded: Boolean, selectedModel: String?, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(150), label = "chevron")
    val hint = listOfNotNull(
        selectedModel?.takeIf { !expanded }?.let(::modelDisplayName),
        group.usageLabel.ifBlank { null },
    ).joinToString(" · ")
    ListItem(
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = Ember.label,
                modifier = Modifier.size(24.dp).rotate(rotation),
            )
        },
        headlineContent = { Text(providerName(group.id), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
        supportingContent = if (hint.isEmpty()) null else {
            { Text(hint, color = if (selectedModel != null && !expanded) Ember.accent else Ember.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = group.usageRemaining?.let { { UsageRing(it) } },
        colors = listColors(),
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@Composable
private fun ModelRow(model: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .then(if (selected) Modifier.background(Ember.userBubble.copy(alpha = 0.35f)) else Modifier)
            .padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Ember.accent))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(modelDisplayName(model), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if ('/' in model || modelDisplayName(model) != model) {
                Text(model, style = MaterialTheme.typography.bodySmall, color = Ember.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
