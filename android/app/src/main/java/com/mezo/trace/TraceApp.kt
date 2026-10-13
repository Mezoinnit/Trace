package com.mezo.trace

import android.app.Application
import com.mezo.trace.core.data.AlertsRepository
import com.mezo.trace.core.data.AuthRepository
import com.mezo.trace.core.data.EventsRepository
import com.mezo.trace.core.data.SessionStore
import com.mezo.trace.core.data.SourcesRepository
import com.mezo.trace.core.data.WatchesRepository
import com.mezo.trace.core.network.ApiClient
import kotlinx.coroutines.runBlocking

/**
 * Manual composition root (mirrors the backend's ApiContainer pattern).
 * Hilt adoption is deferred until the module count justifies it (ADR-016).
 */
class TraceApp : Application() {

    lateinit var sessionStore: SessionStore
        private set

    val api: ApiClient by lazy {
        ApiClient(
            baseUrl = BuildConfig.BASE_URL,
            accessTokenProvider = { runBlocking { sessionStore.current()?.accessToken } },
            // On 401, rotate the session once and retry (single-flight inside
            // the client). Referencing `api` here is safe: the hook only runs
            // after construction, and refresh failures return false → the
            // original 401 propagates and the UI returns to sign-in.
            reauth = { sessionStore.refresh(api) != null },
        )
    }

    val auth: AuthRepository by lazy { AuthRepository(api, sessionStore) }
    val sources: SourcesRepository by lazy { SourcesRepository(api) }
    val events: EventsRepository by lazy { EventsRepository(api) }
    val watches: WatchesRepository by lazy { WatchesRepository(api, sessionStore) }
    val alerts: AlertsRepository by lazy { AlertsRepository(api, sessionStore) }

    /** Entity timeline fetch (plan §12 surfaces). */
    suspend fun entityRepoFetch(id: String): com.mezo.trace.core.network.EntityDetailResponse =
        api.entity(id)

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
    }
}
