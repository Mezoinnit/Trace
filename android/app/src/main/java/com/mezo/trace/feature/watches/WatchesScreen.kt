package com.mezo.trace.feature.watches

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.SourceEndpointDto
import com.mezo.trace.core.network.WatchDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading

/**
 * Watches (plan §4.14): persistent monitoring rules. Creation binds exact
 * endpoints with optional keyword rules and a significance floor.
 */
@Composable
fun WatchesScreen(
    state: WatchesUiState,
    onCreate: () -> Unit,
    onInterpret: () -> Unit,
    onDelete: (WatchDto) -> Unit,
    onToggleEndpoint: (SourceEndpointDto) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Button(onClick = onCreate, enabled = !state.creating) { Text("New watch") }

        state.error?.let { ErrorBox(it, Modifier.fillMaxWidth()) }

        if (state.showCreateForm) {
            CreateWatchCard(
                state = state,
                onCreate = onCreate,
                onInterpret = onInterpret,
                onToggleEndpoint = onToggleEndpoint,
            )
        }

        when {
            state.loading && state.watches.isEmpty() -> Loading()
            state.watches.isEmpty() && !state.showCreateForm ->
                EmptyState("No watches yet. Create one to start monitoring.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.watches, key = { it.id }) { watch ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Badge(watch.status)
                                watch.minSignificance?.let { Badge("≥ SIG $it") }
                            }
                            Text(watch.name, style = MaterialTheme.typography.titleMedium)
                            if (watch.keywordsInclude.isNotEmpty()) {
                                Text(
                                    "includes: " + watch.keywordsInclude.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (watch.keywordsExclude.isNotEmpty()) {
                                Text(
                                    "excludes: " + watch.keywordsExclude.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            TextButton(onClick = { onDelete(watch) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateWatchCard(
    state: WatchesUiState,
    onCreate: () -> Unit,
    onInterpret: () -> Unit,
    onToggleEndpoint: (SourceEndpointDto) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            OutlinedTextField(
                value = state.newName,
                onValueChange = state.onNameChange,
                label = { Text("Watch name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.newKeywordsInclude,
                onValueChange = state.onKeywordsChange,
                label = { Text("Must include keywords (comma-separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.newDescription,
                onValueChange = state.onDescribeChange,
                label = { Text("…or describe it in plain language") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onInterpret, enabled = !state.busy && state.newDescription.isNotBlank()) {
                    Text(if (state.interpreting) "Interpreting…" else "Fill from description")
                }
            }
            state.interpretNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            Text("Endpoints", style = MaterialTheme.typography.titleSmall)
            if (state.availableEndpoints.isEmpty()) {
                Text(
                    "Add a source first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            state.availableEndpoints.forEach { endpoint ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = endpoint.id in state.selectedEndpoints,
                        onCheckedChange = { onToggleEndpoint(endpoint) },
                    )
                    Column {
                        Text(endpoint.endpointUri, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, enabled = !state.busy && state.canSubmit) { Text("Create") }
                TextButton(onClick = state.onCancelCreate) { Text("Cancel") }
            }
        }
    }
}

data class WatchesUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val creating: Boolean = false,
    val showCreateForm: Boolean = false,
    val watches: List<WatchDto> = emptyList(),
    val availableEndpoints: List<SourceEndpointDto> = emptyList(),
    val selectedEndpoints: Set<String> = emptySet(),
    val newName: String = "",
    val newKeywordsInclude: String = "",
    val newDescription: String = "",
    val interpreting: Boolean = false,
    val interpretNote: String? = null,
    val error: String? = null,
    val onNameChange: (String) -> Unit = {},
    val onKeywordsChange: (String) -> Unit = {},
    val onDescribeChange: (String) -> Unit = {},
    val onInterpret: () -> Unit = {},
    val onCancelCreate: () -> Unit = {},
) {
    val canSubmit: Boolean
        get() = newName.isNotBlank() && selectedEndpoints.isNotEmpty()
}
