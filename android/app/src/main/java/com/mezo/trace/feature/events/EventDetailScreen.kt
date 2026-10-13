package com.mezo.trace.feature.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDetailResponse
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.SignificanceBadge
import com.mezo.trace.ui.components.relTime
import com.mezo.trace.ui.theme.TraceDimens

/** Event detail (plan §3.5): what happened, sources, evidence, entities, history. */
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
            .verticalScroll(rememberScrollState())
            .padding(TraceDimens.ScreenPad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            event.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SignificanceBadge(event.significance)
            Badge(event.confidence)
            Badge(event.type)
            if (event.status != "CONFIRMED") Badge(event.status)
        }
        Text(
            relTime(event.occurredAt ?: event.discoveredAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("WHAT HAPPENED")
        Text(
            event.summary ?: "No summary available.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        SectionHeader("SOURCES & EVIDENCE")
        detail.evidence.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(item.role)
                    item.sourceName?.let { Badge(it) }
                    item.sourceTrust?.let { Badge(it) }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.title ?: "(untitled)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                item.canonicalUrl?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (detail.entities.isNotEmpty()) {
            SectionHeader("ENTITIES")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.entities.forEach { entity ->
                    AssistChip(
                        onClick = { onOpenEntity(entity.id) },
                        label = { Text(entity.name) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
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

        if (related.isNotEmpty()) {
            SectionHeader("RELATED EVENTS")
            related.forEach { rel ->
                Text(
                    "• ${rel.title}",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (history.isNotEmpty()) {
            SectionHeader("HISTORY")
            history.forEach { entry ->
                Text(
                    "${entry.at.take(19).replace('T', ' ')} · ${entry.action}" +
                        (entry.detail["band_from"]?.let { " ($it → ${entry.detail["band_to"]})" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(label: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
        Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(6.dp))
}
