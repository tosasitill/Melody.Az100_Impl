package com.tosasitill.az100

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaConnectGateTest {

    @Test
    fun startsOnlyFromKnownDisconnectedState() {
        assertTrue(LeaConnectGate.shouldStart(0, null, 1_000L, 10_000L))
        assertFalse(LeaConnectGate.shouldStart(null, null, 1_000L, 10_000L))
        assertFalse(LeaConnectGate.shouldStart(1, null, 1_000L, 10_000L))
        assertFalse(LeaConnectGate.shouldStart(2, null, 1_000L, 10_000L))
        assertFalse(LeaConnectGate.shouldStart(3, null, 1_000L, 10_000L))
    }

    @Test
    fun suppressesRetryUntilCooldownBoundary() {
        assertFalse(LeaConnectGate.shouldStart(0, 1_000L, 10_999L, 10_000L))
        assertTrue(LeaConnectGate.shouldStart(0, 1_000L, 11_000L, 10_000L))
    }

    @Test
    fun rejectsClockRegression() {
        assertFalse(LeaConnectGate.shouldStart(0, 2_000L, 1_999L, 10_000L))
    }
}
