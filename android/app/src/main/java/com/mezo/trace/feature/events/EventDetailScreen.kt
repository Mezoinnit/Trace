package com.mezo.trace.feature.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge

/** Event detail (plan §3.5). Evidence/entity surfaces arrive with the
 *  backend's detail enrichment; core facts are shown now. */
@Composable
fun EventDetailScreen(event: EventDto?) {
    if (event == null) return
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
    }
}
