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
) {

    private val base = baseUrl.trimEnd('/')
    private val tokenProvider = accessTokenProvider

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

    suspend fun me(): MeResponse = authedGet("/api/v1/me")

    suspend fun deleteMe(): Unit = authedDelete("/api/v1/me")

    // ---- Sources ----

    suspend fun resolveSource(input: String): SourceProposalDto =
        authedPost("/api/v1/sources/resolve", SourceResolveRequest(input))

    suspend fun createSource(endpointUri: String, adapterId: String, displayName: String?): SourceDetailDto =
        authedPost("/api/v1/sources", SourceCreateRequest(endpointUri, adapterId, displayName))

    suspend fun sources(): List<SourceDetailDto> =
        authedGet("/api/v1/sources")

    // ---- Events ----

    suspend fun events(cursor: String?, limit: Int = 50): EventFeedResponse =
        authedGet(
            buildString {
                append("/api/v1/events?limit=").append(limit)
                if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
            },
        )

    suspend fun event(id: String): EventDto = authedGet("/api/v1/events/$id")

    // ---- Watches ----

    suspend fun watches(workspaceId: String): List<WatchDto> =
        authedGet("/api/v1/watches?workspace_id=$workspaceId")

    suspend fun createWatch(request: WatchCreateRequest): WatchDto =
        authedPost("/api/v1/watches", request)

    suspend fun deleteWatch(id: String): Unit = authedDelete("/api/v1/watches/$id")

    suspend fun interpretWatch(input: String): WatchIntentProposalDto =
        authedPost("/api/v1/watches/interpret", WatchInterpretRequest(input))

    // ---- Alerts ----

    suspend fun alerts(workspaceId: String): List<AlertDto> =
        authedGet("/api/v1/alerts?workspace_id=$workspaceId")

    // ---- plumbing ----

    private fun bearer(): String =
        "Bearer " + (tokenProvider() ?: throw ApiException("UNAUTHENTICATED", "sign in first"))

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
        failible {
            http.get(base + path) {
                header(HttpHeaders.Authorization, bearer())
            }.body<T>()
        }

    private suspend inline fun <reified T> authedPost(path: String, body: Any?): T =
        failible {
            http.post(base + path) {
                header(HttpHeaders.Authorization, bearer())
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body<T>()
        }

    private suspend fun authedDelete(path: String) {
        failible {
            http.delete(base + path) {
                header(HttpHeaders.Authorization, bearer())
            }.body<Unit>()
        }
    }
}

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody)

@Serializable
internal data class ErrorBody(val code: String? = null, val message: String? = null)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
