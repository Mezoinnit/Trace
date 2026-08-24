package com.mezo.trace.feature.alerts

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
import com.mezo.trace.core.network.AlertDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading

/**
 * In-app notifications (plan §4.16, §49): alerts are always visible here,
 * independent of FCM availability. Push delivery to the device arrives with
 * the notifications integration (needs a Firebase project — tracked).
 */
@Composable
fun AlertsScreen(
    state: AlertsUiState,
    onOpenEvent: (String) -> Unit,
) {
    when {
        state.loading && state.alerts.isEmpty() -> Loading()
        state.error != null && state.alerts.isEmpty() ->
            ErrorBox(state.error, Modifier.fillMaxSize())
        state.alerts.isEmpty() -> EmptyState("No alerts. Watches will notify you here.")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.alerts, key = { it.id }) { alert ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Badge(alert.significance)
                            Badge(alert.status)
                        }
                        val title = state.eventTitles[alert.eventId]
                        Text(
                            title ?: "Matched event",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = if (title != null) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth().padding(0.dp)
                            },
                        )
                        Text(
                            alert.createdAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

data class AlertsUiState(
    val loading: Boolean = false,
    val alerts: List<AlertDto> = emptyList(),
    /** eventId → title for display; loaded alongside the alert list. */
    val eventTitles: Map<String, String> = emptyMap(),
    val error: String? = null,
)
