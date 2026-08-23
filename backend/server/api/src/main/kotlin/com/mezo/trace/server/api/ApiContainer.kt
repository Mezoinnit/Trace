package com.mezo.trace.server.api

import com.mezo.trace.adapters.AdapterRegistry
import com.mezo.trace.application.port.AuditLogRepository
import com.mezo.trace.application.port.ClockPort
import com.mezo.trace.application.port.PasswordHasher
import com.mezo.trace.application.port.RefreshTokenRepository
import com.mezo.trace.application.port.SourceEndpointRepository
import com.mezo.trace.application.port.SourceHealthRepository
import com.mezo.trace.application.port.SourceRepository
import com.mezo.trace.application.port.TokenIssuer
import com.mezo.trace.application.port.TxRunner
import com.mezo.trace.application.port.UserRepository
import com.mezo.trace.application.port.WorkspaceMembershipRepository
import com.mezo.trace.application.port.WorkspaceRepository
import com.mezo.trace.application.usecase.auth.AuthSessionIssuer
import com.mezo.trace.application.usecase.auth.DeleteAccount
import com.mezo.trace.application.usecase.auth.RefreshSession
import com.mezo.trace.application.usecase.auth.RegisterUser
import com.mezo.trace.application.usecase.auth.SignIn
import com.mezo.trace.application.usecase.auth.SignOut
import com.mezo.trace.application.usecase.intelligence.DeriveEventsFromEvidence
import com.mezo.trace.application.usecase.intelligence.MarkEventContested
import com.mezo.trace.application.usecase.intelligence.MatchWatchesForEvent
import com.mezo.trace.application.usecase.sources.CreateSource
import com.mezo.trace.application.usecase.sources.FetchEndpointNow
import com.mezo.trace.application.usecase.sources.GetSourceDetail
import com.mezo.trace.application.usecase.sources.ListSources
import com.mezo.trace.application.usecase.sources.ResolveSource
import com.mezo.trace.application.usecase.sources.SetSourceStatus
import com.mezo.trace.application.usecase.tenancy.RequireMembership
import com.mezo.trace.application.usecase.watches.CreateWatch
import com.mezo.trace.application.usecase.watches.DeleteWatch
import com.mezo.trace.application.usecase.watches.ListWatches
import com.mezo.trace.application.usecase.workspace.CreateWorkspace
import com.mezo.trace.application.usecase.workspace.DeleteWorkspace
import com.mezo.trace.application.usecase.workspace.GetWorkspace
import com.mezo.trace.application.usecase.workspace.ListWorkspaceMembers
import com.mezo.trace.application.usecase.workspace.ListWorkspaces
import com.mezo.trace.application.usecase.workspace.RenameWorkspace
import com.mezo.trace.infra.fetch.SafeHttpFetcher
import com.mezo.trace.infra.persistence.JdbcAlertRepository
import com.mezo.trace.infra.persistence.JdbcAuditLogRepository
import com.mezo.trace.infra.persistence.JdbcEntityRepository
import com.mezo.trace.infra.persistence.JdbcEventRepository
import com.mezo.trace.infra.persistence.JdbcEvidenceRepository
import com.mezo.trace.infra.persistence.JdbcRefreshTokenRepository
import com.mezo.trace.infra.persistence.JdbcSourceEndpointRepository
import com.mezo.trace.infra.persistence.JdbcSourceHealthRepository
import com.mezo.trace.infra.persistence.JdbcSourceRepository
import com.mezo.trace.infra.persistence.JdbcUserRepository
import com.mezo.trace.infra.persistence.JdbcWatchRepository
import com.mezo.trace.infra.persistence.JdbcWorkspaceMembershipRepository
import com.mezo.trace.infra.persistence.JdbcWorkspaceRepository
import com.mezo.trace.infra.persistence.SqlConnections
import com.mezo.trace.infra.persistence.TxRunnerImpl
import com.mezo.trace.infra.security.Argon2PasswordHasher
import com.mezo.trace.infra.security.JwtTokenService
import com.mezo.trace.infra.security.SecurityConfig
import java.time.Instant
import javax.sql.DataSource

object SystemClock : ClockPort {
    override fun now(): Instant = Instant.now()
}

/**
 * Composition root. Wires ports to adapters; no framework DI needed at this
 * scale (ARCHITECTURE.md).
 */
