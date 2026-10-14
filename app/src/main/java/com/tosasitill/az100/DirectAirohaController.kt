package com.tosasitill.az100

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `Any.wait` / `Any.notifyAll` are not in Kotlin's stdlib, and this controller is
 * written against Java's monitor semantics (one monitor per shared field, wait
 * with timeout, signal-all).  These two helpers keep exactly that behaviour.
 */
private fun Any.waitFor(timeoutMs: Long) {
    (this as java.lang.Object).wait(timeoutMs)
}

private fun Any.signalAll() {
    (this as java.lang.Object).notifyAll()
}

/**
 * Airoha MMI link over plain RFCOMM, owned by the calling process.
 *
 * This is a full re-implementation of the slice of Audio Connect 4.4.0 that the
 * module needs; nothing here loads a single class, resource or byte of
 * `com.panasonic.technicsaudioconnect`.  The wire protocol was recovered from
 * that APK and is documented in `reverse/` and [AirohaRace]:
 *
 * ```
 *   socket   BluetoothDevice.createRfcommSocketToServiceRecord(
 *               00000000-0000-0000-0099-AABBCCDDEEFF)      // Airoha SPP
 *   frames   H4: 05 5A <len LE16> <race id LE16> <payload>
 *   answer   05 5B <len LE16> <race id LE16> <status> ...
 *   notice   05 5D ...                                     // unsolicited
 * ```
 *
 * Sequence, copied from `DeviceMmiAiroha2`:
 *
 * 1. open the RFCOMM socket; the stock stack starts its reader right after
 *    `connect()` and sends *nothing* -- there is no handshake;
 * 2. commands are RACE frames with type 0x5A, one at a time, each answered by the
 *    headset with type 0x5B and a status byte (0 = applied);
 * 3. the headset also pushes state changes (type 0x5D) -- outside control at
 *    race id 10, battery at 3286 -- which is the truth to mirror.
 *
 * Design notes kept from the previous (reflection based) version, because each
 * one was paid for with a visible bug:
 *
 * - one command = one short lived link: the socket is opened when the user clicks
 *   and closed again right after.  Nothing keeps a socket open, so the two hooked
 *   processes can never fight over the single RFCOMM channel, and an idle module
 *   never pages the headset while it is being used by another device;
 * - exactly one command is ever in flight (the newest click wins);
 * - a read-only sync (mode + battery) runs at most once per connection.  Later
 *   provider queries do not open SPP;
 * - no background redial: a failed or dropped link ends the attempt; the next
 *   attempt is the next click or the next connect;
 * - nothing is dialed unless the headset is confirmed connected to this phone.
 */
object DirectAirohaController {

    fun interface BatteryRefreshCallback {
        fun onComplete(values: IntArray?, receivedFreshSample: Boolean)
    }

    private class PendingBatteryRefresh(
        val callback: BatteryRefreshCallback,
        val startUpdates: Long
    )

    /** Airoha SPP service UUID (UuidTable.AIROHA_SPP_UUID). */
    private val SPP_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-AABBCCDDEEFF")

    /** MMI outside-control modes (OutsideCtrl.getModeByte). */
    private const val CTRL_OFF: Byte = 0
    private const val CTRL_ANC: Byte = 1
    private const val CTRL_AMBIENT: Byte = 2

    /** Status vocabulary shared with the rest of the module. */
    private const val STATUS_OFF = 1
    private const val STATUS_ANC = 2
    private const val STATUS_AMBIENT = 3

    /** Reported by the headset only; the panel no longer selects it. */
    private const val STATUS_ADAPTIVE = 4

    /** One SPP/RFCOMM connect attempt may take this long before we retry. */
    private const val LINK_WAIT_MS = 10_000L

    /** A connect that never reports back is dropped after this long. */
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /** RACE response timeout used by Audio Connect's AirohaMmiMgr. */
    private const val RACE_RESPONSE_TIMEOUT_MS = 6000

    /** TWS battery data is a second, asynchronous packet after the ACK. */
    private const val BATTERY_INDICATION_TIMEOUT_MS = 6000

    /** Give the RFCOMM reader a short window to settle before the first write. */
    private const val LINK_STABILIZE_MS = 100L

    /** Bluetooth probe (binder) results are reused this long. */
    private const val PRESENCE_TTL_MS = 15_000L

    /**
     * Outside-control levels.  The headset validates them: Audio Connect sends
     * back exactly what the AZ100 reports (a fresh unit answers
     * `mode=1 anc=100 amb=100`), and a packet carrying the class defaults (0 / 10)
     * is rejected with status 1 -- i.e. silently ignored, which is why the tiles
     * looked dead.  Values are 0..100 and refreshed from every `OnGetOutsideCtrl`.
     */
    private const val DEFAULT_ANC_LEVEL: Byte = 100
    private const val DEFAULT_AMBIENT_LEVEL: Byte = 100

    /** BluetoothProfile.LE_AUDIO; the AZ100 can also connect as LE Audio. */
    private const val LE_AUDIO_PROFILE = 22

