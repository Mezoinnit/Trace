package com.mezo.trace.application.usecase.intelligence

import com.mezo.trace.application.port.NormalizedItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventClustererTest {

    private fun item(title: String) = NormalizedItem(
        externalId = "x",
        canonicalUrl = null,
        title = title,
        author = null,
        publishedAt = null,
        contentPreview = null,
    )

    private val clusterer = EventClusterer(
        events = object : com.mezo.trace.application.port.EventRepository by NoopEvents {},
        tx = object : com.mezo.trace.application.port.TxRunner {
            override fun <T> run(block: () -> T): T = block()
        },
    )

    @Test
    fun `jaccard ignores case punctuation and stop words`() {
        val score = clusterer.jaccard(
            "Compose 1.9 Released!",
            "compose 1.9 released",
        )
        assertEquals(1.0, score)

        val partial = clusterer.jaccard("Kotlin 2.2 compiler update", "Kotlin compiler update ships")
        assertTrue(partial in 0.4..0.85)
    }

    @Test
    fun `dissimilar titles score low`() {
        val score = clusterer.jaccard("Compose 1.9 Released", "NVIDIA earnings report")
        assertTrue(score < 0.3)
    }

    @Test
    fun `blank titles never match`() {
        assertEquals(0.0, clusterer.jaccard(null, "anything"))
        assertEquals(0.0, clusterer.jaccard("", ""))
    }

    @Test
    fun `threshold constant is documented behavior`() {
        assertNotNull(item("x"))
        assertEquals(0.6, EventClusterer.TITLE_THRESHOLD)
        assertEquals(72L, EventClusterer.WINDOW_HOURS)
    }
}

/** Minimal no-op event repository for pure unit checks. */
private object NoopEvents : com.mezo.trace.application.port.EventRepository {
    override fun findByExternalRef(provider: String, externalId: String): com.mezo.trace.domain.Event? = null
    override fun insert(event: com.mezo.trace.domain.Event): Boolean = true
    override fun linkEvidence(eventId: String, evidenceId: String, role: String) {}
    override fun evidenceForEvent(eventId: String): List<com.mezo.trace.application.port.EventEvidenceView> = emptyList()
    override fun updateStatus(eventId: String, status: com.mezo.trace.domain.EventStatus) {}
    override fun markEvidenceRole(evidenceId: String, eventId: String, role: String) {}
    override fun countDistinctSupportingEndpoints(eventId: String): Int = 1
    override fun updateConfidence(eventId: String, band: String, score: Double, reasoning: Map<String, Double>) {}
    override fun findById(id: String): com.mezo.trace.domain.Event? = null
    override fun feedPage(cursor: String?, limit: Int) =
        com.mezo.trace.application.port.EventFeedPage(emptyList(), null)
    override fun latestDiscovered(limit: Int): List<com.mezo.trace.domain.Event> = emptyList()
    override fun latestDiscoveredBySource(sourceId: String, limit: Int): List<com.mezo.trace.domain.Event> = emptyList()
}
