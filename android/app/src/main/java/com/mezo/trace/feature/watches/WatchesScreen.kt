package com.mezo.trace.feature.watches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.SourceEndpointDto
import com.mezo.trace.core.network.WatchDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.components.PrimaryButton
import com.mezo.trace.ui.components.SignificanceBadge
import com.mezo.trace.ui.components.TraceTopBar
import com.mezo.trace.ui.theme.TraceDimens

/**
 * Watches (plan §4.14): persistent monitoring rules. Creation happens in a
 * bottom sheet — name first, optional keyword rules, optional plain-language
 * AI interpretation, then exact endpoint selection.
 */
@Composable
fun WatchesScreen(
    state: WatchesUiState,
    onOpenCreate: () -> Unit,
    onCloseCreate: () -> Unit,
    onNameChange: (String) -> Unit,
    onKeywordsChange: (String) -> Unit,
    onDescribeChange: (String) -> Unit,
    onInterpret: () -> Unit,
    onToggleEndpoint: (SourceEndpointDto) -> Unit,
    onCreate: () -> Unit,
    onDelete: (WatchDto) -> Unit,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TraceTopBar(
                "Watches",
                "Persistent rules that turn new evidence into alerts.",
            )
            when {
                state.loading && state.watches.isEmpty() -> Loading()
                state.watches.isEmpty() && !state.showCreate ->
                    EmptyState(
                        "No watches yet",
                        "Tap “New watch” to start monitoring a source.",
                    )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = TraceDimens.ScreenPad,
                        top = 4.dp,
                        end = TraceDimens.ScreenPad,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.watches, key = { it.id }) { watch ->
                        WatchCard(watch, onDelete)
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onOpenCreate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(TraceDimens.ButtonRadius),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New watch")
        }
    }

    if (state.showCreate) {
        CreateWatchSheet(
            state = state,
            onNameChange = onNameChange,
            onKeywordsChange = onKeywordsChange,
            onDescribeChange = onDescribeChange,
            onInterpret = onInterpret,
            onToggleEndpoint = onToggleEndpoint,
            onCreate = onCreate,
            onClose = onCloseCreate,
        )
    }
}

@Composable
private fun WatchCard(watch: WatchDto, onDelete: (WatchDto) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TraceDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(TraceDimens.CardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        watch.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(watch) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Delete watch",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(watch.status)
                watch.minSignificance?.let { SignificanceBadge(it) }
            }
            if (watch.keywordsInclude.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "includes: " + watch.keywordsInclude.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (watch.keywordsExclude.isNotEmpty()) {
                Text(
                    "excludes: " + watch.keywordsExclude.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateWatchSheet(
    state: WatchesUiState,
    onNameChange: (String) -> Unit,
    onKeywordsChange: (String) -> Unit,
    onDescribeChange: (String) -> Unit,
    onInterpret: () -> Unit,
    onToggleEndpoint: (SourceEndpointDto) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempted by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(TraceDimens.FieldRadius + 2.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    ModalBottomSheet(
        onDismissRequest = { attempted = false; onClose() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TraceDimens.ScreenPad)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "New watch",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = state.newName,
                onValueChange = onNameChange,
                label = { Text("Name") },
                placeholder = { Text("e.g. React releases") },
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
                isError = attempted && state.newName.isBlank(),
                supportingText = if (attempted && state.newName.isBlank()) {
                    { Text("Name is required", color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.newKeywords,
                onValueChange = onKeywordsChange,
                label = { Text("Must include keywords (optional)") },
                placeholder = { Text("release, stable, v") },
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.newDescription,
                onValueChange = onDescribeChange,
                label = { Text("…or describe it in plain language") },
                placeholder = { Text("Tell me when React ships a new stable release") },
                minLines = 2,
                shape = fieldShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onInterpret, enabled = !state.interpreting && state.newDescription.isNotBlank()) {
                Text(
                    if (state.interpreting) "Interpreting…" else "Fill from description",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.interpretNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                "MONITOR THESE ENDPOINTS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.availableEndpoints.isEmpty()) {
                Text(
                    "Add a source first (Sources tab).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.availableEndpoints.forEach { endpoint ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleEndpoint(endpoint) },
                ) {
                    Checkbox(
                        checked = endpoint.id in state.selectedEndpoints,
                        onCheckedChange = { onToggleEndpoint(endpoint) },
                    )
                    Column {
                        Text(
                            endpoint.endpointUri,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            endpoint.status.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let { ErrorBox(it, Modifier.fillMaxWidth()) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = if (state.busy) "Creating…" else "Create watch",
                    enabled = !state.busy && state.canSubmit,
                    onClick = {
                        attempted = true
                        if (state.canSubmit) onCreate()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { attempted = false; onClose() }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

data class WatchesUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val showCreate: Boolean = false,
    val watches: List<WatchDto> = emptyList(),
    val availableEndpoints: List<SourceEndpointDto> = emptyList(),
    val selectedEndpoints: Set<String> = emptySet(),
    val newName: String = "",
    val newKeywords: String = "",
    val newDescription: String = "",
    val interpreting: Boolean = false,
    val interpretNote: String? = null,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = newName.isNotBlank() && selectedEndpoints.isNotEmpty()
}