class ApiContainer(
    private val dataSource: DataSource,
    security: SecurityConfig,
    /** Test seam: override the outbound transport with a deterministic stub. */
    fetcherOverride: com.mezo.trace.application.port.HttpFetcher? = null,
) {

    val clock: ClockPort = SystemClock
    val sql: SqlConnections = TxRunnerImpl(dataSource)
    val txRunner: TxRunner = sql as TxRunner

    val users: UserRepository = JdbcUserRepository(sql)
    val workspaces: WorkspaceRepository = JdbcWorkspaceRepository(sql)
    val memberships: WorkspaceMembershipRepository = JdbcWorkspaceMembershipRepository(sql)
    val refreshTokens: RefreshTokenRepository = JdbcRefreshTokenRepository(sql)
    val auditLog: AuditLogRepository = JdbcAuditLogRepository(sql)
    val sources: SourceRepository = JdbcSourceRepository(sql)
    val sourceEndpoints: SourceEndpointRepository = JdbcSourceEndpointRepository(sql)
    val sourceHealth: SourceHealthRepository = JdbcSourceHealthRepository(sql)

    val passwordHasher: PasswordHasher = Argon2PasswordHasher()
    val tokenIssuer: TokenIssuer = JwtTokenService(security.jwtSecret, clock, security.accessTokenTtl)

    // Outbound fetching (SSRF-hardened) and the adapter registry.
    val fetcher: com.mezo.trace.application.port.HttpFetcher =
        fetcherOverride ?: SafeHttpFetcher(allowHttp = System.getenv("FETCH_ALLOW_HTTP") == "true")
    val adapterRegistry = AdapterRegistry(fetcher)

    val sessionIssuer = AuthSessionIssuer(refreshTokens, tokenIssuer, clock, security.refreshTokenTtl)
    val requireMembership = RequireMembership(memberships, txRunner)

    val registerUser = RegisterUser(users, workspaces, memberships, passwordHasher, auditLog, txRunner, clock, sessionIssuer)
    val signIn = SignIn(users, passwordHasher, auditLog, txRunner, sessionIssuer)
    val refreshSession = RefreshSession(refreshTokens, users, auditLog, txRunner, tokenIssuer, clock, security.refreshTokenTtl)
    val signOut = SignOut(refreshTokens, auditLog, txRunner, tokenIssuer, clock)
    val deleteAccount = DeleteAccount(users, workspaces, refreshTokens, auditLog, txRunner, clock)

    val createWorkspace = CreateWorkspace(workspaces, memberships, auditLog, txRunner, clock)
    val listWorkspaces = ListWorkspaces(workspaces, txRunner)
    val getWorkspace = GetWorkspace(workspaces, requireMembership, txRunner)
    val renameWorkspace = RenameWorkspace(workspaces, requireMembership, auditLog, txRunner, clock)
    val deleteWorkspace = DeleteWorkspace(workspaces, requireMembership, auditLog, txRunner)
    val listMembers = ListWorkspaceMembers(memberships, requireMembership, txRunner)

    val resolveSource = ResolveSource(fetcher)
    val createSource = CreateSource(sources, sourceEndpoints, sourceHealth, auditLog, adapterRegistry, txRunner, clock)
    val listSources = ListSources(sources, sourceEndpoints, txRunner)
    val getSourceDetail = GetSourceDetail(sources, sourceEndpoints, sourceHealth, txRunner)
    val setSourceStatus = SetSourceStatus(sources, sourceEndpoints, auditLog, txRunner, clock)
    val eventRepo = JdbcEventRepository(sql)
    val watchRepo = JdbcWatchRepository(sql)
    val alertRepo = JdbcAlertRepository(sql)
    val entityRepo = JdbcEntityRepository(sql)

    val ingestEvidence = com.mezo.trace.application.usecase.evidence.IngestEvidence(
        JdbcEvidenceRepository(sql),
        txRunner,
    )
    val eventClusterer = com.mezo.trace.application.usecase.intelligence.EventClusterer(eventRepo, txRunner)
    val resolveEntity = com.mezo.trace.application.usecase.entities.ResolveEntity(entityRepo, txRunner)
    val deriveEvents = DeriveEventsFromEvidence(eventRepo, sources, eventClusterer, resolveEntity, entityRepo, txRunner)
    val markEventContested = MarkEventContested(eventRepo, auditLog, txRunner)
    val matchWatches = MatchWatchesForEvent(watchRepo, alertRepo, auditLog, txRunner)
    val fetchEndpointNow = FetchEndpointNow(
        sourceEndpoints, sourceHealth, auditLog, adapterRegistry,
        ingestEvidence, deriveEvents, matchWatches, txRunner, clock,
    )

    // Watches (Phase 4)
    val createWatch = CreateWatch(watchRepo, sourceEndpoints, requireMembership, txRunner)
    val listWatches = ListWatches(watchRepo, requireMembership, txRunner)
    val deleteWatch = DeleteWatch(watchRepo, requireMembership, txRunner)

    fun databaseReady(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").next()
            }
        }
    }.getOrDefault(false)
}
