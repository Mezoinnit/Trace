package com.mezo.trace.server.api.itest

import com.mezo.trace.server.api.ApiContainer
import com.mezo.trace.server.api.RateLimiters
import com.mezo.trace.server.api.module
import com.mezo.trace.testsupport.EmbeddedPg
import com.mezo.trace.testsupport.testSecurityConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Installs the API module against the shared embedded database. */
fun ApplicationTestBuilder.app(authLimit: Int = 10_000, apiLimit: Int = 10_000): ApiContainer {
    val container = ApiContainer(EmbeddedPg.dataSource(), testSecurityConfig())
    application { module(container, RateLimiters(authLimit, apiLimit)) }
    return container
}

suspend fun HttpClient.postJson(path: String, body: String): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

suspend fun HttpClient.authedPost(
    path: String,
    accessToken: String,
    body: String? = null,
): HttpResponse =
    post(path) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        contentType(ContentType.Application.Json)
        if (body != null) setBody(body)
    }

suspend fun register(client: HttpClient, email: String, password: String = "password-123"): JsonObject =
    Json.parseToJsonElement(
        client.postJson("/api/v1/auth/register", """{"email":"$email","password":"$password"}""").bodyAsText(),
    ).jsonObject

suspend fun signin(client: HttpClient, email: String, password: String = "password-123"): JsonObject =
    Json.parseToJsonElement(
        client.postJson("/api/v1/auth/signin", """{"email":"$email","password":"$password"}""").bodyAsText(),
    ).jsonObject

fun JsonObject.accessToken(): String = this["access_token"]!!.toString().trim('"')

fun JsonObject.refreshToken(): String = this["refresh_token"]!!.toString().trim('"')

fun JsonObject.workspaceId(): String =
    (this["workspace"] as JsonObject)["id"].toString().trim('"')
