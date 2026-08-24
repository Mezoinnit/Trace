package com.mezo.trace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mezo.trace.feature.alerts.AlertsScreen
import com.mezo.trace.feature.alerts.AlertsUiState
import com.mezo.trace.feature.auth.AuthScreen
import com.mezo.trace.feature.events.EventDetailScreen
import com.mezo.trace.feature.home.FeedUiState
import com.mezo.trace.feature.home.HomeScreen
import com.mezo.trace.feature.sources.SourcesScreen
import com.mezo.trace.feature.sources.SourcesUiState
import com.mezo.trace.feature.watches.WatchesScreen
import com.mezo.trace.feature.watches.WatchesUiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TraceApp

        setContent {
            MaterialTheme {
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
        val scope = rememberCoroutineScope()
        AuthScreen(
            onAuthenticated = { /* state flips via session flow */ },
            signIn = { e, p -> app.auth.signIn(e, p) },
            register = { e, p, d -> app.auth.register(e, p, d) },
        )
        // Touch scope so lint sees it used for future refresh flows.
        scope.let { }
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
                composable("home") { HomeRoute(app) { id -> navController.navigate("event/$id") } }
                composable("sources") { SourcesRoute(app) }
                composable("watches") { WatchesRoute(app) }
                composable("alerts") { AlertsRoute(app) { id -> navController.navigate("event/$id") } }
                composable("event/{id}") { entry ->
                    val id = entry.arguments?.getString("id")
                    var detail by remember { mutableStateOf<com.mezo.trace.core.network.EventDto?>(null) }
                    LaunchedEffect(id) {
                        detail = runCatching { app.events.detail(id!!) }.getOrNull()
                    }
                    EventDetailScreen(detail)
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
    private fun WatchesRoute(app: TraceApp) {
        val scope = rememberCoroutineScope()
        var state by remember {
            mutableStateOf(
                WatchesUiState(
                    onNameChange = { },
                    onKeywordsChange = { },
                    onDescribeChange = { },
                    onCancelCreate = { },
                ),
            )
        }

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

        LaunchedEffect(Unit) {
            state = state.copy(onNameChange = { state = state.copy(newName = it) })
            state = state.copy(onKeywordsChange = { state = state.copy(newKeywordsInclude = it) })
            state = state.copy(onDescribeChange = { state = state.copy(newDescription = it) })
            state = state.copy(onCancelCreate = { state = state.copy(showCreateForm = false, interpretNote = null) })

            state = state.copy(onInterpret = {
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
                        val interpretNote = notes.joinToString(" · ").takeIf { it.isNotBlank() }

                        state = state.copy(
                            interpreting = false,
                            availableEndpoints = endpoints,
                            selectedEndpoints = selected,
                            newName = proposal.suggestedName ?: state.newName,
                            newKeywordsInclude = proposal.keywordsInclude.joinToString(", "),
                            interpretNote = interpretNote,
                        )
                    } catch (e: Exception) {
                        state = state.copy(interpreting = false, error = e.message)
                    }
                }
            })
            refresh()
        }

        WatchesScreen(
            state = state,
            onInterpret = {
                // handled through state.onInterpret set below
                state.onInterpret()
            },
            onCreate = {
                scope.launch {
                    state = state.copy(busy = true, error = null)
                    runCatching {
                        app.watches.create(
                            name = state.newName.trim(),
                            endpointIds = state.selectedEndpoints.toList(),
                            keywordsInclude = state.newKeywordsInclude.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                        )
                    }.onSuccess {
                        state = state.copy(busy = false, showCreateForm = false, newName = "", newKeywordsInclude = "", selectedEndpoints = emptySet())
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
            onToggleEndpoint = { endpoint ->
                val selected = state.selectedEndpoints.toMutableSet()
                if (!selected.add(endpoint.id)) selected.remove(endpoint.id)
                state = state.copy(selectedEndpoints = selected)
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
                        titles[alert.eventId] = app.events.detail(alert.eventId).title
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
