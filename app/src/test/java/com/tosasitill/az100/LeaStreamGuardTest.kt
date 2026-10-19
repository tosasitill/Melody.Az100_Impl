package com.tosasitill.az100

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaStreamGuardTest {

    private fun guard() = LeaStreamGuard(60_000L, 300_000L)

    private fun holdMs(action: LeaStreamGuard.Action): Long {
        assertTrue("expected a hold but was " + action, action is LeaStreamGuard.Action.Hold)
        return (action as LeaStreamGuard.Action.Hold).durationMs
    }

    @Test
    fun streamingGroupNeedsNoAction() {
        val guard = guard()
        assertTrue(guard.onProbe(1, true, true) is LeaStreamGuard.Action.None)
        assertTrue(guard.onProbe(1, true, true) is LeaStreamGuard.Action.None)
    }

    @Test
    fun idleGroupWithoutAudioIsInconclusive() {
        val guard = guard()
        assertTrue(guard.onProbe(1, false, false) is LeaStreamGuard.Action.None)
        assertTrue(guard.onProbe(1, false, false) is LeaStreamGuard.Action.None)
        assertTrue(guard.onProbe(1, false, false) is LeaStreamGuard.Action.None)
    }

    @Test
    fun twoFailedObservationsWhileAudioPlaysTripTheGuard() {
        val guard = guard()
        assertTrue(guard.onProbe(1, false, true) is LeaStreamGuard.Action.Confirm)
        assertEquals(60_000L, holdMs(guard.onProbe(1, false, true)))
    }

    @Test
    fun aStreamingObservationCancelsPendingConfirmation() {
        val guard = guard()
        assertTrue(guard.onProbe(1, false, true) is LeaStreamGuard.Action.Confirm)
        assertTrue(guard.onProbe(1, true, true) is LeaStreamGuard.Action.None)
        // The failure counter was reset, so the next failure must confirm again.
        assertTrue(guard.onProbe(1, false, true) is LeaStreamGuard.Action.Confirm)
        assertEquals(60_000L, holdMs(guard.onProbe(1, false, true)))
    }

    @Test
    fun repeatedTripsDoubleTheHoldUpToTheCap() {
        val guard = guard()
        assertEquals(60_000L, trip(guard))
        assertEquals(120_000L, trip(guard))
        assertEquals(240_000L, trip(guard))
        assertEquals(300_000L, trip(guard))
        assertEquals(300_000L, trip(guard))
    }

    private fun trip(guard: LeaStreamGuard): Long {
        assertTrue(guard.onProbe(1, false, true) is LeaStreamGuard.Action.Confirm)
        return holdMs(guard.onProbe(1, false, true))
    }

    @Test
    fun aHealthyStreamStartsTheEscalationOver() {
        val guard = guard()
        trip(guard)
        assertEquals(120_000L, trip(guard))
        guard.onHealthyStream()
        assertEquals(60_000L, trip(guard))
    }

    @Test
    fun aLocalSelectionStartsTheEscalationOver() {
        val guard = guard()
        trip(guard)
        assertEquals(120_000L, trip(guard))
        guard.onLocalSelection()
        assertEquals(60_000L, trip(guard))
    }

    @Test
    fun aNewGroupStartsWithNoHistory() {
        val guard = guard()
        trip(guard)
        // The group was torn down and recreated with a new id: no escalation,
        // and the old confirmation state must not leak into it.
        assertTrue(guard.onProbe(2, false, true) is LeaStreamGuard.Action.Confirm)
        assertEquals(60_000L, holdMs(guard.onProbe(2, false, true)))
    }
}
