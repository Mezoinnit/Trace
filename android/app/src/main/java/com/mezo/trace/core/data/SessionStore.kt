package com.mezo.trace.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "trace_session")

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val workspaceId: String,
    val workspaceName: String,
)

/**
 * Session persistence (plan §30: backend is the source of truth; only the
 * session is cached client-side). DataStore keeps tokens out of code; a
 * hardened keystore-backed store replaces this in Phase 14.
 */
class SessionStore(private val context: Context) {

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val userKey = stringPreferencesKey("user_id")
    private val emailKey = stringPreferencesKey("email")
    private val wsIdKey = stringPreferencesKey("workspace_id")
    private val wsNameKey = stringPreferencesKey("workspace_name")

    val session: Flow<Session?> = context.dataStore.data.map { prefs ->
        val access = prefs[accessKey] ?: return@map null
        Session(
            accessToken = access,
            refreshToken = prefs[refreshKey] ?: "",
            userId = prefs[userKey] ?: "",
            email = prefs[emailKey] ?: "",
            workspaceId = prefs[wsIdKey] ?: "",
            workspaceName = prefs[wsNameKey] ?: "",
        )
    }

    suspend fun current(): Session? = session.first()

    suspend fun save(session: Session) {
        context.dataStore.edit { prefs ->
            prefs[accessKey] = session.accessToken
            prefs[refreshKey] = session.refreshToken
            prefs[userKey] = session.userId
            prefs[emailKey] = session.email
            prefs[wsIdKey] = session.workspaceId
            prefs[wsNameKey] = session.workspaceName
        }
    }

    suspend fun updateTokens(access: String, refresh: String) {
        context.dataStore.edit { prefs ->
            prefs[accessKey] = access
            prefs[refreshKey] = refresh
        }
    }

    /**
     * Rotates the session using the stored refresh token (rotation means the
     * old refresh token is consumed server-side; the new pair is persisted).
     * Returns null when there is nothing to refresh or the server rejected
     * the token (revoked family / expired) — callers then clear the session.
     */
    suspend fun refresh(api: com.mezo.trace.core.network.ApiClient): Session? {
        val token = current()?.refreshToken ?: return null
        if (token.isBlank()) return null
        return try {
            val response = api.refresh(token)
            val refreshed = Session(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                userId = response.user.id,
                email = response.user.email,
                workspaceId = response.workspace.id,
                workspaceName = response.workspace.name,
            )
            save(refreshed)
            refreshed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