    /** Audio profiles asked during a presence probe. */
    private val PROBE_PROFILES = intArrayOf(
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
        LE_AUDIO_PROFILE
    )

    private const val LINK_IDLE = 0
    private const val LINK_CONNECTING = 1
    private const val LINK_READY = 2

    private val SESSIONS = ConcurrentHashMap<String, Session>()
    private val BATTERY = ConcurrentHashMap<String, IntArray>()

    /** ACL state for the headset: 0 unknown, 1 connected, -1 gone. */
    @Volatile
    private var aclPresent = 0

    /** Cached presence probes: confirmed / absent / unknown. */
    private const val PROBE_CONNECTED = 1
    private const val PROBE_ABSENT = -1
    private const val PROBE_UNKNOWN = 0

    /** Apply a mode (1 off / 2 ANC / 3 ambient) to the headset. */
    fun request(context: Context?, address: String, status: Int) {
        session(context, address)?.request(normalizeStatus(status))
    }

    /**
     * Read mode and battery once while this connection lasts.  Provider queries
     * and media-route callbacks may call this on every binder transaction; only
     * the first call that still sees the headset connected opens SPP.
     */
    fun syncOnce(context: Context?, address: String) {
        if (aclPresent < 0) return
        session(context, address)?.syncOnce()
    }

    /** Force a user-requested read without changing the once-per-connection latch. */
    fun refreshBattery(context: Context?, address: String, callback: BatteryRefreshCallback?) {
        val session = session(context, address)
        if (session == null) {
            callback?.onComplete(null, false)
            return
        }
        session.refreshBattery(callback)
    }

    /** Drop the link (and the socket) when the headset goes away. */
    fun disconnect(context: Context?, address: String, reason: String) {
        session(context, address)?.release(reason)
    }

    /**
     * Presence as the session sees it: the ACL broadcast first, the one-time
     * profile probe second (cached).  The Melody provider answers queries before
     * any command was sent, so it needs this without owning a link.
     */
    fun reachable(context: Context?, address: String): Boolean =
        session(context, address)?.reachable() == true

    /**
     * True when any headset audio profile is still connected.  This uses the
     * public profile state API, unlike `BluetoothAdapter.getConnectedDevices(int)`,
     * which this ROM does not expose.  It only guards against treating our own SPP
     * teardown as the headset leaving.
     */
    fun audioProfileConnected(context: Context?): Boolean {
        val adapter = try {
            var found: BluetoothAdapter? = null
            if (context != null) {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? BluetoothManager
                found = manager?.adapter
            }
            found ?: BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            Logs.trace("audio profile adapter failed: $t")
            return false
        } ?: return false

        val a2dp = profileState(adapter, BluetoothProfile.A2DP)
        val headset = profileState(adapter, BluetoothProfile.HEADSET)
        val leAudio = profileState(adapter, LE_AUDIO_PROFILE)
        Logs.trace("audio profiles a2dp=$a2dp hfp=$headset le=$leAudio")
        return a2dp == BluetoothProfile.STATE_CONNECTED
                || headset == BluetoothProfile.STATE_CONNECTED
                || leAudio == BluetoothProfile.STATE_CONNECTED
    }

    private fun profileState(adapter: BluetoothAdapter, profile: Int): Int = try {
        adapter.getProfileConnectionState(profile)
    } catch (t: Throwable) {
        BluetoothProfile.STATE_DISCONNECTED
    }

    fun setPresent(present: Boolean) {
        val value = if (present) 1 else -1
        aclPresent = value
        // The buds appeared/disappeared: let every live session react at once
        // (a fresh connect must not wait out a backoff won while they were away).
        for (session in SESSIONS.values) session.onAcl(value)
    }

    fun cachedBattery(address: String?): IntArray? =
        BATTERY[address?.uppercase(Locale.ROOT) ?: ""]?.copyOf()

    /**
     * `Context.getApplicationContext()` is null while `attach()` is still running,
     * so the base context is kept until a better one appears.
     */
    private fun session(context: Context?, address: String?): Session? {
        if (address.isNullOrEmpty()) return null
        val key = address.uppercase(Locale.ROOT)
        SESSIONS[key]?.let { return it }
        val app = context?.applicationContext
        val created = Session(app ?: context, address)
        return SESSIONS.putIfAbsent(key, created) ?: created
    }

    private fun normalizeStatus(status: Int): Int =
        if (status == STATUS_ANC || status == STATUS_AMBIENT) status else STATUS_OFF

    /**
     * Never log a full address: the module's own traces stay redacted.  See
     * `AGENTS.md` -- Bluetooth addresses are device-specific data.
     */
    private fun mask(address: String?): String {
        if (address == null || address.length < 5) return "?"
        return "**:**:**" + address.substring(address.length - 5)
    }

    /* ------------------------------------------------------------------ */
    /* session                                                             */
    /* ------------------------------------------------------------------ */

    private class Session(context: Context?, private val address: String) : Runnable, AirohaRace.Sink {

        private val key = address.uppercase(Locale.ROOT)

        @Volatile
        private var context: Context? = context

