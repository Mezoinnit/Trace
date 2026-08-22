package com.mezo.trace.server.api

import com.mezo.trace.application.AppError
import com.mezo.trace.domain.User
import com.mezo.trace.domain.WorkspaceId
import com.mezo.trace.application.usecase.auth.AuthSessionIssuer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.format.DateTimeFormatter
import java.util.UUID

private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

private fun parseWorkspaceId(raw: String?): WorkspaceId {
    if (raw == null) throw AppError.NotFound("workspace")
    runCatching { UUID.fromString(raw) }.getOrElse { throw AppError.NotFound("workspace") }
    return WorkspaceId(raw)
}

private fun userDto(user: User) = UserDto(user.id.value, user.email.value, user.displayName)

/**
 * Resolves the workspace shown in session responses. Runs inside a
 * transaction because repository access requires one (TxRunner contract).
 */
private fun primaryWorkspace(c: ApiContainer, userId: String, preferredId: WorkspaceId?): WorkspaceRefDto =
    c.txRunner.run {
        val workspace = preferredId?.let { c.workspaces.findById(it) }
            ?: c.workspaces.listForUser(com.mezo.trace.domain.UserId(userId)).firstOrNull()?.workspace
            ?: throw AppError.NotFound("workspace")
        WorkspaceRefDto(workspace.id.value, workspace.name)
    }

fun Route.apiV1Routes(c: ApiContainer, rateLimits: RateLimiters) {

    route("/api/v1") {

        // ---- Authentication (rate limited by IP) ----
        post("/auth/register") {
            val request = call.receive<RegisterRequest>()
            val result = c.registerUser.execute(request.email, request.password, request.displayName)
            call.respond(
                HttpStatusCode.Created,
                SessionResponse(
                    user = userDto(result.user),
                    workspace = primaryWorkspace(c, result.user.id.value, result.workspaceId),
                    accessToken = result.session.accessToken.token,
                    accessTokenExpiresAt = ISO_INSTANT.format(result.session.accessToken.expiresAt),
                    refreshToken = result.session.refreshToken,
                ),
            )
        }

        post("/auth/signin") {
            val request = call.receive<SignInRequest>()
            val result = c.signIn.execute(request.email, request.password)
            call.respond(
                HttpStatusCode.OK,
                SessionResponse(
                    user = userDto(result.user),
                    workspace = primaryWorkspace(c, result.user.id.value, null),
                    accessToken = result.session.accessToken.token,
                    accessTokenExpiresAt = ISO_INSTANT.format(result.session.accessToken.expiresAt),
                    refreshToken = result.session.refreshToken,
                ),
            )
        }

        post("/auth/refresh") {
            val request = call.receive<RefreshRequest>()
            val result = c.refreshSession.execute(request.refreshToken)
            call.respond(
                HttpStatusCode.OK,
                SessionResponse(
                    user = userDto(result.user),
                    workspace = primaryWorkspace(c, result.user.id.value, null),
                    accessToken = result.session.accessToken.token,
                    accessTokenExpiresAt = ISO_INSTANT.format(result.session.accessToken.expiresAt),
                    refreshToken = result.session.refreshToken,
                ),
            )
        }

        post("/auth/signout") {
            val request = call.receiveNullable<SignOutRequest>() ?: SignOutRequest(null)
            c.signOut.execute(request.refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Identity (authenticated) ----
        get("/me") {
            val userId = call.requireUserId(c.tokenIssuer)
            val response = c.txRunner.run {
                val user = c.users.findById(userId) ?: throw AppError.Unauthenticated()
                val workspaces = c.workspaces.listForUser(userId)
                MeResponse(
                    user = userDto(user),
                    workspaces = workspaces.map {
                        WorkspaceWithRoleDto(it.workspace.id.value, it.workspace.name, it.role.name)
                    },
                )
            }
            call.respond(HttpStatusCode.OK, response)
        }

        delete("/me") {
            val userId = call.requireUserId(c.tokenIssuer)
            c.deleteAccount.execute(userId)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Workspaces (tenant-owned resources) ----
        get("/workspaces") {
            val userId = call.requireUserId(c.tokenIssuer)
            val items = c.listWorkspaces.execute(userId).map {
                WorkspaceWithRoleDto(it.workspace.id.value, it.workspace.name, it.role.name)
            }
            call.respond(HttpStatusCode.OK, ListResponse(items))
        }

        post("/workspaces") {
            val userId = call.requireUserId(c.tokenIssuer)
            val request = call.receive<WorkspaceCreateRequest>()
            val workspace = c.createWorkspace.execute(userId, request.name)
            call.respond(
                HttpStatusCode.Created,
                WorkspaceDetailDto(
                    workspace.id.value,
                    workspace.name,
                    workspace.createdBy.value,
                    ISO_INSTANT.format(workspace.createdAt),
                    ISO_INSTANT.format(workspace.updatedAt),
                ),
            )
        }

        get("/workspaces/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = parseWorkspaceId(call.parameters["id"])
            val workspace = c.getWorkspace.execute(userId, workspaceId)
            call.respond(
                HttpStatusCode.OK,
                WorkspaceDetailDto(
                    workspace.id.value,
                    workspace.name,
                    workspace.createdBy.value,
                    ISO_INSTANT.format(workspace.createdAt),
                    ISO_INSTANT.format(workspace.updatedAt),
                ),
            )
        }

        patch("/workspaces/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = parseWorkspaceId(call.parameters["id"])
            val request = call.receive<WorkspaceUpdateRequest>()
            c.renameWorkspace.execute(userId, workspaceId, request.name)
            val workspace = c.getWorkspace.execute(userId, workspaceId)
            call.respond(
                HttpStatusCode.OK,
                WorkspaceDetailDto(
                    workspace.id.value,
                    workspace.name,
                    workspace.createdBy.value,
                    ISO_INSTANT.format(workspace.createdAt),
                    ISO_INSTANT.format(workspace.updatedAt),
                ),
            )
        }

        delete("/workspaces/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = parseWorkspaceId(call.parameters["id"])
            c.deleteWorkspace.execute(userId, workspaceId)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/workspaces/{id}/members") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = parseWorkspaceId(call.parameters["id"])
            val members = c.listMembers.execute(userId, workspaceId).map {
                MemberDto(it.member.userId.value, it.displayName, it.member.role.name)
            }
            call.respond(HttpStatusCode.OK, ListResponse(members))
        }
    }
}
