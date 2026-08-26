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

        // ---- Events (global intelligence feed, Phase 4) ----

        get("/events") {
            call.requireUserId(c.tokenIssuer)
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
            val cursor = call.request.queryParameters["cursor"]
            val page = c.txRunner.run { c.eventRepo.feedPage(cursor, limit) }
            call.respond(
                HttpStatusCode.OK,
                EventFeedResponse(
                    items = page.events.map(::eventDto),
                    nextCursor = page.nextCursor,
                ),
            )
        }

        get("/events/{id}") {
            call.requireUserId(c.tokenIssuer)
            val detail = c.txRunner.run {
                val event = c.eventRepo.findById(call.parameters["id"].orEmpty())
                    ?: throw AppError.NotFound("event")
                val evidence = c.eventRepo.evidenceForEvent(event.id)
                val entities = c.entityRepo.listEventEntities(event.id)
                Triple(event, evidence, entities)
            }
            call.respond(
                HttpStatusCode.OK,
                EventDetailResponse(
                    event = eventDto(detail.first),
                    evidence = detail.second.map { view ->
                        EventEvidenceDto(
                            id = view.evidenceId,
                            title = view.title,
                            canonicalUrl = view.canonicalUrl,
                            role = view.role,
                            sourceName = view.sourceName,
                            sourceTrust = view.sourceTrust,
                            publishedAt = view.publishedAt?.toString(),
                        )
                    },
                    entities = detail.third.map { entity ->
                        EntityChipDto(entity.id, entity.canonicalName, entity.type.name)
                    },
                ),
            )
        }

        // ---- Search & investigations (Phase 11) ----

        get("/search") {
            call.requireUserId(c.tokenIssuer)
            val q = call.request.queryParameters["q"].orEmpty().take(200)
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
            val type = call.request.queryParameters["type"]
            val sourceId = call.request.queryParameters["source_id"]
            val entityId = call.request.queryParameters["entity_id"]

            fun parseInstant(raw: String?): java.time.Instant? =
                raw?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }

            val result = c.txRunner.run {
                val matchedEvents = c.eventRepo.searchEvents(
                    query = q,
                    filters = com.mezo.trace.application.port.EventSearchFilters(
                        type = type,
                        sourceId = sourceId,
                        entityId = entityId,
                        from = parseInstant(call.request.queryParameters["from"]),
                        to = parseInstant(call.request.queryParameters["to"]),
                    ),
                    limit = limit,
                )
                val matchedEntities = if (q.isNotBlank()) c.entityRepo.search(q, limit = 10) else emptyList()
                matchedEvents to matchedEntities
            }
            call.respond(
                HttpStatusCode.OK,
                EventFeedResponse(
                    items = result.first.map(::eventDto),
                    nextCursor = null,
                ),
            )
        }

        // ---- Historical intelligence (Phase 12) ----

        get("/events/{id}/related") {
            call.requireUserId(c.tokenIssuer)
            val related = c.txRunner.run {
                c.eventRepo.relatedEvents(call.parameters["id"].orEmpty(), limit = 20)
            }
            call.respond(HttpStatusCode.OK, EventFeedResponse(related.map(::eventDto), nextCursor = null))
        }

        get("/events/{id}/history") {
            call.requireUserId(c.tokenIssuer)
            val eventId = call.parameters["id"].orEmpty()
            // Prove the event exists before returning its trail.
            c.txRunner.run {
                c.eventRepo.findById(eventId) ?: throw AppError.NotFound("event")
            }
            val history = c.txRunner.run {
                c.auditRepo.listForResource("event", eventId)
            }.map {
                EventHistoryDto(at = it.at.toString(), action = it.action, detail = it.detail)
            }
            call.respond(HttpStatusCode.OK, ListResponse(history))
        }

        get("/evidence/{id}") {
            call.requireUserId(c.tokenIssuer)
            val result = c.txRunner.run {
                val evidence = c.evidenceRepo.findById(call.parameters["id"].orEmpty())
                    ?: throw AppError.NotFound("evidence")
                evidence to c.evidenceRepo.versionsFor(evidence.id)
            }
            call.respond(
                HttpStatusCode.OK,
                EvidenceDetailDto(
                    id = result.first.id,
                    endpointId = result.first.endpointId,
                    externalId = result.first.externalId,
                    canonicalUrl = result.first.canonicalUrl,
                    title = result.first.title,
                    publishedAt = result.first.publishedAt?.toString(),
                    discoveredAt = result.first.discoveredAt.toString(),
                    content = result.first.content,
                    contentHash = result.first.contentHash,
                    versions = result.second.map { v ->
                        EvidenceVersionDto(
                            version = v.version,
                            content = v.content,
                            contentHash = v.contentHash,
                            changedBy = v.changedBy,
                            createdAt = v.createdAt.toString(),
                        )
                    },
                ),
            )
        }

        get("/entities/{id}/timeline") {
            call.requireUserId(c.tokenIssuer)
            val entityId = call.parameters["id"].orEmpty()
            fun parseInstant(raw: String?): java.time.Instant? =
                raw?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val from = parseInstant(call.request.queryParameters["from"])
            val to = parseInstant(call.request.queryParameters["to"])
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 365)
            val events = c.txRunner.run {
                // Timeline = entity-scoped window query; identity does the scoping.
                c.eventRepo.searchEvents(
                    query = "",
                    filters = com.mezo.trace.application.port.EventSearchFilters(
                        entityId = entityId,
                        from = from,
                        to = to,
                    ),
                    limit = limit,
                )
            }
            call.respond(HttpStatusCode.OK, TimelineResponse(entityId, events.map(::eventDto)))
        }

        post("/investigations") {
            call.requireUserId(c.tokenIssuer)
            val request = call.receive<InvestigationRequest>()
            val result = c.investigate.execute(request.query.take(500))
            call.respond(
                HttpStatusCode.OK,
                InvestigationResponse(
                    interpretation = result.interpretationText,
                    entityHint = result.entityHint,
                    lastDays = result.lastDays,
                    events = result.events.map(::eventDto),
                    entities = result.matchedEntities.map {
                        EntityChipDto(it.id, it.canonicalName, it.type.name)
                    },
                ),
            )
        }

        // ---- Watches (tenant-owned monitoring rules, Phase 4) ----

        // NL interpretation → structured proposal the user confirms (§3.2).
        post("/watches/interpret") {
            call.requireUserId(c.tokenIssuer)
            val request = call.receive<WatchInterpretRequest>()
            val proposal = c.aiGateway.parseWatchIntent(request.input)
            call.respond(
                HttpStatusCode.OK,
                WatchIntentProposalDto(
                    endpointUri = proposal.endpointUri,
                    adapterId = proposal.adapterId,
                    suggestedName = proposal.suggestedName,
                    keywordsInclude = proposal.keywordsInclude,
                    keywordsExclude = proposal.keywordsExclude,
                    minSignificance = proposal.minSignificance,
                    uncertainties = proposal.uncertainties,
                ),
            )
        }

        post("/watches") {
            val userId = call.requireUserId(c.tokenIssuer)
            val request = call.receive<WatchCreateRequest>()
            val watch = c.createWatch.execute(
                userId,
                request.workspaceId.trim(),
                request.name,
                request.endpointIds.toSet(),
                request.eventTypes.toSet(),
                com.mezo.trace.application.usecase.watches.CreateWatch.Rules(
                    entityIds = request.entityIds.toSet(),
                    keywordsInclude = request.keywordsInclude,
                    keywordsExclude = request.keywordsExclude,
                    semantic = request.semantic,
                    maxAgeHours = request.maxAgeHours,
                    minSignificance = request.minSignificance,
                    minConfidence = request.minConfidence,
                    notificationMode = request.notificationMode ?: "IMMEDIATE",
                ),
            )
            call.respond(HttpStatusCode.Created, watchDto(watch))
        }

        get("/watches") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = call.request.queryParameters["workspace_id"]?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw com.mezo.trace.domain.ValidationException("workspace_id", "required")
            val items = c.listWatches.execute(userId, workspaceId).map(::watchDto)
            call.respond(HttpStatusCode.OK, ListResponse(items))
        }

        get("/watches/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val watch = c.txRunner.run {
                val w = c.watchRepo.findById(call.parameters["id"].orEmpty())
                    ?: throw AppError.NotFound("watch")
                c.requireMembership.execute(userId, com.mezo.trace.domain.WorkspaceId(w.workspaceId))
                w
            }
            call.respond(HttpStatusCode.OK, watchDto(watch))
        }

        patch("/watches/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            val body = call.receive<WatchUpdateRequest>()
            val updated = c.updateWatch.execute(
                userId,
                call.parameters["id"].orEmpty(),
                com.mezo.trace.application.usecase.watches.UpdateWatch.Patch(
                    expectedVersion = body.expectedVersion,
                    name = body.name,
                    status = body.status,
                    endpointIds = body.endpointIds?.toSet(),
                    eventTypes = body.eventTypes?.toSet(),
                    notificationMode = body.notificationMode,
                    rules = body.let {
                        if (it.entityIds != null || it.keywordsInclude != null ||
                            it.keywordsExclude != null || it.semantic != null ||
                            it.maxAgeHours != null || it.minSignificance != null ||
                            it.minConfidence != null
                        ) {
                            com.mezo.trace.application.usecase.watches.CreateWatch.Rules(
                                entityIds = it.entityIds?.toSet() ?: emptySet(),
                                keywordsInclude = it.keywordsInclude ?: emptyList(),
                                keywordsExclude = it.keywordsExclude ?: emptyList(),
                                semantic = it.semantic,
                                maxAgeHours = it.maxAgeHours,
                                minSignificance = it.minSignificance,
                                minConfidence = it.minConfidence,
                                notificationMode = it.notificationMode ?: "IMMEDIATE",
                            )
                        } else {
                            null
                        }
                    },
                ),
            )
            call.respond(HttpStatusCode.OK, watchDto(updated))
        }

        delete("/watches/{id}") {
            val userId = call.requireUserId(c.tokenIssuer)
            c.deleteWatch.execute(userId, call.parameters["id"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }

        post("/entities/{id}/merge") {
            val userId = call.requireUserId(c.tokenIssuer)
            val body = call.receive<EntityMergeRequest>()
            c.mergeEntities.execute(
                userId.value,
                call.parameters["id"].orEmpty(),
                body.targetId.trim(),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        get("/entities/{id}") {
            call.requireUserId(c.tokenIssuer)
            val detail = c.txRunner.run {
                val entity = c.entityRepo.findById(call.parameters["id"].orEmpty())
                    ?: throw AppError.NotFound("entity")
                entity to c.entityRepo.eventsForEntity(entity.id, limit = 50)
            }
            call.respond(
                HttpStatusCode.OK,
                EntityDetailDto(
                    entity = EntityDto(
                        id = detail.first.id,
                        type = detail.first.type.name,
                        canonicalName = detail.first.canonicalName,
                        description = detail.first.description,
                    ),
                    recentEvents = detail.second.map(::eventDto),
                ),
            )
        }

        // ---- Alerts (per-workspace, Phase 4) ----

        get("/alerts") {
            val userId = call.requireUserId(c.tokenIssuer)
            val workspaceId = call.request.queryParameters["workspace_id"]?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw com.mezo.trace.domain.ValidationException("workspace_id", "required")
            val alerts = c.txRunner.run {
                c.requireMembership.execute(userId, com.mezo.trace.domain.WorkspaceId(workspaceId))
                c.alertRepo.listForWorkspace(workspaceId, limit = 100)
            }.map { alert ->
                AlertDto(
                    id = alert.id,
                    watchId = alert.watchId,
                    eventId = alert.eventId,
                    significance = alert.significance,
                    status = alert.status.name,
                    createdAt = alert.createdAt.toString(),
                )
            }
            call.respond(HttpStatusCode.OK, ListResponse(alerts))
        }
    }
}

private fun eventDto(event: com.mezo.trace.domain.Event) = EventDto(
    id = event.id,
    type = event.type,
    title = event.title,
    summary = event.summary,
    occurredAt = event.occurredAt?.toString(),
    discoveredAt = event.discoveredAt.toString(),
    confidence = event.confidence,
    significance = event.significance,
    status = event.status.name,
)

private fun watchDto(watch: com.mezo.trace.domain.Watch) = WatchDto(
    id = watch.id,
    workspaceId = watch.workspaceId,
    name = watch.name,
    endpointIds = watch.endpointIds.toList(),
    eventTypes = watch.eventTypes.toList(),
    entityIds = watch.entityIds.toList(),
    keywordsInclude = watch.keywordsInclude,
    keywordsExclude = watch.keywordsExclude,
    semantic = watch.semantic,
    maxAgeHours = watch.maxAgeHours,
    minSignificance = watch.minSignificance,
    minConfidence = watch.minConfidence,
    notificationMode = watch.notificationMode.name,
    status = watch.status.name,
    version = watch.version,
    createdAt = watch.createdAt.toString(),
)

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
