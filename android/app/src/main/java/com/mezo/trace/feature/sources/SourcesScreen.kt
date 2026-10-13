package com.mezo.trace.feature.sources

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mezo.trace.core.network.SourceDetailDto
import com.mezo.trace.core.network.SourceProposalDto
import com.mezo.trace.ui.components.Badge
import com.mezo.trace.ui.components.EmptyState
import com.mezo.trace.ui.components.ErrorBox
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.components.PrimaryButton
import com.mezo.trace.ui.components.SearchField
import com.mezo.trace.ui.components.TraceTopBar
import com.mezo.trace.ui.theme.TraceDimens

/**
 * Sources (plan §2.3): paste a URL, review the exact-endpoint proposal,
 * confirm. The system never silently broadens — the preview shows precisely
 * what will be monitored.
 */
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onResolve: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancelProposal: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TraceTopBar("Sources", "Paste any URL — Trace finds the exact feed to monitor.")

        Column(Modifier.padding(horizontal = TraceDimens.ScreenPad)) {
            SearchField(
                value = state.input,
                onValueChange = state.onInputChange,
                placeholder = "https://github.com/facebook/react",
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = if (state.busy) "Resolving…" else "Resolve",
                enabled = state.input.isNotBlank() && !state.busy,
                onClick = { onResolve(state.input) },
            )
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                ErrorBox(it, Modifier.fillMaxWidth())
            }
        }

        state.proposal?.let { proposal ->
            ProposalCard(
                proposal = proposal,
                busy = state.busy,
                onConfirm = onConfirm,
                onCancel = onCancelProposal,
            )
        }

        when {
            state.loading && state.sources.isEmpty() -> Loading()
            state.sources.isEmpty() && state.proposal == null ->
                EmptyState("No sources yet", "Paste a URL above to add your first source.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = TraceDimens.ScreenPad,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sources, key = { it.id }) { source -> SourceCard(source) }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: SourceProposalDto,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        Modifier.padding(horizontal = TraceDimens.ScreenPad, vertical = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(TraceDimens.CardRadius),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(TraceDimens.CardPad)) {
            Text(
                "PROPOSED ENDPOINT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(proposal.provider)
                Badge(proposal.trust)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                proposal.endpointUri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            proposal.notes.forEach { note ->
                Spacer(Modifier.height(4.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Confirm & add",
                    enabled = !busy,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun SourceCard(source: SourceDetailDto) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TraceDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(TraceDimens.CardPad)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(source.provider)
                Badge(source.trust)
                Badge(source.status)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                source.displayName ?: source.canonicalIdentity,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            source.endpoints.forEach { withHealth ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "• ${withHealth.endpoint.endpointUri}" +
                        (withHealth.health?.let { " — ${it.state.lowercase()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

data class SourcesUiState(
    val input: String = "",
    val busy: Boolean = false,
    val loading: Boolean = false,
    val sources: List<SourceDetailDto> = emptyList(),
    val proposal: SourceProposalDto? = null,
    val error: String? = null,
    val onInputChange: (String) -> Unit = {},
)
