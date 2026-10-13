package com.mezo.trace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mezo.trace.feature.alerts.AlertsScreen
import com.mezo.trace.feature.alerts.AlertsUiState
import com.mezo.trace.feature.auth.AuthScreen
import com.mezo.trace.feature.entities.EntityDetailScreen
import com.mezo.trace.feature.events.EventDetailScreen
import com.mezo.trace.feature.home.FeedUiState
import com.mezo.trace.feature.home.HomeScreen
import com.mezo.trace.feature.search.SearchScreen
import com.mezo.trace.feature.sources.SourcesScreen
import com.mezo.trace.feature.sources.SourcesUiState
import com.mezo.trace.feature.watches.WatchesScreen
import com.mezo.trace.feature.watches.WatchesUiState
import com.mezo.trace.ui.components.Loading
import com.mezo.trace.ui.theme.TraceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TraceApp

        setContent {
            TraceTheme {
                val session by app.sessionStore.session.collectAsState(initial = null)
                val initialLoaded = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { app.sessionStore.current(); initialLoaded.value = true }

                when {
                    // Wait for the first DataStore emission before deciding.
                    !initialLoaded.value -> Unit

                    session == null -> AuthGate(app)

                    else -> MainScaffold(app)
                }
            }
        }
    }

    @Composable
    private fun AuthGate(app: TraceApp) {
        AuthScreen(
            onAuthenticated = { /* state flips via session flow */ },
            signIn = { e, p -> app.auth.signIn(e, p) },
            register = { e, p, d -> app.auth.register(e, p, d) },
        )
    }

    @Composable
    private fun MainScaffold(app: TraceApp) {
        val navController = rememberNavController()
        val tabs = listOf(
            Tab("home", "Intelligence") { Icon(Icons.Filled.Home, null) },
            Tab("sources", "Sources") { Icon(Icons.Filled.Search, null) },
            Tab("watches", "Watches") { Icon(Icons.Filled.List, null) },
            Tab("alerts", "Alerts") { Icon(Icons.Filled.Notifications, null) },
        )

        Scaffold(bottomBar = {
            NavigationBar {
                val current = navController.currentBackStackEntryAsState().value?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        }) { padding ->
            NavHost(
                navController,
                startDestination = "home",
                modifier = Modifier.padding(padding),
            ) {
                composable("home") {
                    HomeRoute(app) { id -> navController.navigate("event/$id") }
                }
                composable("search") { SearchRoute(app) { id -> navController.navigate("event/$id") } }
                composable("sources") { SourcesRoute(app) }
                composable("watches") { WatchesRoute(app) }
                composable("alerts") {
                    AlertsRoute(app) { id -> navController.navigate("event/$id") }
                }
                composable("event/{id}") { entry ->
                    val id = entry.arguments?.getString("id")
                    var detail by remember {
                        mutableStateOf<com.mezo.trace.core.network.EventDetailResponse?>(null)
                    }
                    var history by remember {
                        mutableStateOf<List<com.mezo.trace.core.network.EventHistoryDto>>(emptyList())
                    }
                    var related by remember {
                        mutableStateOf<List<com.mezo.trace.core.network.EventDto>>(emptyList())
                    }
                    LaunchedEffect(id) {
                        detail = runCatching { app.events.detail(id!!) }.getOrNull()
                        history = runCatching { app.api.eventHistory(id!!) }.getOrDefault(emptyList())
                        related = runCatching { app.api.relatedEvents(id!!) }.getOrDefault(emptyList())
                    }
                    if (detail == null) {
                        Loading()
                    } else {
                        EventDetailScreen(
                            detail = detail,
                            onOpenEntity = { entityId -> navController.navigate("entity/$entityId") },
                            history = history,
                            related = related,
                            onOpenEvent = { eventId -> navController.navigate("event/$eventId") },
                        )
                    }
                }
                composable("entity/{id}") { entry ->
                    val id = entry.arguments?.getString("id")
                    var detail by remember {
                        mutableStateOf<com.mezo.trace.core.network.EntityDetailResponse?>(null)
                    }
                    LaunchedEffect(id) {
                        detail = runCatching { app.entityRepoFetch(id!!) }.getOrNull()
                    }
                    EntityDetailScreen(
                        name = detail?.entity?.name,
                        events = detail?.recentEvents.orEmpty(),
                        loading = detail == null,
                    ) { eventId -> navController.navigate("event/$eventId") }
                }
            }
        }
    }

    @Composable
    private fun HomeRoute(app: TraceApp, onOpenEvent: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var state by remember {
            mutableStateOf(FeedUiState(loadMore = {}))
        }
        var cursor by remember { mutableStateOf<String?>(null) }

        suspend fun load(reset: Boolean) {
            state = state.copy(loading = true, error = null)
            runCatching { app.events.feed(if (reset) null else cursor) }
                .onSuccess { page ->
                    cursor = page.nextCursor
                    state = FeedUiState(
                        loading = false,
                        events = if (reset) page.items else state.events + page.items,
                        nextCursor = page.nextCursor,
                        loadMore = { scope.launch { load(false) } },
                    )
                }
                .onFailure { e ->
                    state = state.copy(loading = false, error = e.message ?: "failed to load")
                }
        }

        LaunchedEffect(Unit) { load(reset = true) }
        HomeScreen(state, onOpenEvent)
    }

    @Composable
    private fun SourcesRoute(app: TraceApp) {
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf(SourcesUiState()) }

        suspend fun refresh() {
            state = state.copy(loading = true)
            runCatching { app.sources.list() }
                .onSuccess { state = state.copy(loading = false, sources = it) }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }

        LaunchedEffect(Unit) { refresh() }

        SourcesScreen(
            state = state,
            onResolve = { input ->
                scope.launch {
                    state = state.copy(busy = true, error = null)
                    runCatching { app.sources.resolve(input) }
                        .onSuccess { state = state.copy(busy = false, proposal = it) }
                        .onFailure { state = state.copy(busy = false, error = it.message) }
                }
            },
            onConfirm = {
                val proposal = state.proposal ?: return@SourcesScreen
                scope.launch {
                    state = state.copy(busy = true, error = null)
                    try {
                        app.sources.create(proposal)
                        state = state.copy(busy = false, proposal = null)
                        refresh()
                    } catch (e: Exception) {
                        state = state.copy(busy = false, error = e.message)
                    }
                }
            },
            onCancelProposal = { state = state.copy(proposal = null) },
        )

        // keep input change handler wired
        state = state.copy(onInputChange = { state = state.copy(input = it) })
    }

    @Composable
    private fun SearchRoute(app: TraceApp, onOpenEvent: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var searched by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf<List<com.mezo.trace.core.network.EventDto>>(emptyList()) }
        var interpretation by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        SearchScreen(
            busy = busy,
            results = results,
            interpretation = interpretation,
            searched = searched,
            error = error,
            onSearch = { q ->
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val result = app.events.investigate(q.trim())
                        results = result.events
                        interpretation = result.interpretation
                        searched = true
                    } catch (e: Exception) {
                        error = e.message
                        searched = true
                    } finally {
                        busy = false
                    }
                }
            },
            onOpenEvent = onOpenEvent,
        )
    }

    @Composable
    private fun WatchesRoute(app: TraceApp) {
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf(WatchesUiState()) }

        suspend fun refresh() {
            state = state.copy(loading = true)
            runCatching {
                val watches = app.watches.list()
                val endpoints = app.sources.allEndpoints()
                watches to endpoints
            }.onSuccess { (watches, endpoints) ->
                state = state.copy(loading = false, watches = watches, availableEndpoints = endpoints)
            }.onFailure { state = state.copy(loading = false, error = it.message) }
        }

        LaunchedEffect(Unit) { refresh() }

        WatchesScreen(
            state = state,
            onOpenCreate = { state = state.copy(showCreate = true, error = null, interpretNote = null) },
            onCloseCreate = { state = state.copy(showCreate = false, interpretNote = null) },
            onNameChange = { state = state.copy(newName = it) },
            onKeywordsChange = { state = state.copy(newKeywords = it) },
            onDescribeChange = { state = state.copy(newDescription = it) },
            onInterpret = {
                scope.launch {
                    state = state.copy(interpreting = true, error = null, interpretNote = null)
                    try {
                        val proposal = app.watches.interpret(state.newDescription.trim())

                        // Converge the proposed source so it can be selected.
                        val endpoints = if (
                            proposal.endpointUri != null && proposal.adapterId != null
                        ) {
                            app.sources.createSourceFromProposal(proposal)
                            app.sources.allEndpoints()
                        } else {
                            state.availableEndpoints
                        }
                        val selected = if (proposal.endpointUri != null) {
                            endpoints.filter { it.endpointUri == proposal.endpointUri }
                                .map { it.id }.toSet()
                        } else {
                            state.selectedEndpoints
                        }

                        val notes = buildList {
                            if (proposal.uncertainties.isNotEmpty()) {
                                add("Check: " + proposal.uncertainties.joinToString(" · "))
                            }
                            if (proposal.endpointUri == null) {
                                add("add a source manually for the endpoint")
                            }
                        }

                        state = state.copy(
                            interpreting = false,
                            availableEndpoints = endpoints,
                            selectedEndpoints = selected,
                            newName = proposal.suggestedName ?: state.newName,
                            newKeywords = proposal.keywordsInclude.joinToString(", "),
                            interpretNote = notes.joinToString(" · ").takeIf { it.isNotBlank() },
                        )
                    } catch (e: Exception) {
                        state = state.copy(interpreting = false, error = e.message)
                    }
                }
            },
            onToggleEndpoint = { endpoint ->
                val selected = state.selectedEndpoints.toMutableSet()
                if (!selected.add(endpoint.id)) selected.remove(endpoint.id)
                state = state.copy(selectedEndpoints = selected)
            },
            onCreate = {
                scope.launch {
                    state = state.copy(busy = true, error = null)
                    runCatching {
                        app.watches.create(
                            name = state.newName.trim(),
                            endpointIds = state.selectedEndpoints.toList(),
                            keywordsInclude = state.newKeywords.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                        )
                    }.onSuccess {
                        state = WatchesUiState(
                            watches = state.watches,
                            availableEndpoints = state.availableEndpoints,
                        )
                        refresh()
                    }.onFailure { state = state.copy(busy = false, error = it.message) }
                }
            },
            onDelete = { watch ->
                scope.launch {
                    runCatching { app.watches.delete(watch.id) }
                    refresh()
                }
            },
        )
    }

    @Composable
    private fun AlertsRoute(app: TraceApp, onOpenEvent: (String) -> Unit) {
        var state by remember { mutableStateOf(AlertsUiState()) }

        LaunchedEffect(Unit) {
            state = state.copy(loading = true)
            try {
                val alerts = app.alerts.list()
                val titles = mutableMapOf<String, String>()
                for (alert in alerts) {
                    try {
                        titles[alert.eventId] = app.events.detail(alert.eventId).event.title
                    } catch (_: Exception) {
                        // Detail fetch is best-effort for the list view.
                    }
                }
                state = AlertsUiState(alerts = alerts, eventTitles = titles)
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message)
            }
        }
        AlertsScreen(state, onOpenEvent)
    }

    private data class Tab(
        val route: String,
        val label: String,
        val icon: @Composable () -> Unit,
    )
}
