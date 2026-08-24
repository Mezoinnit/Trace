package com.mezo.trace.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.SourceDetailDto
import com.mezo.trace.core.network.SourceProposalDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading

/**
 * Sources (plan §2.3): paste a URL, review the exact-endpoint proposal,
 * confirm. The system never silently broadens — the preview shows precisely
 * what will be monitored.
 */
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onResolve: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancelProposal: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        OutlinedTextField(
            value = state.input,
            onValueChange = state.onInputChange,
            label = { Text("Paste a URL to monitor") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onResolve(state.input) }, enabled = state.input.isNotBlank() && !state.busy) {
                Text("Resolve")
            }
        }

        state.error?.let { ErrorBox(it, Modifier.fillMaxWidth()) }

        state.proposal?.let { proposal ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Proposed endpoint", style = MaterialTheme.typography.titleSmall)
                    Badge(proposal.provider)
                    Text(proposal.endpointUri, style = MaterialTheme.typography.bodyLarge)
                    proposal.notes.forEach { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm, enabled = !state.busy) { Text("Confirm & add") }
                        TextButton(onClick = onCancelProposal) { Text("Cancel") }
                    }
                }
            }
        }

        when {
            state.loading && state.sources.isEmpty() -> Loading()
            state.sources.isEmpty() && state.proposal == null ->
                EmptyState("No sources yet. Paste a URL above.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sources, key = { it.id }) { source -> SourceCard(source) }
            }
        }
    }
}

@Composable
private fun SourceCard(source: SourceDetailDto) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(source.provider)
                Badge(source.trust)
                Badge(source.status)
            }
            Text(source.displayName ?: source.canonicalIdentity, style = MaterialTheme.typography.titleMedium)
            source.endpoints.forEach { withHealth ->
                Text(
                    "• ${withHealth.endpoint.endpointUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

data class SourcesUiState(
    val input: String = "",
    val busy: Boolean = false,
    val loading: Boolean = false,
    val sources: List<SourceDetailDto> = emptyList(),
    val proposal: SourceProposalDto? = null,
    val error: String? = null,
    val onInputChange: (String) -> Unit = {},
)