        private val lock = Any()
        private val linkLock = Any()
        private val ackLock = Any()
        private val batteryLock = Any()

        /** Newest requested mode, -1 when nothing is pending. */
        private var wantedMode = -1
        private var wantedQuery = false
        private var wantedBatteryRefresh = false
        private val pendingBatteryRefreshes = ArrayList<PendingBatteryRefresh>()
        private var workerStarted = false

        @Volatile
        private var linkState = LINK_IDLE

        @Volatile
        private var connectStartedAt = 0L

        /** Set from the reader thread; the worker performs the actual teardown. */
        @Volatile
        private var needsReset = false

        /** Set when the current socket dies, so a waiting command fails fast. */
        @Volatile
        private var linkDead = false

        /** Open attempt state, written by the connect thread. */
        @Volatile
        private var socket: BluetoothSocket? = null

        @Volatile
        private var openerRunning = false

        @Volatile
        private var openOk = false

        @Volatile
        private var openError: Throwable? = null

        /** A frame the worker is waiting for: set by the reader, consumed once. */
        @Volatile
        private var ack: AirohaRace.Frame? = null

        /** Which race id the ACK must belong to; -1 means no active waiter. */
        @Volatile
        private var ackFor = -1

        /** Response type expected by the current ack waiter. */
        @Volatile
        private var ackType = -1

        /** Battery indications have their own completion signal after the ACK. */
        private val batteryRoleUpdates = LongArray(2)

        /** Wire roles are agent/partner, not fixed left/right channels. */
        @Volatile
        private var agentIsRight = false

        @Volatile
        private var agentChannelKnown = false

        /** Bumped on every teardown; a late connect() must not commit a socket. */
        @Volatile
        private var attemptSeq = 0

        @Volatile
        private var ancLevel = DEFAULT_ANC_LEVEL

        @Volatile
        private var ambientLevel = DEFAULT_AMBIENT_LEVEL

        @Volatile
        private var adaptiveOn = false

        /** False until the headset itself reported the adaptive flag. */
        @Volatile
        private var adaptiveKnown = false

        /**
         * Command that is still waiting for its ack.  A response timeout used to
         * lose the click (the link was rebuilt and the command was already
         * consumed); it is now re-queued after the reconnect.
         */
        @Volatile
        private var pendingMode = -1

        @Volatile
        private var retries = 0

        /** Cached binder probe for the connected-to-this-phone question. */
        @Volatile
        private var probeAt = 0L

        @Volatile
        private var probed = PROBE_UNKNOWN

        /** Last probe failure, kept only for the trace file. */
        @Volatile
        private var probeError: String? = null

        /** ACL hint this session last saw, to notice a fresh connect instantly. */
        @Volatile
        private var aclSeen = 0

        /** A read-only sync already ran (or was claimed) for the current connection. */
        @Volatile
        private var synced = false

        @Volatile
        private var batteryUpdates = 0L

        fun reachable(): Boolean {
            onAcl(aclPresent)
            // The volume panel gate: "unknown" still shows the row (the ACL
            // broadcast may predate this process), only an explicit disconnect
            // hides it.
            return connectionState() != PROBE_ABSENT
        }

        /** ACL state changed (or was noticed late): forget the stale probe. */
        fun onAcl(acl: Int) {
            if (acl != 0 && acl != aclSeen) {
                aclSeen = acl
                probed = PROBE_UNKNOWN
                probeAt = 0L
                // A new connection may sync once.  Going away must not keep the
                // latch, or the next connect would stay on the stale label.
                if (acl < 0) synced = false
            }
        }

        /**
         * Open one short read-only link if the buds are confirmed connected and
         * this connection has not been synced yet.  Safe to call from every
         * provider query: the latch makes the rest free.
         */
        fun syncOnce() {
            if (synced) return
            if (connectionState() != PROBE_CONNECTED) return
            synchronized(lock) {
                if (synced) return
                synced = true
                wantedQuery = true
                startWorker()
                lock.signalAll()
            }
        }

        fun refreshBattery(callback: BatteryRefreshCallback?) {
            synchronized(lock) {
                if (callback != null) {
                    pendingBatteryRefreshes.add(PendingBatteryRefresh(callback, batteryUpdates))
                }
                wantedBatteryRefresh = true
                startWorker()
                lock.signalAll()
            }
        }

        /** The base context is fine; upgrade to the Application once it exists. */
        private fun contextForCodeLoading(): Context? {
            val current = context
            if (current != null) {
                val app = current.applicationContext
                if (app != null) {
                    context = app
                    return app
                }
                return current
            }
            val app = RuntimeEnvironment.application
            if (app != null) context = app
            return app
        }

        /* ---------------- public api ---------------- */

        fun request(status: Int) {
            Logs.trace("request mode=$status")
            synchronized(lock) {
                wantedMode = status          // newest click wins
                retries = 0                  // every click gets one fresh retry
                startWorker()
                lock.signalAll()
            }
        }

        private fun startWorker() {
            if (workerStarted) return
            workerStarted = true
            Thread(this, "az100-airoha").apply {
                isDaemon = true
                start()
            }
        }

