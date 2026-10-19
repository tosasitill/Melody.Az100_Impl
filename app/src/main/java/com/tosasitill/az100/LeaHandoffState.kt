package com.tosasitill.az100

/** Tracks headset ownership independently of the lifetime of its LE ACLs. */
internal class LeaHandoffState(private val yieldWindowMs: Long) {

    enum class ContextChange { NONE, YIELDED, RESUMED }

    private val contextsByGroup = mutableMapOf<Int, Int>()
    private var yieldAt: Long? = null
    private var yieldDurationMs = yieldWindowMs
    private var remoteGroupId: Int? = null
    private var remoteAclReleased = false
    private var manuallyDisconnected = false

    @Synchronized
    fun isYielding(now: Long): Boolean {
        if (manuallyDisconnected || remoteGroupId != null) return true
        val openedAt = yieldAt ?: return false
        return now >= openedAt && now - openedAt <= yieldDurationMs
    }

    /** Explicit selection can end the window; background connects must wait. */
    @Synchronized
    fun shouldSuppressAutoConnect(now: Long): Boolean = isYielding(now)

    /** A disconnect applies to the whole headset until the user selects it again. */
    @Synchronized
    fun disconnectLocally() {
        manuallyDisconnected = true
    }

    @Synchronized
    fun isManuallyDisconnected(): Boolean = manuallyDisconnected

    /** Service connect() is also used by policy retries and sibling auto-connect. */
    @Synchronized
    fun onConnectRequested(explicitLocalSelection: Boolean, now: Long): Boolean {
        if (explicitLocalSelection) {
            resumeLocally()
            return true
        }
        return !shouldSuppressAutoConnect(now)
    }

    /** Releasing an already inactive group is normal connection bookkeeping. */
    @Synchronized
    fun onApiRelease(activeGroupId: Int, az100Present: Boolean, recentlyActivated: Boolean, now: Long): Boolean {
        if (activeGroupId == -1 || !az100Present || recentlyActivated) return false
        yieldTemporarily(now)
        return true
    }

    /** Only an actual active-to-inactive transition can extend a handoff. */
    @Synchronized
    fun onGroupDeactivated(previousGroupId: Int, az100Present: Boolean, now: Long): Boolean {
        if (previousGroupId == -1 || !az100Present) return false
        yieldTemporarily(now)
        return true
    }

    @Synchronized
    fun isRemoteYielding(): Boolean = remoteGroupId != null

    /**
     * @param durationMs how long the window lasts; a failed LE stream holds
     *     longer on every trip than a normal handoff.
     */
    @Synchronized
    fun yieldTemporarily(now: Long, durationMs: Long = yieldWindowMs) {
        // Releases arrive in bursts (the API release, then the group
        // deactivation that follows it).  Re-arm the start every time, but let
        // the longer fail-over hold survive the normal handoff that trails it.
        val keepLonger = yieldAt != null && yieldDurationMs > durationMs && isYielding(now)
        yieldAt = now
        if (!keepLonger) yieldDurationMs = durationMs
    }

    @Synchronized
    fun yieldRemotely(groupId: Int, now: Long) {
        remoteGroupId = groupId
        yieldAt = now
        yieldDurationMs = yieldWindowMs
    }

    /** AZ100 frees its LE links with 0x14 when another host takes its resources. */
    @Synchronized
    fun onAclDisconnected(groupId: Int, transport: Int, reason: Int, now: Long): Boolean {
        if (transport != LE_TRANSPORT || reason != REMOTE_LOW_RESOURCES) return false
        remoteAclReleased = true
        yieldRemotely(groupId, now)
        return true
    }

    @Synchronized
    fun onAvailableContexts(groupId: Int, contexts: Int, now: Long): ContextChange {
        val previous = contextsByGroup.put(groupId, contexts)
        if (manuallyDisconnected) return ContextChange.NONE
        val mediaAvailable = contexts and MEDIA_CONTEXT != 0
        // A stale capability report or an automatic reconnect does not undo a
        // peer's resource release. That handoff ends with explicit local selection.
        if (mediaAvailable && remoteGroupId == groupId && !remoteAclReleased) {
            remoteGroupId = null
            yieldAt = null
            yieldDurationMs = yieldWindowMs
            return ContextChange.RESUMED
        }
        // Initial zero contexts are part of bring-up on this ROM. Only a real
        // withdrawal of an advertised MEDIA context proves a handoff here.
        if (!mediaAvailable && previous != null && previous and MEDIA_CONTEXT != 0) {
            yieldRemotely(groupId, now)
            return ContextChange.YIELDED
        }
        return ContextChange.NONE
    }

    @Synchronized
    fun resumeLocally() {
        manuallyDisconnected = false
        remoteGroupId = null
        remoteAclReleased = false
        yieldAt = null
        yieldDurationMs = yieldWindowMs
        contextsByGroup.clear()
    }

    @Synchronized
    fun shouldHoldRelease(activatedAt: Long, now: Long, holdWindowMs: Long): Boolean {
        return !isYielding(now) && activatedAt > 0L && now >= activatedAt
                && now - activatedAt <= holdWindowMs
    }

    @Synchronized
    fun shouldReconnectClassic(now: Long, leConnected: Boolean): Boolean {
        return !isYielding(now) && !leConnected
    }

    private companion object {
        const val MEDIA_CONTEXT = 0x0004
        const val LE_TRANSPORT = 2
        const val REMOTE_LOW_RESOURCES = 0x14
    }
}
