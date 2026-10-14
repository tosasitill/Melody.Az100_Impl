package com.tosasitill.az100

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaHandoffStateTest {

    private val state = LeaHandoffState(30_000L)

    @Test
    fun initialZeroContextsKeepTheBringUpGuard() {
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0, 1_000L))
        assertFalse(state.isYielding(1_038L))
        assertTrue(state.shouldHoldRelease(1_000L, 1_038L, 15_000L))
        assertFalse(state.shouldHoldRelease(1_000L, 16_001L, 15_000L))
        assertFalse(state.shouldHoldRelease(0L, 1_038L, 15_000L))
    }

    @Test
    fun computerTakeoverOverridesEvenARecentActivation() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        assertEquals(LeaHandoffState.ContextChange.YIELDED,
                state.onAvailableContexts(1, 0, 1_199L))
        assertTrue(state.isRemoteYielding())
        assertFalse(state.shouldHoldRelease(1_000L, 1_199L, 15_000L))
        assertFalse(state.shouldReconnectClassic(1_799L, true))
        assertFalse(state.shouldReconnectClassic(2_599L, false))
    }

    @Test
    fun otherContextsDoNotMaskWithdrawalOfMedia() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        assertEquals(LeaHandoffState.ContextChange.YIELDED,
                state.onAvailableContexts(1, 0x0ffb, 2_000L))
        assertTrue(state.isYielding(2_000L))
    }

    @Test
    fun remoteOwnershipSurvivesTheOldThirtySecondTimeout() {
        state.yieldRemotely(1, 1_000L)
        state.yieldTemporarily(2_000L)
        assertTrue(state.isYielding(90_000L))
        assertFalse(state.shouldReconnectClassic(90_000L, false))
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(2, 0x0fff, 90_001L))
        assertTrue(state.isRemoteYielding())
    }

    @Test
    fun nativeMediaReturnRestoresNormalPhoneBehavior() {
        state.yieldRemotely(1, 1_000L)
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0002, 90_000L))
        assertTrue(state.isYielding(90_000L))
        assertEquals(LeaHandoffState.ContextChange.RESUMED,
                state.onAvailableContexts(1, 0x0fff, 90_001L))
        assertFalse(state.isYielding(90_001L))
        assertTrue(state.shouldReconnectClassic(90_001L, false))
        assertFalse(state.shouldReconnectClassic(90_001L, true))
    }

    @Test
    fun explicitPhoneSelectionClearsOwnershipAndStaleContexts() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        state.yieldRemotely(1, 2_000L)
        state.resumeLocally()
        assertFalse(state.isYielding(2_001L))
        assertFalse(state.isRemoteYielding())
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0, 2_002L))
    }

    @Test
    fun remoteResourceReleaseDoesNotDialClassicAfterBothEarsDisappear() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        assertTrue(state.onAclDisconnected(1, 2, 0x14, 20_000L))
        assertTrue(state.isRemoteYielding())
        assertFalse(state.shouldReconnectClassic(20_600L, false))
        assertFalse(state.shouldHoldRelease(1_000L, 20_600L, 15_000L))
        assertTrue(state.onAclDisconnected(-1, 2, 0x14, 20_115L))
        assertTrue(state.isYielding(90_000L))
        assertFalse(state.shouldReconnectClassic(90_000L, false))
    }

    @Test
    fun automaticReconnectContextReportCannotCancelResourceRelease() {
        assertTrue(state.onAclDisconnected(1, 2, 0x14, 20_000L))
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0fff, 20_001L))
        assertTrue(state.isYielding(90_000L))
        state.resumeLocally()
        assertFalse(state.isYielding(90_001L))
        assertTrue(state.shouldReconnectClassic(90_001L, false))
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0fff, 90_002L))
    }

    @Test
    fun ordinaryTimeoutAndLocalDisconnectKeepClassicFallback() {
        assertFalse(state.onAclDisconnected(1, 1, 0x14, 20_000L))
        assertFalse(state.onAclDisconnected(1, 2, 0x08, 20_001L))
        assertFalse(state.onAclDisconnected(1, 2, 0x16, 20_002L))
        assertFalse(state.isRemoteYielding())
        assertTrue(state.shouldReconnectClassic(20_600L, false))
    }

    @Test
    fun ambiguousApiReleaseOnlyHasABoundedYieldWindow() {
        state.yieldTemporarily(1_000L)
        assertTrue(state.isYielding(31_000L))
        assertFalse(state.isRemoteYielding())
        assertFalse(state.isYielding(31_001L))
        assertFalse(state.isYielding(999L))
    }

    @Test
    fun autoReconnectSuppressionCoversTheEntireAmbiguousRelease() {
        state.yieldTemporarily(1_000L)
        assertTrue(state.shouldSuppressAutoConnect(9_001L))
        assertTrue(state.shouldSuppressAutoConnect(31_000L))
        assertFalse(state.shouldSuppressAutoConnect(31_001L))
        assertFalse(state.isYielding(31_001L))
    }

    @Test
    fun releaseBeforeFirstActivationCannotSuppressTheNewAudioOutput() {
        assertFalse(state.onApiRelease(-1, true, false, 1_000L))
        assertFalse(state.onGroupDeactivated(-1, true, 1_004L))
        assertFalse(state.isYielding(2_535L))
        assertFalse(state.shouldSuppressAutoConnect(2_535L))
        assertTrue(state.shouldHoldRelease(2_535L, 2_650L, 15_000L))
    }

    @Test
    fun releaseOfARealActiveGroupStillYieldsToAnotherHost() {
        assertTrue(state.onApiRelease(1, true, false, 20_000L))
        assertTrue(state.onGroupDeactivated(1, true, 20_005L))
        assertTrue(state.isYielding(20_100L))
        assertFalse(state.shouldReconnectClassic(20_100L, false))
    }

    @Test
    fun recentActivationOrAnUnrelatedGroupDoesNotOpenAHandoff() {
        assertFalse(state.onApiRelease(1, true, true, 1_199L))
        assertFalse(state.onApiRelease(-1, true, false, 1_200L))
        assertFalse(state.onApiRelease(2, false, false, 1_201L))
        assertFalse(state.onGroupDeactivated(2, false, 1_202L))
        assertFalse(state.isYielding(1_202L))
    }

    @Test
    fun noOpGroupReleaseCannotCancelConfirmedRemoteOwnership() {
        assertTrue(state.onAclDisconnected(1, 2, 0x14, 20_000L))
        assertFalse(state.onApiRelease(-1, true, false, 20_010L))
        assertFalse(state.onGroupDeactivated(-1, true, 20_020L))
        assertTrue(state.isYielding(90_000L))
        assertTrue(state.shouldSuppressAutoConnect(90_000L))
    }

    @Test
    fun manualDisconnectSurvivesPolicyRetriesContextReportsAndTimeouts() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        state.disconnectLocally()

        assertFalse(state.onConnectRequested(false, 2_300L))
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0fff, 2_500L))
        assertFalse(state.onConnectRequested(false, 2_600L))
        assertFalse(state.onConnectRequested(false, 90_000L))
        assertTrue(state.isManuallyDisconnected())
        assertTrue(state.isYielding(90_000L))
        assertTrue(state.shouldSuppressAutoConnect(90_000L))
        assertFalse(state.shouldReconnectClassic(90_000L, false))
        assertFalse(state.shouldHoldRelease(89_999L, 90_000L, 15_000L))
    }

    @Test
    fun onlyAnExplicitPhoneConnectReleasesTheManualDisconnect() {
        state.disconnectLocally()
        assertFalse(state.onConnectRequested(false, 1_000L))
        assertTrue(state.onConnectRequested(true, 2_000L))
        assertFalse(state.isManuallyDisconnected())
        assertFalse(state.isYielding(2_000L))
        assertTrue(state.onConnectRequested(false, 2_001L))
        assertTrue(state.shouldReconnectClassic(2_001L, false))
    }

    @Test
    fun automaticServiceAndSiblingConnectsCannotUndoComputerTakeover() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        assertTrue(state.onAclDisconnected(1, 2, 0x14, 1_100L))

        assertFalse(state.onConnectRequested(false, 1_200L))
        assertFalse(state.onConnectRequested(false, 1_201L))
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0fff, 1_202L))
        assertFalse(state.onConnectRequested(false, 90_000L))
        assertTrue(state.isRemoteYielding())
        assertTrue(state.isYielding(90_000L))
        assertFalse(state.shouldHoldRelease(1_000L, 1_300L, 15_000L))
        assertFalse(state.shouldReconnectClassic(90_000L, false))

        assertTrue(state.onConnectRequested(true, 90_001L))
        assertFalse(state.isRemoteYielding())
        assertFalse(state.isYielding(90_001L))
    }

    @Test
    fun aPolicyConnectCannotEraseTheContextsNeededToDetectMediaWithdrawal() {
        state.onAvailableContexts(1, 0x0fff, 1_000L)
        assertTrue(state.onConnectRequested(false, 1_100L))
        assertEquals(LeaHandoffState.ContextChange.YIELDED,
                state.onAvailableContexts(1, 0x0ffb, 1_200L))
        assertFalse(state.onConnectRequested(false, 1_300L))
        assertTrue(state.isRemoteYielding())
    }

    @Test
    fun aReleaseBlocksAutomaticActivationWithoutRequiringAnAclResourceSignal() {
        assertTrue(state.onApiRelease(1, true, false, 20_000L))
        assertFalse(state.onConnectRequested(false, 21_000L))
        assertTrue(state.isYielding(21_000L))
        assertFalse(state.shouldHoldRelease(20_999L, 21_000L, 15_000L))
        assertTrue(state.onConnectRequested(true, 21_001L))
        assertFalse(state.isYielding(21_001L))
    }

    @Test
    fun nativeMediaReturnCannotCancelAUserDisconnectDuringHandoff() {
        state.yieldRemotely(1, 1_000L)
        state.disconnectLocally()
        assertEquals(LeaHandoffState.ContextChange.NONE,
                state.onAvailableContexts(1, 0x0fff, 1_200L))
        assertFalse(state.onConnectRequested(false, 1_300L))
        assertTrue(state.onConnectRequested(true, 1_400L))
        assertFalse(state.isManuallyDisconnected())
        assertFalse(state.isRemoteYielding())
    }

    @Test
    fun aServiceReconnectDuringAnAmbiguousReleaseDoesNotReactivateTheGroup() {
        state.yieldTemporarily(1_000L)
        assertFalse(state.onConnectRequested(false, 2_000L))
        assertFalse(state.onConnectRequested(false, 9_001L))
        assertFalse(state.onConnectRequested(false, 31_000L))
        assertTrue(state.isYielding(9_001L))
        assertFalse(state.shouldHoldRelease(9_000L, 9_001L, 15_000L))
        assertFalse(state.isYielding(31_001L))
        assertTrue(state.onConnectRequested(false, 31_001L))
    }

    @Test
    fun confirmedRemoteOwnerKeepsAutomaticReconnectSuppressed() {
        state.yieldRemotely(1, 1_000L)
        assertTrue(state.shouldSuppressAutoConnect(90_000L))
        state.resumeLocally()
        assertFalse(state.shouldSuppressAutoConnect(90_001L))
    }
}
