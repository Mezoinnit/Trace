package com.mezo.trace.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading

/**
 * Home = YOUR INTELLIGENCE (plan §3.3): the event feed, newest first.
 * Cached-vs-current is signalled by the loading state (plan §31).
 */
@Composable
fun HomeScreen(
    state: FeedUiState,
    onOpenEvent: (String) -> Unit,
) {
    when {
        state.loading && state.events.isEmpty() -> Loading()
        state.error != null && state.events.isEmpty() ->
            ErrorBox(state.error, Modifier.fillMaxSize())
        state.events.isEmpty() -> EmptyState("No intelligence yet. Add a source and create a watch.")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.events, key = { it.id }) { event ->
                EventCard(event, onOpenEvent)
            }
            if (state.nextCursor != null) {
                items(listOf("load-more")) {
                    Text(
                        "Load more",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.loadMore() }
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventDto, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(event.id) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(event.type)
                Badge("CONF ${event.confidence}")
                Badge("SIG ${event.significance}")
                if (event.status != "CONFIRMED") Badge(event.status)
            }
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            event.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                event.occurredAt ?: event.discoveredAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

data class FeedUiState(
    val loading: Boolean = false,
    val events: List<EventDto> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val loadMore: () -> Unit = {},
)
