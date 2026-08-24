package com.mezo.trace.feature.entities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Entity timeline (plan §3.5/§12): recent events for one entity, newest
 * first. The entry point for "what happened to X over time".
 */
@Composable
fun EntityDetailScreen(
    name: String?,
    events: List<EventDto>,
    onOpenEvent: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name ?: "Entity", style = MaterialTheme.typography.headlineSmall)
        Text("TIMELINE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

        if (events.isEmpty()) {
            EmptyState("No recorded events yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                event.occurredAt ?: event.discoveredAt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Badge(event.type)
                        }
                    }
                }
            }
        }
    }
}
