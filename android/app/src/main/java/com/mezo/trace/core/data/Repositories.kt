package com.mezo.trace.core.data

import com.mezo.trace.core.network.ApiClient
import com.mezo.trace.core.network.EventDto
import com.mezo.trace.core.network.EventFeedResponse
import com.mezo.trace.core.network.AlertDto
import com.mezo.trace.core.network.SessionResponse
import com.mezo.trace.core.network.SourceDetailDto
import com.mezo.trace.core.network.SourceEndpointDto
import com.mezo.trace.core.network.SourceProposalDto
import com.mezo.trace.core.network.WatchCreateRequest
import com.mezo.trace.core.network.WatchDto

/**
 * Thin repositories over [ApiClient]. ViewModels own state; these own calls.
 */
class AuthRepository(private val api: ApiClient, private val store: SessionStore) {

    val session = store.session

    suspend fun register(email: String, password: String, displayName: String?): Session {
        val response = api.register(email, password, displayName)
        return save(response)
    }

    suspend fun signIn(email: String, password: String): Session {
        val response = api.signIn(email, password)
        return save(response)
    }

    private suspend fun save(response: SessionResponse): Session {
        val session = Session(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            userId = response.user.id,
            email = response.user.email,
            workspaceId = response.workspace.id,
            workspaceName = response.workspace.name,
        )
        store.save(session)
        return session
    }

    /** Revokes the token family server-side, then wipes local state. */
    suspend fun signOut() {
        api.signOut(store.current()?.refreshToken)
        store.clear()
    }
}

class SourcesRepository(private val api: ApiClient) {

    suspend fun resolve(url: String): SourceProposalDto = api.resolveSource(url)

    suspend fun create(proposal: SourceProposalDto): SourceDetailDto =
        api.createSource(proposal.endpointUri, proposal.adapterId, proposal.displayName)

    /** Registers the endpoint from an AI intent proposal (idempotent). */
    suspend fun createSourceFromProposal(
        proposal: com.mezo.trace.core.network.WatchIntentProposalDto,
    ): SourceDetailDto? =
        runCatching {
            api.createSource(proposal.endpointUri!!, proposal.adapterId!!, proposal.suggestedName)
        }.getOrNull()

    suspend fun list(): List<SourceDetailDto> = api.sources()

    /** All endpoints across sources — the picker for watch creation. */
    suspend fun allEndpoints(): List<SourceEndpointDto> =
        list().flatMap { it.endpoints.map { withHealth -> withHealth.endpoint } }
}

class EventsRepository(private val api: ApiClient) {

    suspend fun feed(cursor: String? = null): EventFeedResponse = api.events(cursor)

    suspend fun detail(id: String): com.mezo.trace.core.network.EventDetailResponse = api.event(id)

    suspend fun search(query: String): EventFeedResponse = api.searchEvents(query)

    suspend fun investigate(query: String): com.mezo.trace.core.network.InvestigationResponse =
        api.investigate(query)
}

class WatchesRepository(
    private val api: ApiClient,
    private val sessionStore: SessionStore,
) {

    suspend fun list(): List<WatchDto> {
        val wsId = sessionStore.current()?.workspaceId.orEmpty()
        return if (wsId.isEmpty()) emptyList() else api.watches(wsId)
    }

    suspend fun create(
        name: String,
        endpointIds: List<String>,
        keywordsInclude: List<String> = emptyList(),
        keywordsExclude: List<String> = emptyList(),
        minSignificance: String? = null,
    ): WatchDto {
        val wsId = sessionStore.current()?.workspaceId.orEmpty()
        return api.createWatch(
            WatchCreateRequest(
                workspaceId = wsId,
                name = name,
                endpointIds = endpointIds,
                eventTypes = listOf("SOFTWARE_RELEASE"),
                keywordsInclude = keywordsInclude,
                keywordsExclude = keywordsExclude,
                minSignificance = minSignificance,
            ),
        )
    }

    suspend fun delete(id: String) = api.deleteWatch(id)

    /** NL interpretation → proposal the user confirms (plan §3.2). */
    suspend fun interpret(input: String) = api.interpretWatch(input)
}

class AlertsRepository(
    private val api: ApiClient,
    private val sessionStore: SessionStore,
) {

    /** In-app notifications (plan §49: alerts are visible even when FCM is down). */
    suspend fun list(): List<AlertDto> {
        val wsId = sessionStore.current()?.workspaceId.orEmpty()
        return if (wsId.isEmpty()) emptyList() else api.alerts(wsId)
    }
}
