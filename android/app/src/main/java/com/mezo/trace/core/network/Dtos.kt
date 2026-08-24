package com.mezo.trace.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Auth ----

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SignInRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class WorkspaceRefDto(val id: String, val name: String)

@Serializable
data class SessionResponse(
    val user: UserDto,
    val workspace: WorkspaceRefDto,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class MeResponse(val user: UserDto, val workspaces: List<WorkspaceWithRole>)

@Serializable
data class WorkspaceWithRole(val id: String, val name: String, val role: String)

// ---- Sources ----

@Serializable
data class SourceResolveRequest(val input: String)

@Serializable
data class SourceProposalDto(
    @SerialName("input_url") val inputUrl: String,
    val provider: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("canonical_identity") val canonicalIdentity: String,
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("display_url") val displayUrl: String?,
    @SerialName("endpoint_type") val endpointType: String,
    @SerialName("adapter_id") val adapterId: String,
    val capabilities: List<String> = emptyList(),
    val trust: String,
    @SerialName("display_name") val displayName: String? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class SourceCreateRequest(
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("adapter_id") val adapterId: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SourceEndpointDto(
    val id: String,
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("display_url") val displayUrl: String? = null,
    @SerialName("endpoint_type") val endpointType: String,
    val capabilities: List<String> = emptyList(),
    val adapter: String,
    val status: String,
)

@Serializable
data class SourceHealthDto(
    val state: String,
    @SerialName("failure_count") val failureCount: Long = 0,
)

@Serializable
data class SourceDetailDto(
    val id: String,
    val provider: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("canonical_identity") val canonicalIdentity: String,
    @SerialName("display_name") val displayName: String? = null,
    val trust: String,
    val status: String,
    val endpoints: List<SourceEndpointWithHealth> = emptyList(),
)

@Serializable
data class SourceEndpointWithHealth(
    val endpoint: SourceEndpointDto,
    val health: SourceHealthDto? = null,
)

// ---- Events ----

@Serializable
data class EventDto(
    val id: String,
    val type: String,
    val title: String,
    val summary: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("discovered_at") val discoveredAt: String,
    val confidence: String,
    val significance: String,
    val status: String,
)

@Serializable
data class EventFeedResponse(
    val items: List<EventDto>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

// ---- Watches ----

@Serializable
data class WatchCreateRequest(
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("event_types") val eventTypes: List<String> = emptyList(),
    @SerialName("keywords_include") val keywordsInclude: List<String> = emptyList(),
    @SerialName("keywords_exclude") val keywordsExclude: List<String> = emptyList(),
    @SerialName("min_significance") val minSignificance: String? = null,
)

@Serializable
data class WatchDto(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("event_types") val eventTypes: List<String>,
    @SerialName("entity_ids") val entityIds: List<String> = emptyList(),
    @SerialName("keywords_include") val keywordsInclude: List<String> = emptyList(),
    @SerialName("keywords_exclude") val keywordsExclude: List<String> = emptyList(),
    val semantic: String? = null,
    @SerialName("max_age_hours") val maxAgeHours: Int? = null,
    @SerialName("min_significance") val minSignificance: String? = null,
    @SerialName("min_confidence") val minConfidence: String? = null,
    val status: String,
    val version: Int,
    @SerialName("created_at") val createdAt: String,
)

// ---- Alerts ----

@Serializable
data class AlertDto(
    val id: String,
    @SerialName("watch_id") val watchId: String,
    @SerialName("event_id") val eventId: String,
    val significance: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

// ---- AI watch interpretation (Phase 10) ----

@Serializable
data class WatchInterpretRequest(val input: String)

@Serializable
data class WatchIntentProposalDto(
    @SerialName("endpoint_uri") val endpointUri: String?,
    @SerialName("adapter_id") val adapterId: String?,
    @SerialName("suggested_name") val suggestedName: String?,
    @SerialName("keywords_include") val keywordsInclude: List<String>,
    @SerialName("keywords_exclude") val keywordsExclude: List<String>,
    @SerialName("min_significance") val minSignificance: String?,
    val uncertainties: List<String> = emptyList(),
)
