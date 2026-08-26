package com.mezo.trace.feature.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDetailResponse
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge

/** Event detail (plan §3.5): what happened, sources, supporting evidence,
 *  linked entities, contradictions if contested. */
@Composable
fun EventDetailScreen(
    detail: EventDetailResponse?,
    onOpenEntity: (String) -> Unit,
    history: List<com.mezo.trace.core.network.EventHistoryDto> = emptyList(),
    related: List<EventDto> = emptyList(),
    onOpenEvent: (String) -> Unit = {},
) {
    val event = detail?.event ?: return
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(event.title, style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(event.type)
            Badge("CONF ${event.confidence}")
            Badge("SIG ${event.significance}")
            Badge(event.status)
        }
        Text("WHAT HAPPENED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(event.summary ?: "No summary available.", style = MaterialTheme.typography.bodyMedium)

        Text("WHEN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "occurred: " + (event.occurredAt ?: "unknown"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text("discovered: ${event.discoveredAt}", style = MaterialTheme.typography.bodySmall)

        // SOURCES & EVIDENCE (plan §12: attribution + canonical links).
        Text("SOURCES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        detail.evidence.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(item.role)
                    item.sourceName?.let { Badge(it) }
                    item.sourceTrust?.let { Badge(it) }
                }
                Text(item.title ?: "(untitled)", style = MaterialTheme.typography.bodyMedium)
                item.canonicalUrl?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // ENTITIES (timeline entry points).
        if (detail.entities.isNotEmpty()) {
            Text("ENTITIES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.entities.forEach { entity ->
                    androidx.compose.material3.AssistChip(
                        onClick = { onOpenEntity(entity.id) },
                        label = { Text(entity.name) },
                    )
                }
            }
        }

        if (event.status == "CONTESTED") {
            Text(
                "⚠ Sources disagree — supporting and contradicting evidence are both preserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // RELATED EVENTS (plan §3.5).
        if (related.isNotEmpty()) {
            Text("RELATED EVENTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            related.forEach { rel ->
                Text(
                    "• ${rel.title}",
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        }

        // HISTORY (plan §12): status/confidence transitions over time.
        if (history.isNotEmpty()) {
            Text("HISTORY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            history.forEach { entry ->
                Text(
                    "${entry.at.take(19)} · ${entry.action}" +
                        (entry.detail["band_from"]?.let { " ($it → ${entry.detail["band_to"]})" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
