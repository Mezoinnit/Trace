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