        fun release(reason: String) {
            Logs.trace("release ${mask(key)} reason=$reason")
            val cancelled: List<PendingBatteryRefresh>
            synchronized(lock) {
                wantedMode = -1
                wantedQuery = false
                wantedBatteryRefresh = false
                cancelled = ArrayList(pendingBatteryRefreshes)
                pendingBatteryRefreshes.clear()
            }
            teardown()
            completeBatteryRefreshes(cancelled)
        }

        /** Socket is useless: drop it.  The worker exits until the next command. */
        private fun teardown() {
            attemptSeq++
            closeSocket()
            linkDead = true
            clearAckWait()
            synchronized(ackLock) { ackLock.signalAll() }
            synchronized(batteryLock) { batteryLock.signalAll() }
            adaptiveOn = false
            adaptiveKnown = false
            agentChannelKnown = false
            linkState = LINK_IDLE
            synchronized(linkLock) { linkLock.signalAll() }
        }

        private fun closeSocket() {
            val current = socket
            socket = null
            if (current != null) {
                try {
                    current.close()
                } catch (t: Throwable) {
                    Logs.d("socket close failed", t)
                }
            }
        }

        /* ---------------- worker ---------------- */

        override fun run() {
            Logs.trace("worker start")
            try {
                loop()
            } catch (t: Throwable) {
                Logs.trace("worker crashed: $t")
                Logs.e("airoha worker crashed", t)
            } finally {
                synchronized(lock) {
                    workerStarted = false
                    // Nothing may be left un-consumed: if the loop died with a
                    // command pending, restart instead of dropping clicks.
                    if (wantedMode >= 0 || wantedQuery || wantedBatteryRefresh) startWorker()
                }
            }
        }

        private fun loop() {
            while (true) {
                if (needsReset) {
                    needsReset = false
                    val again = pendingMode
                    Logs.trace("link dropped mid-command")
                    teardown()
                    // The socket died while the command was in flight (the
                    // channel is shared by two module processes): retry once,
                    // then give up instead of redialing the headset forever.
                    if (again >= 0 && retries < 1 && connectionState() == PROBE_CONNECTED) {
                        retries++
                        Logs.trace("retry command mode=$again")
                        synchronized(lock) {
                            if (wantedMode < 0) wantedMode = again
                        }
                    } else {
                        pendingMode = -1
                    }
                }

                var mode = -1
                var query = false
                var batteryRefresh = false
                var refreshCallbacks: List<PendingBatteryRefresh>? = null
                synchronized(lock) {
                    if (wantedMode < 0 && !wantedQuery && !wantedBatteryRefresh) {
                        // Idle: leave.  Object.wait() would wake this process
                        // every minute for no work.  The next click or sync
                        // starts a new daemon.
                        return
                    }
                    if (wantedMode >= 0) {
                        mode = wantedMode
                        wantedMode = -1
                    }
                    if (wantedQuery) {
                        query = true
                        wantedQuery = false
                    }
                    if (wantedBatteryRefresh) {
                        batteryRefresh = true
                        wantedBatteryRefresh = false
                        refreshCallbacks = ArrayList(pendingBatteryRefreshes)
                        pendingBatteryRefreshes.clear()
                    }
                }

                // One command = one short lived link: close whatever a former
                // attempt left behind before deciding anything.
                teardown()

                try {
                    val state = connectionState()
                    if (mode >= 0 || batteryRefresh) {
                        // A click after an explicit disconnect is ignored on
                        // purpose: the headset is on another device and must not
                        // be paged.
                        if (state == PROBE_ABSENT) {
                            pendingMode = -1
                            Logs.trace(
                                if (batteryRefresh) {
                                    "battery refresh ignored, headset not connected"
                                } else {
                                    "click ignored, headset not connected"
                                }
                            )
                            continue
                        }
                    } else if (query && state != PROBE_CONNECTED) {
                        // Background battery refresh only runs on a confirmed live link.
                        Logs.trace("query skipped, headset not confirmed")
                        continue
                    }

                    if (!ensureLink(mode >= 0 || batteryRefresh)) {
                        pendingMode = -1
                        continue
                    }

                    try {
                        if (mode >= 0) applyMode(mode)
                        // A sync claimed alongside a click still has to run: the
                        // click path does not ask for battery.
                        if (query) queryState() else if (batteryRefresh) queryBattery()
                    } finally {
                        teardown()
                    }
                } finally {
                    completeBatteryRefreshes(refreshCallbacks)
                }
            }
        }

        /* ---------------- link ---------------- */

