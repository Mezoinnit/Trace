package com.mezo.trace.feature.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.AlertDto
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.components.SignificanceBadge
import com.mezo.trace.ui.components.TraceTopBar
import com.mezo.trace.ui.components.relTime
import com.mezo.trace.ui.theme.TraceDimens
import com.mezo.trace.ui.theme.significanceColor

/**
 * In-app notifications (plan §4.16, §49): alerts are always visible here,
 * independent of FCM availability. Push delivery arrives with the
 * notifications integration.
 */
@Composable
fun AlertsScreen(
    state: AlertsUiState,
    onOpenEvent: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TraceTopBar("Alerts", "Matches from your watches, newest first.")
        when {
            state.loading && state.alerts.isEmpty() -> Loading()
            state.error != null && state.alerts.isEmpty() ->
                ErrorBox(state.error, Modifier.fillMaxSize())
            state.alerts.isEmpty() ->
                EmptyState(
                    "No alerts yet",
                    "Watches will notify you here when something matches.",
                    Icons.Filled.Notifications,
                )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = TraceDimens.ScreenPad,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.alerts, key = { it.id }) { alert ->
                    AlertCard(alert, state.eventTitles[alert.eventId], onOpenEvent)
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: AlertDto,
    title: String?,
    onOpenEvent: (String) -> Unit,
) {
    val accent = significanceColor(alert.significance)
    Surface(
        Modifier.fillMaxWidth().clickable { onOpenEvent(alert.eventId) },
        shape = RoundedCornerShape(TraceDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(TraceDimens.CardPad)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (alert.status.equals("PENDING", true)) accent else Color.Transparent,
                            CircleShape,
                        ),
                )
                SignificanceBadge(alert.significance)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title ?: "Matched event",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                relTime(alert.createdAt) + "  ·  ${alert.status.lowercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
