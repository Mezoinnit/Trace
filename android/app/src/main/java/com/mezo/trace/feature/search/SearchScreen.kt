package com.mezo.trace.feature.search

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState

/**
 * Search / investigation (plan §3.4): a question in, structured intelligence
 * out. The interpretation line shows how the query was scoped.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onSearch: () -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.onQueryChange(it) },
            label = { Text("What do you want to know?") },
            placeholder = { Text("What changed in androidx/androidx this month?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSearch, enabled = state.query.isNotBlank() && !state.busy) {
            Text(if (state.busy) "Investigating…" else "Ask")
        }

        state.interpretation?.let {
            Text(
                "Interpreted as: $it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        state.error?.let { ErrorText(it) }

        when {
            state.busy && state.results.isEmpty() -> LoadingRow()
            state.results.isEmpty() && state.searched -> EmptyState("Nothing found. Try broader terms.")
            state.results.isEmpty() -> {}
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.results, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Badge(event.type)
                                Badge("CONF ${event.confidence}")
                            }
                            Text(event.title, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Text("Searching…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

data class SearchUiState(
    val query: String = "",
    val busy: Boolean = false,
    val searched: Boolean = false,
    val results: List<EventDto> = emptyList(),
    val interpretation: String? = null,
    val error: String? = null,
    val onQueryChange: (String) -> Unit = {},
)
