package com.mezo.trace.feature.entities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.components.TraceTopBar
import com.mezo.trace.ui.components.relTime
import com.mezo.trace.ui.theme.TraceDimens

/**
 * Entity timeline (plan §3.5/§12): recent events for one entity, newest
 * first. The entry point for "what happened to X over time".
 */
@Composable
fun EntityDetailScreen(
    name: String?,
    events: List<EventDto>,
    loading: Boolean = false,
    onOpenEvent: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TraceTopBar(name ?: "Entity", "Recorded events for this entity, newest first.")
        when {
            loading && events.isEmpty() -> Loading()
            events.isEmpty() -> EmptyState(
                "No recorded events yet",
                "Events will appear here as evidence is gathered.",
                Icons.Filled.Info,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = TraceDimens.ScreenPad,
                    end = TraceDimens.ScreenPad,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val grouped = events.groupBy { (it.occurredAt ?: it.discoveredAt).take(10) }
                grouped.forEach { (day, dayEvents) ->
                    item(key = "day-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(dayEvents, key = { it.id }) { event ->
                        Surface(
                            Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) },
                            shape = RoundedCornerShape(TraceDimens.CardRadius),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(TraceDimens.CardPad)) {
                                Text(
                                    event.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Badge(event.type)
                                    Badge(event.confidence)
                                    Text(
                                        relTime(event.occurredAt ?: event.discoveredAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
