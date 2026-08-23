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
