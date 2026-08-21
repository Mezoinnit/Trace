package com.mezo.trace.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorkspaceIdTest {

    @Test
    fun `equal values are equal`() {
        assertEquals(WorkspaceId("ws-1"), WorkspaceId("ws-1"))
    }

    @Test
    fun `different values are different`() {
        assertNotEquals(WorkspaceId("ws-1"), WorkspaceId("ws-2"))
    }
}