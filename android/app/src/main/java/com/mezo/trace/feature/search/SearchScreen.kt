package com.mezo.trace.feature.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.SearchField
import com.mezo.trace.ui.components.TraceTopBar
import com.mezo.trace.ui.theme.TraceDimens

/**
 * Search / investigation (plan §3.4): a question in, structured intelligence
 * out. The interpretation line shows how the query was scoped. State lives
 * here directly — no callback-in-state plumbing.
 */
@Composable
fun SearchScreen(
    busy: Boolean,
    results: List<EventDto>,
    interpretation: String?,
    searched: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TraceTopBar("Investigate", "Ask a question — Trace scopes and searches for you.")

        Column(Modifier.padding(horizontal = TraceDimens.ScreenPad)) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "What changed in androidx this month?",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !busy) onSearch(query) }),
                trailing = {
                    IconButton(
                        onClick = { if (query.isNotBlank() && !busy) onSearch(query) },
                        enabled = query.isNotBlank() && !busy,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Ask",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }

        interpretation?.let {
            Text(
                "Interpreted as: $it",
                modifier = Modifier.padding(horizontal = TraceDimens.ScreenPad, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let {
            ErrorBox(it, Modifier.padding(horizontal = TraceDimens.ScreenPad, vertical = 6.dp))
        }

        when {
            busy && results.isEmpty() -> Text(
                "Investigating…",
                modifier = Modifier.padding(TraceDimens.ScreenPad),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results.isEmpty() && searched -> EmptyState("Nothing found", "Try broader terms or another phrasing.")
            results.isEmpty() -> {}
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = TraceDimens.ScreenPad,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.id }) { event ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) },
                        shape = RoundedCornerShape(TraceDimens.CardRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(TraceDimens.CardPad)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Badge(event.type)
                                Badge(event.confidence)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                event.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