        private fun ensureLink(userInitiated: Boolean): Boolean {
            if (linkState == LINK_READY) return true
            // One strict gate before any RFCOMM work.  Explicitly gone means
            // never dial (the user has the headset on another device); unknown
            // allows the single attempt that follows a real user click.
            val state = connectionState()
            if (state == PROBE_ABSENT) {
                Logs.trace("not connected, skip connect")
                return false
            }
            if (state != PROBE_CONNECTED && !userInitiated) {
                Logs.trace("link unconfirmed, background path skipped")
                return false
            }

            val now = SystemClock.elapsedRealtime()
            if (linkState == LINK_CONNECTING && now - connectStartedAt > CONNECT_TIMEOUT_MS) {
                Logs.trace("connect watchdog fired")
                markFailed("connect watchdog")
                teardown()
                return false
            }

            if (linkState != LINK_CONNECTING) {
                linkState = LINK_CONNECTING
                connectStartedAt = now
                openLink()
            }
            if (linkState == LINK_CONNECTING) {
                synchronized(linkLock) {
                    val deadline = SystemClock.elapsedRealtime() + LINK_WAIT_MS
                    while (linkState == LINK_CONNECTING
                        && SystemClock.elapsedRealtime() < deadline
                    ) {
                        try {
                            linkLock.waitFor(500L)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                }
            }
            return linkState == LINK_READY
        }

        private fun markReady() {
            Logs.trace("link ready")
            linkState = LINK_READY
            synchronized(linkLock) { linkLock.signalAll() }
            Logs.i("airoha link ready for ${mask(key)}")
        }

        private fun markFailed(reason: String) {
            Logs.trace("link failed: $reason")
            if (linkState != LINK_READY) linkState = LINK_IDLE
            synchronized(linkLock) { linkLock.signalAll() }
            Logs.d("airoha link attempt failed: $reason")
        }

        /**
         * ACL broadcast first, cached binder probe second: `PROBE_CONNECTED` /
         * `PROBE_ABSENT` / `PROBE_UNKNOWN`.  Only an explicit "gone" forbids
         * talking to the headset; "unknown" merely forbids the background paths --
         * a user click may still make a single attempt (the ACL broadcast may
         * predate this process).
         */
        private fun connectionState(): Int {
            val known = aclPresent
            if (known != 0) return known
            val now = SystemClock.elapsedRealtime()
            if (now - probeAt < PRESENCE_TTL_MS) return probed
            probeAt = now
            probed = PROBE_UNKNOWN
            val base = contextForCodeLoading() ?: return probed
            probeError = null
            var answered = false
            var found = false
            // The manager route first: BluetoothManager#getConnectedDevices(int)
            // is public API on this build and returns the actual devices, which is
            // the only way to confirm the connection belongs to THIS headset
            // rather than to some other headset of the user's.
            val manager = try {
                base.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            } catch (t: Throwable) {
                probeError = "manager: $t"
                null
            }
            if (manager != null) {
                for (profile in PROBE_PROFILES) {
                    val state = probe(manager, profile)
                    if (state >= 0) answered = true
                    if (state > 0) found = true
                }
            }
            // Fall back to the adapter's profile state only if the manager gave no
            // answer at all.  That call cannot name the device, so it is a weaker
            // signal and must not be the first choice.
            if (!answered) {
                val adapter = try {
                    BluetoothAdapter.getDefaultAdapter()
                } catch (t: Throwable) {
                    probeError = "adapter: $t"
                    null
                }
                if (adapter != null) {
                    for (profile in PROBE_PROFILES) {
                        val state = probe(adapter, profile)
                        if (state >= 0) answered = true
                        if (state > 0) found = true
                    }
                }
            }
            probed = if (!answered) PROBE_UNKNOWN else if (found) PROBE_CONNECTED else PROBE_ABSENT
            if (!answered) {
                Logs.trace("probe unanswered ($probeError)")
            } else {
                Logs.trace("probe answered=$answered connected=$found")
            }
            return probed
        }

        /**
         * -1 profile unsupported, 0 not connected, 1 this headset connected.
         *
         * `BluetoothAdapter.getConnectedDevices(int)` does not exist on this
         * build -- it is not declared on the class at all, and the reflective call
         * threw on every attempt:
         *
         * ```
         * probe unanswered (p2: java.lang.NoSuchMethodException:
         *     android.bluetooth.BluetoothAdapter.getConnectedDevices [int])
         * ```
         *
         * Measured 2026-10-13.  Because `answered` stayed false the probe fell
         * through to the manager route, which used the same missing method and
         * failed identically, so the result was `PROBE_UNKNOWN` forever and
         * `syncOnce()`'s `!= PROBE_CONNECTED` guard never opened -- the
         * once-per-connection mode and battery read simply never ran.
         *
         * `BluetoothManager#getConnectedDevices(int)` is public on this build and
         * returns the connected [BluetoothDevice]s, which is what [matches] needs
         * to confirm the peer is this headset.  It is the primary route now.
         */
        private fun probe(manager: BluetoothManager, profile: Int): Int = try {
            matches(manager.getConnectedDevices(profile))
        } catch (t: Throwable) {
            if (probeError == null) probeError = "m$profile: $t"
            -1
        }

        /**
         * Last resort: `BluetoothAdapter#getProfileConnectionState(int)`.
         *
         * Public API, and the call [audioProfileConnected] already uses
         * successfully on this ROM.  It answers "is this profile connected to
         * *something*", not "to this headset", so it is only consulted when the
         * manager route produced no answer at all -- and even then the session only
         * ever treats a positive result as a hint that the headset is present.
         */
        private fun probe(adapter: BluetoothAdapter, profile: Int): Int = try {
            if (adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED) 1 else 0
        } catch (t: Throwable) {
            if (probeError == null) {
                val cause = t.cause ?: t
                probeError = "p$profile: $cause"
            }
            -1
        }

        private fun matches(devices: List<BluetoothDevice>?): Int {
            if (devices == null) return 0
            for (device in devices) {
                if (address.equals(device.address, ignoreCase = true)) return 1
            }
            return 0
        }

        /**
         * Open RFCOMM off the worker thread (connect can block for tens of
         * seconds) and start the H4 reader as soon as the socket is up -- which is
         * exactly what the stock `SppController` does, minus the teardown that used
         * to make every click pay for a fresh connect.
         */
        private fun openLink() {
            Logs.trace("connecting")
            synchronized(linkLock) {
                if (openerRunning) return
                openerRunning = true
                openOk = false
                openError = null
            }
            val seq = attemptSeq
            Thread({
                var opened: BluetoothSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null || !adapter.isEnabled) {
                        throw IOException("bluetooth disabled")
                    }
                    val device = adapter.getRemoteDevice(address)
                    opened = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    opened.connect()
                    if (seq != attemptSeq) {
                        // A watchdog already gave up on this attempt.
                        throw IOException("attempt superseded")
                    }
                    socket = opened
                    linkDead = false
                    startReader(opened)
                    // Start reading before the first write.  Keep a small, bounded
                    // settling window without inventing a protocol handshake that
                    // Audio Connect never sends.
                    sleep(LINK_STABILIZE_MS)
                    if (seq != attemptSeq || opened !== socket || linkDead) {
                        throw IOException("link ended before ready")
                    }
                    synchronized(linkLock) {
                        openOk = true
                        openerRunning = false
                        linkLock.signalAll()
                    }
                    markReady()
                    return@Thread
                } catch (t: Throwable) {
                    if (opened != null) {
                        try {
                            opened.close()
                        } catch (ignored: Throwable) {
                            // The socket is already unusable.
                        }
                    }
                    synchronized(linkLock) {
                        openError = t
                        openerRunning = false
                        linkLock.signalAll()
                    }
                }
            }, "az100-spp-open").apply {
                isDaemon = true
                start()
            }

            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            synchronized(linkLock) {
                while (openerRunning && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        linkLock.waitFor(500L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            if (openerRunning) {
                Logs.trace("open timed out, aborting socket")
                // Invalidate the in-flight attempt so a late connect() cannot
                // commit a socket the worker has already given up on.
                attemptSeq++
                closeSocket()
                synchronized(linkLock) { openerRunning = false }
                markFailed("open timeout")
            } else if (!openOk) {
                markFailed("open: $openError")
            }
        }

        private fun startReader(current: BluetoothSocket) {
            Thread({
                val chunk = ByteArray(2000)
                val parser = AirohaRace.Parser()
                val sink = AirohaRace.Sink { frame ->
                    if (current === socket) onFrame(frame)
                }
                try {
                    val input = current.inputStream
                    while (current === socket) {
                        val n = input.read(chunk)
                        // -1 is EOF.  0 should not happen for a positive buffer,
                        // but spinning on it would peg a core.
                        if (n <= 0 || current !== socket) break
                        Logs.trace("rx raw ${AirohaRace.hex(chunk.copyOf(n))}")
                        parser.feed(chunk, 0, n, sink)
                    }
                } catch (t: Throwable) {
                    Logs.trace("reader stopped: $t")
                } finally {
                    if (current === socket) {
                        // The socket died (the channel is shared by the two hooked
                        // processes): wake the worker and let it decide whether one
                        // retry is still allowed.
                        needsReset = true
                        linkDead = true
                        markFailed("reader ended")
                        synchronized(ackLock) { ackLock.signalAll() }
                        synchronized(batteryLock) { batteryLock.signalAll() }
                        synchronized(lock) { lock.signalAll() }
                    }
                }
            }, "az100-spp-read").apply {
                isDaemon = true
                start()
            }
        }

        /* ---------------- frames ---------------- */

        private fun write(frame: ByteArray): Boolean {
            val current = socket ?: return false
            return try {
                current.outputStream.apply {
                    write(frame)
                    flush()
                }
                Logs.trace("tx ${AirohaRace.hex(frame)}")
                true
            } catch (t: Throwable) {
                Logs.trace("write failed: $t")
                needsReset = true
                linkDead = true
                synchronized(ackLock) { ackLock.signalAll() }
                synchronized(batteryLock) { batteryLock.signalAll() }
                synchronized(lock) { lock.signalAll() }
                false
            }
        }

        /**
         * Send one command and wait for its answer.  Every MMI command is type
         * 0x5A and is answered by type 0x5B carrying the same race id and a status
         * byte.  Indications update state but never complete this ACK wait; TWS
         * battery explicitly waits for its indication afterwards.
         */
        private fun command(raceId: Int, payload: ByteArray?): AirohaRace.Frame? {
            val frame = sendAndWait(raceId, payload)
            if (frame == null) {
                Logs.trace("!! no answer for race=$raceId")
                needsReset = true
            }
            return frame
        }

        private fun sendAndWait(raceId: Int, payload: ByteArray?): AirohaRace.Frame? {
            val seq = attemptSeq
            synchronized(ackLock) {
                ack = null
                ackFor = raceId
                ackType = AirohaRace.TYPE_RESP
            }
            if (!write(AirohaRace.command(raceId, payload))) {
                clearAckWait()
                return null
            }
            val deadline = SystemClock.elapsedRealtime() + RACE_RESPONSE_TIMEOUT_MS
            synchronized(ackLock) {
                // A dead socket reports itself through linkDead; the full timeout
                // would only make a stale click slower to give up.
                while (ack == null && !linkDead && seq == attemptSeq
                    && SystemClock.elapsedRealtime() < deadline
                ) {
                    try {
                        ackLock.waitFor(200L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        ack = null
                        ackFor = -1
                        ackType = -1
                        return null
                    }
                }
                val answer = ack
                ack = null
                ackFor = -1
                ackType = -1
                return answer
            }
        }

        private fun clearAckWait() {
            synchronized(ackLock) {
                ack = null
                ackFor = -1
                ackType = -1
            }
        }

        /**
         * Every H4 packet, on the reader thread.  Acks wake the worker; the
         * unsolicited indications are decoded here because they carry state the
         * worker never asked for (outside-control changes made on the buds).
         */
        override fun onFrame(frame: AirohaRace.Frame) {
            if (!frame.isRace) return
            val type = frame.type
            val raceId = frame.raceId
            if (type == AirohaRace.TYPE_RESP || type == AirohaRace.TYPE_IND) {
                Logs.trace("rx ${AirohaRace.hex(frame.raw)}")
            }

            // Outside-control indication: mode, noise level, ambient level.
            if (raceId == AirohaRace.RID_GET_OUTSIDE && type == AirohaRace.TYPE_IND
                && frame.raw.size >= 10
            ) {
                onOutsideCtrl(frame.u8(7), frame.u8(8), frame.u8(9), true)
            } else if (raceId == AirohaRace.RID_GET_AGENT && type == AirohaRace.TYPE_RESP
                && frame.status == 0 && frame.raw.size >= 8 && frame.u8(7) <= 1
            ) {
                agentIsRight = frame.u8(7) == 1
                agentChannelKnown = true
                Logs.trace("rx agent channel=${if (agentIsRight) "right" else "left"}")
            } else if (raceId == AirohaRace.RID_GET_BATTERY
                && type == AirohaRace.TYPE_IND
                && frame.raw.size >= 9 && frame.status == 0
            ) {
                // Battery arrives as an indication (role, level) after the ack.
                val role = frame.u8(7)
                if (role <= 1 && agentChannelKnown) {
                    val side = if (agentIsRight) 1 - role else role
                    onBattery(side, frame.u8(8), role)
                } else {
                    Logs.trace("battery indication ignored: unknown role/channel role=$role")
                }
            } else if (raceId == AirohaRace.RID_GET_CRADLE && type == AirohaRace.TYPE_RESP
                && frame.status == 0 && frame.raw.size >= 8
            ) {
                onBattery(2, frame.u8(7), -1)
            }

            // Wake the worker only for the response type it requested.  TWS
            // battery is deliberately two-stage: its 0x5B ACK must not consume the
            // later 0x5D (role, level) indication.
            if (type == AirohaRace.TYPE_RESP) {
                synchronized(ackLock) {
                    if (ack == null && ackFor == raceId && ackType == type) {
                        ack = frame
                        ackLock.signalAll()
                    }
                }
            }
        }

        private fun onOutsideCtrl(mode: Int, noise: Int, ambient: Int, indication: Boolean) {
            if (noise in 1..100) ancLevel = noise.toByte()
            if (ambient in 1..100) ambientLevel = ambient.toByte()
            Logs.trace(
                "rx outsideCtrl mode=$mode anc=$ancLevel amb=$ambientLevel"
                        + (if (indication) " (ind)" else "")
            )
            Az100Hook.onAirohaMode(
                address,
                when (mode) {
                    CTRL_ANC.toInt() -> if (adaptiveOn) STATUS_ADAPTIVE else STATUS_ANC
                    CTRL_AMBIENT.toInt() -> STATUS_AMBIENT
                    else -> STATUS_OFF
                }
            )
        }

        private fun onBattery(side: Int, level: Int, role: Int) {
            if (side < 0 || side > 2) return
            var values = BATTERY[key]
            values = if (values == null) intArrayOf(-1, -1, -1, 4, 4, 4) else values.copyOf()
            values[side] = if (level <= 100) level else -1
            if (values[side] < 0) {
                values[side + 3] = 4
            } else if (values[side + 3] != 1) {
                values[side + 3] = 4
            }
            BATTERY[key] = values
            synchronized(batteryLock) {
                batteryUpdates++
                if (role >= 0 && role < batteryRoleUpdates.size) batteryRoleUpdates[role]++
                batteryLock.signalAll()
            }
            Logs.trace("rx battery side=$side level=$level")
            Az100Hook.onAirohaBattery(address, values)
        }

        /* ---------------- commands ---------------- */

        private fun applyMode(status: Int) {
            pendingMode = status
            // The panel offers 关闭 / 降噪 / 通透 only, and 自适应 may still be on
            // from the official app: leaving it running under a "降噪" label is
            // audible (the level keeps following the surroundings), so it is
            // cleared first.  Only the first command of a link pays for the extra
            // packet, while the flag is still unknown.
            if (adaptiveOn || !adaptiveKnown) {
                val answer = command(AirohaRace.RID_SET_ADAPTIVE, byteArrayOf(0))
                adaptiveOn = false
                adaptiveKnown = true
                if (answer == null) return
            }
            val mode = when (status) {
                STATUS_ANC -> CTRL_ANC
                STATUS_AMBIENT -> CTRL_AMBIENT
                else -> CTRL_OFF
            }
            Logs.trace("apply ctrl mode=$mode anc=$ancLevel amb=$ambientLevel")
            val answer = command(AirohaRace.RID_SET_OUTSIDE, byteArrayOf(mode, ancLevel, ambientLevel))
            if (answer == null) return
            val ackStatus = answer.status
            if (ackStatus != 0) {
                // Refused: leave the UI to the state the headset reports.
                Logs.trace("!! setOutsideCtrl rejected status=$ackStatus")
                pendingMode = -1
            } else {
                pendingMode = -1
                retries = 0
                // Stock does exactly this (OnSetOutsideCtrl -> getOutsideCtrl):
                // the read-back is what keeps levels and mode authoritative.
                val readBack = command(AirohaRace.RID_GET_OUTSIDE, null)
                if (readBack != null && readBack.status == 0 && readBack.raw.size >= 10) {
                    onOutsideCtrl(readBack.u8(7), readBack.u8(8), readBack.u8(9), false)
                }
            }
        }

        private fun queryState() {
            // Once per connection.  Outside control is what the volume tile labels
            // itself from; the three battery frames fill the settings cache.
            // Nothing here is repeated while the buds stay connected.
            val outside = command(AirohaRace.RID_GET_OUTSIDE, null) ?: return
            if (outside.status == 0 && outside.raw.size >= 10) {
                onOutsideCtrl(outside.u8(7), outside.u8(8), outside.u8(9), false)
            }
            queryBattery()
        }

        private fun queryBattery() {
            if (needsReset || linkDead) return
            // Official OnBattery maps role 0/1 through the current agent's channel.
            // Refresh it on each short-lived link because the AZ100 can change
            // agent when an earbud is returned to the case.
            val channel = command(AirohaRace.RID_GET_AGENT, null) ?: return
            if (!agentChannelKnown) {
                Logs.trace("!! no valid agent channel status=${channel.status}")
                return
            }
            if (!queryTwsBattery(0) && (needsReset || linkDead)) return
            if (!queryTwsBattery(1) && (needsReset || linkDead)) return
            command(AirohaRace.RID_GET_CRADLE, null)
        }

        /**
         * TWS battery uses an ACK followed by a separate indication.  Waiting for
         * only the ACK makes the worker close RFCOMM before the level is delivered.
         * The role counter also retains an indication received in the same RFCOMM
         * read as the ACK, before the worker starts this wait.
         */
        private fun queryTwsBattery(role: Int): Boolean {
            val seq = attemptSeq
            val startUpdates = synchronized(batteryLock) { batteryRoleUpdates[role] }
            val answer = command(AirohaRace.RID_GET_BATTERY, byteArrayOf(role.toByte()))
            if (answer == null || answer.status != 0) {
                if (answer != null) {
                    Logs.trace("battery ACK rejected role=$role status=${answer.status}")
                }
                return false
            }

            val deadline = SystemClock.elapsedRealtime() + BATTERY_INDICATION_TIMEOUT_MS
            synchronized(batteryLock) {
                while (batteryRoleUpdates[role] <= startUpdates && !linkDead
                    && seq == attemptSeq
                    && SystemClock.elapsedRealtime() < deadline
                ) {
                    try {
                        batteryLock.waitFor(200L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                val received = batteryRoleUpdates[role] > startUpdates
                if (!received) Logs.trace("!! no battery indication role=$role")
                return received
            }
        }

        private fun completeBatteryRefreshes(callbacks: List<PendingBatteryRefresh>?) {
            if (callbacks.isNullOrEmpty()) return
            val currentUpdates = batteryUpdates
            val values = cachedBattery(address)
            for (pending in callbacks) {
                val fresh = currentUpdates > pending.startUpdates
                try {
                    pending.callback.onComplete(if (fresh) values else null, fresh)
                } catch (t: Throwable) {
                    Logs.d("battery refresh callback failed: $t")
                }
            }
        }

        /* ---------------- misc ---------------- */

        private fun sleep(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
