package com.tosasitill.az100

/** Pure state gate for the automatic LE Audio connect path. */
internal object LeaConnectGate {

    /**
     * Only a known disconnected device may start a fresh connection attempt.
     * Unknown state and clock rollback both fail closed.
     */
    fun shouldStart(
        connectionState: Int?,
        previousAttemptAt: Long?,
        now: Long,
        cooldownMs: Long
    ): Boolean {
        if (connectionState != 0) return false
        if (previousAttemptAt == null) return true
        if (now < previousAttemptAt) return false
        return now - previousAttemptAt >= cooldownMs
    }
}
