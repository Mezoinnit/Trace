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

        // ---- Sources (Phase 2: global shared intelligence) ----

        post("/sources/resolve") {
            call.requireUserId(c.tokenIssuer)
            val request = call.receive<SourceResolveRequest>()
            val proposal = c.resolveSource.execute(request.input)
            call.respond(
                HttpStatusCode.OK,
                SourceProposalDto(
                    inputUrl = proposal.inputUrl,
                    provider = proposal.provider.name,
                    sourceType = proposal.sourceType,
                    canonicalIdentity = proposal.canonicalIdentity,
                    endpointUri = proposal.endpointUri,
                    displayUrl = proposal.displayUrl,
                    endpointType = proposal.endpointType,
                    adapterId = proposal.adapterId,
                    capabilities = proposal.capabilities.map { it.name },
                    trust = proposal.trust.name,
                    displayName = proposal.displayName,
                    notes = proposal.notes,
                ),
            )
        }

        post("/sources") {
            val userId = call.requireUserId(c.tokenIssuer)
            val request = call.receive<SourceCreateRequest>()
            val registered = c.createSource.execute(
                userId,
                request.endpointUri.trim(),
                request.adapterId.trim(),
                request.displayName?.trim(),
            )
            call.respond(HttpStatusCode.Created, sourceDetailOf(c, registered.source.id))
        }

        get("/sources") {
            call.requireUserId(c.tokenIssuer)
            val items = c.listSources.execute().map { entry ->
                SourceDetailDto(
                    id = entry.source.id,
                    provider = entry.source.provider.name,
                    sourceType = entry.source.sourceType,
                    canonicalIdentity = entry.source.canonicalIdentity,
                    displayName = entry.source.displayName,
                    trust = entry.source.trust.name,
                    capabilities = entry.source.capabilities.map { it.name },
                    status = entry.source.status.name,
                    endpoints = entry.endpoints.map { endpoint ->
                        SourceEndpointWithHealthDto(endpointDto(endpoint), null)
                    },
                )
            }
            call.respond(HttpStatusCode.OK, ListResponse(items))
        }

        get("/sources/{id}") {
            call.requireUserId(c.tokenIssuer)
            val detail = c.getSourceDetail.execute(call.parameters["id"].orEmpty())
            call.respond(HttpStatusCode.OK, toSourceDetailDto(detail))
        }

        patch("/sources/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val request = call.receive<SourceStatusRequest>()
            val newStatus = runCatching { com.mezo.trace.domain.SourceStatus.valueOf(request.status.uppercase()) }
                .getOrElse { throw com.mezo.trace.domain.ValidationException("status", "must be ACTIVE or DISABLED") }
            c.setSourceStatus.execute(userId, call.parameters["id"].orEmpty(), newStatus)
            call.respond(HttpStatusCode.OK, toSourceDetailDto(c.getSourceDetail.execute(call.parameters["id"].orEmpty())))
        }
    }
}

private fun endpointDto(endpoint: com.mezo.trace.domain.SourceEndpoint) = EndpointDto(
    id = endpoint.id,
    endpointUri = endpoint.endpointUri,
    displayUrl = endpoint.displayUrl,
    endpointType = endpoint.endpointType,
    capabilities = endpoint.capabilities.map { it.name },
    adapter = endpoint.adapter,
    status = endpoint.status.name,
)

private fun healthDto(health: com.mezo.trace.domain.SourceHealth) = SourceHealthDto(
    state = health.state.name,
    lastSuccessAt = health.lastSuccessAt?.toString(),
    lastFailureAt = health.lastFailureAt?.toString(),
    failureCount = health.failureCount,
    consecutiveFailures = health.consecutiveFailures,
    averageLatencyMs = health.averageLatencyMs,
    lastItemAt = health.lastItemAt?.toString(),
)

private fun toSourceDetailDto(detail: com.mezo.trace.application.usecase.sources.GetSourceDetail.Detail) =
    SourceDetailDto(
        id = detail.source.id,
        provider = detail.source.provider.name,
        sourceType = detail.source.sourceType,
        canonicalIdentity = detail.source.canonicalIdentity,
        displayName = detail.source.displayName,
        trust = detail.source.trust.name,
        capabilities = detail.source.capabilities.map { it.name },
        status = detail.source.status.name,
        endpoints = detail.endpoints.map { entry ->
            SourceEndpointWithHealthDto(
                endpoint = endpointDto(entry.endpoint),
                health = entry.health?.let(::healthDto),
            )
        },
    )

private fun sourceDetailOf(c: ApiContainer, sourceId: String): SourceDetailDto =
    toSourceDetailDto(c.getSourceDetail.execute(sourceId))
