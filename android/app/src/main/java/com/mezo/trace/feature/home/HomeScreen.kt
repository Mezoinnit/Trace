package com.mezo.trace.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.components.SignificanceBadge
import com.mezo.trace.ui.theme.TraceDimens
import com.mezo.trace.ui.theme.significanceColor

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
        state.events.isEmpty() ->
            EmptyState(
                "No intelligence yet",
                "Add a source, then create a watch to start monitoring.",
            )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = TraceDimens.ScreenPad,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.events, key = { it.id }) { event ->
                EventCard(event, onOpenEvent)
            }
            if (state.nextCursor != null) {
                item(key = "load-more") {
                    TextButton(onClick = { state.loadMore() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventDto, onOpen: (String) -> Unit) {
    val accent = significanceColor(event.significance)
    Surface(
        Modifier.fillMaxWidth().clickable { onOpen(event.id) },
        shape = RoundedCornerShape(TraceDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.height(IntrinsicSize.Max)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(Modifier.padding(TraceDimens.CardPad).weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SignificanceBadge(event.significance)
                    Badge(event.confidence)
                    if (event.status != "CONFIRMED") Badge(event.status)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                event.summary?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    com.mezo.trace.ui.components.relTime(event.occurredAt ?: event.discoveredAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
