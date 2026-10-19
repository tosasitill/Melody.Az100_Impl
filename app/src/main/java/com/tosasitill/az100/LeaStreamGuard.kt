package com.tosasitill.az100

/**
 * Decides when an LE Audio group cannot start a stream and the output has to
 * go back to classic audio.
 *
 * <h2>The failure this covers (measured 2026-10-19)</h2>
 *
 * For a media stream this ROM can configure a bidirectional CIS: a sink ASE
 * and a source ASE on the same earbud.  The controller accepts the input data
 * path and rejects the output one:
 *
 * <pre>
 *   ASCS_CP_CMD  QOS_CONFIG: ASE 1,ASE 3,   snk,...;;src,...
 *   ASCS_CP_CMD  ENABLE: ASE_ID 1,ASE_ID 3, meta: 03020400020501;;meta: 03020100;;
 *   HCI_EVENT    SET_DATA_PATH: direction 0x00 STATUS=0000
 *   HCI_EVENT    SET_DATA_PATH: direction 0x01 STATUS=0x0c   // COMMAND_DISALLOWED
 *   SM           TARGET STATE CHANGED: STREAMING -> IDLE
 *   ASCS_CP_CMD  RELEASE: ASE_ID 1,ASE_ID 3,
 *   AF -->       RESUME_REQ: LocalSource                    // and round again
 * </pre>
 *
 * AOSP treats any data-path failure as fatal ({@code StopStream()} releases
 * the ASEs, the CIS and the CIG), and the audio framework immediately asks to
 * resume, so a complete create/configure/enable/fail/release cycle repeats
 * about twice a second while the LE ACL stays up.  The connection is fine;
 * the stream is not, and nothing in the Java layer can make that controller
 * direction work.
 *
 * <h2>How it is recognised</h2>
 *
 * A working stream reaches STREAMING in about half a second (measured
 * 2026-10-11: CODEC_CONFIG 15:14:41.981, ASE STREAMING 15:14:42.490).  A group
 * that is the active output, with audio actually in use, and still not
 * streaming seconds later is therefore not slow -- it is this failure.  Two
 * consecutive failed observations are required, so one slow start cannot trip
 * it.  Audio not being in use makes the observation inconclusive: an idle
 * group is supposed to be non-streaming.
 *
 * <p>The caller turns [Action.Hold] into a yield window and deactivates the
 * group, which lets the classic link carry the stream.  Repeated trips double
 * the hold (base, 2x, 4x, ... up to the cap); a healthy stream or an explicit
 * local selection resets the escalation.
 */
internal class LeaStreamGuard(
        private val baseHoldMs: Long,
        private val maxHoldMs: Long) {

    sealed class Action {
        object None : Action()
        object Confirm : Action()
        class Hold(val durationMs: Long) : Action()
    }

    private var groupId = -1
    private var confirming = false
    private var failures = 0

    /**
     * Feed one observation of [group].
     *
     * @param streaming the active group reports a live stream
     *     ({@code LeAudioService.getGroupStatus()} is STREAMING or VoIP).
     * @param audioInUse the phone is rendering or capturing audio right now.
     */
    @Synchronized
    fun onProbe(group: Int, streaming: Boolean, audioInUse: Boolean): Action {
        // A recreated group (new id) starts with no history.
        if (group != groupId) {
            groupId = group
            reset()
        }
        if (streaming) {
            reset()
            return Action.None
        }
        if (!audioInUse) {
            // Inconclusive: do not let an idle moment count as a failure.
            confirming = false
            return Action.None
        }
        if (!confirming) {
            confirming = true
            return Action.Confirm
        }
        confirming = false
        failures++
        val shift = (failures - 1).coerceIn(0, 4)
        return Action.Hold(minOf(baseHoldMs shl shift, maxHoldMs))
    }

    /** A stream-status report proves the group can stream; start over. */
    @Synchronized
    fun onHealthyStream() {
        reset()
    }

    /** The user picked the earbud again; give LE Audio a fresh chance. */
    @Synchronized
    fun onLocalSelection() {
        reset()
    }

    private fun reset() {
        confirming = false
        failures = 0
    }
}
