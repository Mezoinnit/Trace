package com.mezo.trace.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Error surface for the UI layer; message is human-presentable. */
class ApiException(val code: String, message: String) : Exception(message)

/**
 * Trace API client (API.md contract). Bearer token injected per request from
 * the session provider so rotation is always fresh.
 *
 * Requests are written explicitly per endpoint — verbose, but free of
 * generic-helper pitfalls and trivially greppable against API.md.
 */
class ApiClient(
    baseUrl: String,
    accessTokenProvider: () -> String?,
    /** Test seam: inject a mock/fake engine instead of OkHttp. */
    private val engine: io.ktor.client.engine.HttpClientEngine? = null,
    /**
     * Refresh hook (plan §30: sessions rotate server-side). Called on 401
     * UNAUTHENTICATED with a stale access token; on success the store has
     * been updated and the failed request is retried once.
     */
    private val reauth: (suspend () -> Boolean)? = null,
) {

    private val base = baseUrl.trimEnd('/')
    private val tokenProvider = accessTokenProvider

    /** Single-flight guard so concurrent 401s trigger at most one refresh. */
    private var refreshJob: kotlinx.coroutines.Deferred<Boolean>? = null

    private fun clientConfig(): HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    private val http: HttpClient = engine
        ?.let { HttpClient(it, clientConfig()) }
        ?: HttpClient(OkHttp, clientConfig())

    // ---- Auth ----

    suspend fun register(email: String, password: String, displayName: String?): SessionResponse =
        failible {
            http.post("$base/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(email, password, displayName))
            }.body()
        }

    suspend fun signIn(email: String, password: String): SessionResponse =
        failible {
            http.post("$base/api/v1/auth/signin") {
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(email, password))
            }.body()
        }

    suspend fun refresh(refreshToken: String): SessionResponse =
        failible {
            http.post("$base/api/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }.body()
        }

    /** Server-side revocation of the refresh-token family, then local wipe. */
    suspend fun signOut(refreshToken: String?): Unit =
        try {
            failible {
                http.post("$base/api/v1/auth/signout") {
                    contentType(ContentType.Application.Json)
                    setBody(SignOutRequest(refreshToken))
                }.body<Unit>()
            }
        } catch (_: Exception) {
            // Best-effort: local sign-out must always succeed (plan §30).
        }

    suspend fun me(): MeResponse = authedGet("/api/v1/me")

    suspend fun deleteMe(): Unit = authedDelete("/api/v1/me")

    // ---- Sources ----

    suspend fun resolveSource(input: String): SourceProposalDto =
        authedPost("/api/v1/sources/resolve", SourceResolveRequest(input))

    suspend fun createSource(endpointUri: String, adapterId: String, displayName: String?): SourceDetailDto =
        authedPost("/api/v1/sources", SourceCreateRequest(endpointUri, adapterId, displayName))

    suspend fun sources(): List<SourceDetailDto> =
        authedGet<ListResponse<SourceDetailDto>>("/api/v1/sources").items

    // ---- Events ----

    suspend fun events(cursor: String?, limit: Int = 50): EventFeedResponse =
        authedGet(
            buildString {
                append("/api/v1/events?limit=").append(limit)
                if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
            },
        )

    suspend fun event(id: String): EventDetailResponse = authedGet("/api/v1/events/$id")

    suspend fun searchEvents(query: String): EventFeedResponse =
        authedGet("/api/v1/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))

    suspend fun investigate(query: String): InvestigationResponse =
        authedPost("/api/v1/investigations", InvestigationRequest(query))

    suspend fun entity(id: String): EntityDetailResponse = authedGet("/api/v1/entities/$id")

    suspend fun eventHistory(id: String): List<EventHistoryDto> =
        authedGet("/api/v1/events/$id/history")

    suspend fun relatedEvents(id: String): List<EventDto> =
        authedGet<EventFeedResponse>("/api/v1/events/$id/related").items

    suspend fun evidence(id: String): EvidenceDetailDto = authedGet("/api/v1/evidence/$id")

    suspend fun timeline(entityId: String): TimelineResponse =
        authedGet("/api/v1/entities/$entityId/timeline")

    // ---- Watches ----

    suspend fun watches(workspaceId: String): List<WatchDto> =
        authedGet<ListResponse<WatchDto>>("/api/v1/watches?workspace_id=$workspaceId").items

    suspend fun createWatch(request: WatchCreateRequest): WatchDto =
        authedPost("/api/v1/watches", request)

    suspend fun deleteWatch(id: String): Unit = authedDelete("/api/v1/watches/$id")

    suspend fun interpretWatch(input: String): WatchIntentProposalDto =
        authedPost("/api/v1/watches/interpret", WatchInterpretRequest(input))

    // ---- Alerts ----

    suspend fun alerts(workspaceId: String): List<AlertDto> =
        authedGet<ListResponse<AlertDto>>("/api/v1/alerts?workspace_id=$workspaceId").items

    // ---- plumbing ----

    private fun bearer(): String =
        "Bearer " + (tokenProvider() ?: throw ApiException("UNAUTHENTICATED", "sign in first"))

    /**
     * On 401 UNAUTHENTICATED: attempt one token refresh (single-flight) and
     * retry the request exactly once with the fresh token. Any refresh
     * failure rethrows the original error so callers surface sign-in.
     * [failible] has already converted the 401 into an [ApiException] by the
     * time it reaches here, so the code string is the 401 signal.
     */
    private suspend fun <T> withReauth(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ApiException) {
            if (e.code != "UNAUTHENTICATED") throw e
            if (reauth == null || !refreshTokens()) throw e
            // Retry once with the token the refresh left in the store.
            block()
        }

    /** Single-flight refresh; returns true only when new tokens were stored. */
    private suspend fun refreshTokens(): Boolean {
        val hook = reauth ?: return false
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val winner: kotlinx.coroutines.Deferred<Boolean>
        synchronized(this) {
            val current = refreshJob
            winner = if (current?.isActive == true) current else deferred
            refreshJob = winner
        }
        if (winner !== deferred) {
            // Another coroutine owns the refresh; adopt its outcome.
            return try {
                winner.await()
            } catch (_: Exception) {
                false
            }
        }
        try {
            val ok = hook()
            deferred.complete(ok)
            return ok
        } catch (_: Exception) {
            // Refresh failed (revoked/expired/offline): everyone returns
            // false and the original 401 surfaces instead.
            deferred.complete(false)
            return false
        } finally {
            synchronized(this) { if (refreshJob === deferred) refreshJob = null }
        }
    }

    private suspend fun <T> failible(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ClientRequestException) {
            val text = runCatching { e.response.body<String>() }.getOrDefault("")
            val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(text) }.getOrNull()
            throw ApiException(
                envelope?.error?.code ?: "UNKNOWN",
                envelope?.error?.message ?: text.take(200).ifEmpty { "request failed" },
            )
        }

    private suspend inline fun <reified T> authedGet(path: String): T =
        withReauth {
            failible {
                http.get(base + path) {
                    header(HttpHeaders.Authorization, bearer())
                }.body<T>()
            }
        }

    private suspend inline fun <reified T> authedPost(path: String, body: Any?): T =
        withReauth {
            failible {
                http.post(base + path) {
                    header(HttpHeaders.Authorization, bearer())
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.body<T>()
            }
        }

    private suspend fun authedDelete(path: String) {
        withReauth {
            failible {
                http.delete(base + path) {
                    header(HttpHeaders.Authorization, bearer())
                }.body<Unit>()
            }
        }
    }
}

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody)

@Serializable
internal data class ErrorBody(val code: String? = null, val message: String? = null)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }


@Serializable
internal data class ListResponse<T>(val items: List<T>)
