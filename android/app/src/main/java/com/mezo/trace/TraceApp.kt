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

    private val api: ApiClient by lazy {
        ApiClient(
            baseUrl = BuildConfig.BASE_URL,
            accessTokenProvider = { runBlocking { sessionStore.current()?.accessToken } },
        )
    }

    val auth: AuthRepository by lazy { AuthRepository(api, sessionStore) }
    val sources: SourcesRepository by lazy { SourcesRepository(api) }
    val events: EventsRepository by lazy { EventsRepository(api) }
    val watches: WatchesRepository by lazy { WatchesRepository(api, sessionStore) }
    val alerts: AlertsRepository by lazy { AlertsRepository(api, sessionStore) }

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
    }
}
