package com.mezo.trace.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SignInRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class SignOutRequest(
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
data class WorkspaceCreateRequest(val name: String)

@Serializable
data class WorkspaceUpdateRequest(val name: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String?,
)

@Serializable
data class WorkspaceRefDto(
    val id: String,
    val name: String,
)

@Serializable
data class SessionResponse(
    val user: UserDto,
    val workspace: WorkspaceRefDto,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class WorkspaceWithRoleDto(
    val id: String,
    val name: String,
    val role: String,
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val workspaces: List<WorkspaceWithRoleDto>,
)

@Serializable
data class WorkspaceDetailDto(
    val id: String,
    val name: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MemberDto(
    val user_id: String,
    val display_name: String?,
    val role: String,
)

@Serializable
data class ListResponse<T>(val items: List<T>)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val database: String,
)

// ---- Sources (Phase 2) ----

@Serializable
data class SourceResolveRequest(val input: String)

@Serializable
data class SourceProposalDto(
    @SerialName("input_url") val inputUrl: String,
    val provider: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("canonical_identity") val canonicalIdentity: String,
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("display_url") val displayUrl: String,
    @SerialName("endpoint_type") val endpointType: String,
    @SerialName("adapter_id") val adapterId: String,
    val capabilities: List<String>,
    val trust: String,
    @SerialName("display_name") val displayName: String?,
    val notes: List<String>,
)

@Serializable
data class SourceCreateRequest(
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("adapter_id") val adapterId: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SourceStatusRequest(val status: String)

@Serializable
data class EndpointDto(
    val id: String,
    @SerialName("endpoint_uri") val endpointUri: String,
    @SerialName("display_url") val displayUrl: String?,
    @SerialName("endpoint_type") val endpointType: String,
    val capabilities: List<String>,
    val adapter: String,
    val status: String,
)

@Serializable
data class SourceDto(
    val id: String,
    val provider: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("canonical_identity") val canonicalIdentity: String,
    @SerialName("display_name") val displayName: String?,
    val trust: String,
    val capabilities: List<String>,
    val status: String,
)

@Serializable
data class SourceHealthDto(
    val state: String,
    @SerialName("last_success_at") val lastSuccessAt: String?,
    @SerialName("last_failure_at") val lastFailureAt: String?,
    @SerialName("failure_count") val failureCount: Long,
    @SerialName("consecutive_failures") val consecutiveFailures: Int,
    @SerialName("average_latency_ms") val averageLatencyMs: Int?,
    @SerialName("last_item_at") val lastItemAt: String?,
)

@Serializable
data class SourceDetailDto(
    val id: String,
    val provider: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("canonical_identity") val canonicalIdentity: String,
    @SerialName("display_name") val displayName: String?,
    val trust: String,
    val capabilities: List<String>,
    val status: String,
    val endpoints: List<SourceEndpointWithHealthDto>,
)

@Serializable
data class SourceEndpointWithHealthDto(
    val endpoint: EndpointDto,
    val health: SourceHealthDto?,
)

// ---- Events / Watches / Alerts (Phase 4) ----

@Serializable
data class EventDto(
    val id: String,
    val type: String,
    val title: String,
    val summary: String?,
    @SerialName("occurred_at") val occurredAt: String?,
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

@Serializable
data class WatchCreateRequest(
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("event_types") val eventTypes: List<String> = emptyList(),
    @SerialName("entity_ids") val entityIds: List<String> = emptyList(),
    @SerialName("keywords_include") val keywordsInclude: List<String> = emptyList(),
    @SerialName("keywords_exclude") val keywordsExclude: List<String> = emptyList(),
    val semantic: String? = null,
    @SerialName("max_age_hours") val maxAgeHours: Int? = null,
    @SerialName("min_significance") val minSignificance: String? = null,
    @SerialName("min_confidence") val minConfidence: String? = null,
)

@Serializable
data class WatchDto(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("event_types") val eventTypes: List<String>,
    @SerialName("entity_ids") val entityIds: List<String>,
    @SerialName("keywords_include") val keywordsInclude: List<String>,
    @SerialName("keywords_exclude") val keywordsExclude: List<String>,
    val semantic: String?,
    @SerialName("max_age_hours") val maxAgeHours: Int?,
    @SerialName("min_significance") val minSignificance: String?,
    @SerialName("min_confidence") val minConfidence: String?,
    val status: String,
    /** Optimistic-lock token; echo it in PATCH (plan §24). */
    val version: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AlertDto(
    val id: String,
    @SerialName("watch_id") val watchId: String,
    @SerialName("event_id") val eventId: String,
    val significance: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

// ---- Entities (Phase 6) ----

@Serializable
data class EntityDto(
    val id: String,
    val type: String,
    @SerialName("canonical_name") val canonicalName: String,
    val description: String?,
)

@Serializable
data class EntityDetailDto(
    val entity: EntityDto,
    @SerialName("recent_events") val recentEvents: List<EventDto>,
)

@Serializable
data class EntityMergeRequest(
    @SerialName("target_id") val targetId: String,
)

@Serializable
data class WatchUpdateRequest(
    @SerialName("expected_version") val expectedVersion: Int,
    val name: String? = null,
    val status: String? = null,
    @SerialName("endpoint_ids") val endpointIds: List<String>? = null,
    @SerialName("event_types") val eventTypes: List<String>? = null,
    @SerialName("entity_ids") val entityIds: List<String>? = null,
    @SerialName("keywords_include") val keywordsInclude: List<String>? = null,
    @SerialName("keywords_exclude") val keywordsExclude: List<String>? = null,
    val semantic: String? = null,
    @SerialName("max_age_hours") val maxAgeHours: Int? = null,
    @SerialName("min_significance") val minSignificance: String? = null,
    @SerialName("min_confidence") val minConfidence: String? = null,
)
