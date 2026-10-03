package com.tosasitill.az100;

import android.app.AndroidAppHelper;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Airoha MMI link over plain RFCOMM, owned by the calling process.
 *
 * This is a full re-implementation of the slice of Audio Connect 4.4.0 that
 * the module needs; nothing here loads a single class, resource or byte of
 * {@code com.panasonic.technicsaudioconnect}.  The wire protocol was recovered
 * from that APK and is documented in {@code reverse/} and
 * {@link AirohaRace}:
 *
 * <pre>
 *   socket   BluetoothDevice.createRfcommSocketToServiceRecord(
 *               00000000-0000-0000-0099-AABBCCDDEEFF)      // Airoha SPP
 *   frames   H4: 05 5A &lt;len LE16&gt; &lt;race id LE16&gt; &lt;payload&gt;
 *   answer   05 5B &lt;len LE16&gt; &lt;race id LE16&gt; &lt;status&gt; ...
 *   notice   05 5D ...                                     // unsolicited
 * </pre>
 *
 * Sequence, copied from {@code DeviceMmiAiroha2}:
 * <ol>
 *   <li>open the RFCOMM socket; the stock stack starts its reader right after
 *       {@code connect()} and sends <em>nothing</em> -- there is no handshake;</li>
 *   <li>commands are RACE frames with type 0x5A, one at a time, each answered
 *       by the headset with type 0x5B and a status byte (0 = applied);</li>
 *   <li>the headset also pushes state changes (type 0x5D) -- outside control at
 *       race id 10, battery at 3286 -- which is the truth to mirror.</li>
 * </ol>
 *
 * Design notes kept from the previous (reflection based) version, because each
 * one was paid for with a visible bug:
 * <ul>
 *   <li>one command = one short lived link: the socket is opened when the user
 *       clicks and closed again right after.  Nothing keeps a socket open, so
 *       the two hooked processes (Melody + Device Space) can never fight over
 *       the single RFCOMM channel, and an idle module never pages the headset
 *       while it is being used by another device;</li>
 *   <li>exactly one command is ever in flight (the newest click wins);</li>
 *   <li>a read-only sync (mode + battery) runs at most once per connection.
 *       Later provider queries do not open SPP;</li>
 *   <li>no background redial: a failed or dropped link ends the attempt; the
 *       next attempt is the next click or the next connect;</li>
 *   <li>nothing is dialed unless the headset is confirmed connected to this
 *       phone.</li>
 * </ul>
 */
final class DirectAirohaController {

    interface BatteryRefreshCallback {
        void onComplete(int[] values, boolean receivedFreshSample);
    }

    private static final class PendingBatteryRefresh {
        final BatteryRefreshCallback callback;
        final long startUpdates;

        PendingBatteryRefresh(BatteryRefreshCallback callback, long startUpdates) {
            this.callback = callback;
            this.startUpdates = startUpdates;
        }
    }

    /** Airoha SPP service UUID (UuidTable.AIROHA_SPP_UUID). */
    private static final UUID SPP_UUID =
            UUID.fromString("00000000-0000-0000-0099-AABBCCDDEEFF");

    /** MMI outside-control modes (OutsideCtrl.getModeByte). */
    private static final byte CTRL_OFF = 0;
    private static final byte CTRL_ANC = 1;
    private static final byte CTRL_AMBIENT = 2;

    /** Status vocabulary shared with the rest of the module. */
    private static final int STATUS_OFF = 1;
    private static final int STATUS_ANC = 2;
    private static final int STATUS_AMBIENT = 3;
    /** Reported by the headset only; the panel no longer selects it. */
    private static final int STATUS_ADAPTIVE = 4;

    /** One SPP/RFCOMM connect attempt may take this long before we retry. */
    private static final long LINK_WAIT_MS = 10_000L;
    /** A connect that never reports back is dropped after this long. */
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    /** RACE response timeout used by Audio Connect's AirohaMmiMgr. */
    private static final int RACE_RESPONSE_TIMEOUT_MS = 6000;
    /** TWS battery data is a second, asynchronous packet after the ACK. */
    private static final int BATTERY_INDICATION_TIMEOUT_MS = 6000;
    /** Give the RFCOMM reader a short window to settle before the first write. */
    private static final long LINK_STABILIZE_MS = 100L;
    /** Bluetooth probe (binder) results are reused this long. */
    private static final long PRESENCE_TTL_MS = 15_000L;

    /**
     * Outside-control levels.  The headset validates them: Audio Connect sends
     * back exactly what the AZ100 reports (a fresh unit answers
     * {@code mode=1 anc=100 amb=100}), and a packet carrying the class defaults
     * (0 / 10) is rejected with status 1 -- i.e. silently ignored, which is why
     * the tiles looked dead.  Values are 0..100 and refreshed from every
     * {@code OnGetOutsideCtrl}.
     */
    private static final byte DEFAULT_ANC_LEVEL = 100;
    private static final byte DEFAULT_AMBIENT_LEVEL = 100;

    /** BluetoothProfile.LE_AUDIO; the AZ100 can also connect as LE Audio. */
    private static final int LE_AUDIO_PROFILE = 22;

    /** Audio profiles asked during a presence probe. */
    private static final int[] PROBE_PROFILES =
            {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, LE_AUDIO_PROFILE};

    private static final int LINK_IDLE = 0;
    private static final int LINK_CONNECTING = 1;
    private static final int LINK_READY = 2;

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, int[]> BATTERY = new ConcurrentHashMap<>();
    /** ACL state for the headset: 0 unknown, 1 connected, -1 gone. */
    private static volatile int aclPresent;

    /** Cached presence probes: confirmed / absent / unknown. */
    private static final int PROBE_CONNECTED = 1;
    private static final int PROBE_ABSENT = -1;
    private static final int PROBE_UNKNOWN = 0;

    private DirectAirohaController() {
    }

    /** Apply a mode (1 off / 2 ANC / 3 ambient) to the headset. */
    static void request(Context context, String address, int status) {
        Session session = session(context, address);
        if (session != null) session.request(normalizeStatus(status));
    }

    /**
     * Read mode and battery once while this connection lasts. Provider queries
     * and media-route callbacks may call this on every binder transaction;
     * only the first call that still sees the headset connected opens SPP.
     */
    static void syncOnce(Context context, String address) {
        if (aclPresent < 0) return;
        Session session = session(context, address);
        if (session != null) session.syncOnce();
    }

    /** Force a user-requested read without changing the once-per-connection sync latch. */
    static void refreshBattery(Context context, String address, BatteryRefreshCallback callback) {
        Session session = session(context, address);
        if (session == null) {
            if (callback != null) callback.onComplete(null, false);
            return;
        }
        session.refreshBattery(callback);
    }

    /** Drop the link (and the socket) when the headset goes away. */
    static void disconnect(Context context, String address, String reason) {
        Session session = session(context, address);
        if (session != null) session.release(reason);
    }

    /**
     * Presence as the session sees it: the ACL broadcast first, the one-time
     * profile probe second (cached).  The Melody provider answers queries before
     * any command was sent, so it needs this without owning a link.
     */
    static boolean reachable(Context context, String address) {
        Session session = session(context, address);
        return session != null && session.reachable();
    }

    /**
     * True when any headset audio profile is still connected. This uses the
     * public profile state API, unlike {@code BluetoothAdapter.getConnectedDevices(int)},
     * which this ROM does not expose. It only guards against treating our own
     * SPP teardown as the headset leaving.
     */
    static boolean audioProfileConnected(Context context) {
        BluetoothAdapter adapter = null;
        try {
            if (context != null) {
                BluetoothManager manager =
                        (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (manager != null) adapter = manager.getAdapter();
            }
            if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            Logs.trace("audio profile adapter failed: " + t);
            return false;
        }
        if (adapter == null) return false;
        int a2dp = profileState(adapter, BluetoothProfile.A2DP);
        int headset = profileState(adapter, BluetoothProfile.HEADSET);
        int leAudio = profileState(adapter, LE_AUDIO_PROFILE);
        Logs.trace("audio profiles a2dp=" + a2dp + " hfp=" + headset + " le=" + leAudio);
        return a2dp == BluetoothProfile.STATE_CONNECTED
                || headset == BluetoothProfile.STATE_CONNECTED
                || leAudio == BluetoothProfile.STATE_CONNECTED;
    }

    private static int profileState(BluetoothAdapter adapter, int profile) {
        try {
            return adapter.getProfileConnectionState(profile);
        } catch (Throwable t) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    static void setPresent(boolean present) {
        int value = present ? 1 : -1;
        aclPresent = value;
        // The buds appeared/disappeared: let every live session react at once
        // (a fresh connect must not wait out a backoff won while they were away).
        for (Session session : SESSIONS.values()) session.onAcl(value);
    }

    static int[] cachedBattery(String address) {
        int[] value = BATTERY.get(address == null ? "" : address.toUpperCase(Locale.ROOT));
        return value == null ? null : value.clone();
    }

    static void forget() {
        SESSIONS.clear();
    }

    /**
     * {@code Context.getApplicationContext()} is null while {@code attach()} is
     * still running, so the base context is kept until a better one appears.
     */
    private static Session session(Context context, String address) {
        if (address == null || address.isEmpty()) return null;
        String key = address.toUpperCase(Locale.ROOT);
        Session existing = SESSIONS.get(key);
        if (existing != null) return existing;
        Context app = context == null ? null : context.getApplicationContext();
        Session created = new Session(app != null ? app : context, address);
        Session previous = SESSIONS.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    private static int normalizeStatus(int status) {
        return status == STATUS_ANC || status == STATUS_AMBIENT ? status : STATUS_OFF;
    }

    /* ------------------------------------------------------------------ */
    /* session                                                             */
    /* ------------------------------------------------------------------ */

    private static final class Session implements Runnable, AirohaRace.Sink {

        private volatile Context context;
        private final String address;
        private final String key;

        private final Object lock = new Object();
        private final Object linkLock = new Object();

        /** Newest requested mode, -1 when nothing is pending. */
        private int wantedMode = -1;
        private boolean wantedQuery;
        private boolean wantedBatteryRefresh;
        private final List<PendingBatteryRefresh> pendingBatteryRefreshes = new ArrayList<>();
        private boolean workerStarted;

        private volatile int linkState = LINK_IDLE;
        private volatile long connectStartedAt;
        /** Set from the reader thread; the worker performs the actual teardown. */
        private volatile boolean needsReset;
        /** Set when the current socket dies, so a waiting command fails fast. */
        private volatile boolean linkDead;

        /** Open attempt state, written by the connect thread. */
        private volatile BluetoothSocket socket;
        private volatile boolean openerRunning;
        private volatile boolean openOk;
        private volatile Throwable openError;

        /** A frame the worker is waiting for: set by the reader, consumed once. */
        private volatile AirohaRace.Frame ack;
        private final Object ackLock = new Object();
        /** Which race id the ACK must belong to; -1 means no active waiter. */
        private volatile int ackFor = -1;
        /** Response type expected by the current ack waiter. */
        private volatile int ackType = -1;
        /** Battery indications have their own completion signal after the ACK. */
        private final Object batteryLock = new Object();
        private final long[] batteryRoleUpdates = new long[2];
        /** Wire roles are agent/partner, not fixed left/right channels. */
        private volatile boolean agentIsRight;
        private volatile boolean agentChannelKnown;
        /** Bumped on every teardown; a late connect() must not commit a socket. */
        private volatile int attemptSeq;

        private volatile byte ancLevel = DEFAULT_ANC_LEVEL;
        private volatile byte ambientLevel = DEFAULT_AMBIENT_LEVEL;
        private volatile boolean adaptiveOn;
        /** False until the headset itself reported the adaptive flag. */
        private volatile boolean adaptiveKnown;

        /**
         * Command that is still waiting for its ack.  A response timeout used to
         * lose the click (the link was rebuilt and the command was already
         * consumed); it is now re-queued after the reconnect.
         */
        private volatile int pendingMode = -1;
        private volatile int retries;

        /** Cached binder probe for the connected-to-this-phone question. */
        private volatile long probeAt;
        private volatile int probed;
        /** Last probe failure, kept only for the trace file. */
        private volatile String probeError;
        /** ACL hint this session last saw, to notice a fresh connect instantly. */
        private volatile int aclSeen;
        /** A read-only sync already ran (or was claimed) for the current connection. */
        private volatile boolean synced;
        private volatile long batteryUpdates;

        Session(Context context, String address) {
            this.context = context;
            this.address = address;
            this.key = address.toUpperCase(Locale.ROOT);
        }

        boolean reachable() {
            onAcl(aclPresent);
            // The volume panel gate: "unknown" still shows the row (the ACL
            // broadcast may predate this process), only an explicit disconnect
            // hides it.
            return connectionState() != PROBE_ABSENT;
        }

        /** ACL state changed (or was noticed late): forget the stale probe. */
        void onAcl(int acl) {
            if (acl != 0 && acl != aclSeen) {
                aclSeen = acl;
                probed = PROBE_UNKNOWN;
                probeAt = 0L;
                // A new connection may sync once. Going away must not keep the
                // latch, or the next connect would stay on the stale label.
                if (acl < 0) synced = false;
            }
        }

        /**
         * Open one short read-only link if the buds are confirmed connected and
         * this connection has not been synced yet. Safe to call from every
         * provider query: the latch makes the rest free.
         */
        void syncOnce() {
            if (synced) return;
            if (connectionState() != PROBE_CONNECTED) return;
            synchronized (lock) {
                if (synced) return;
                synced = true;
                wantedQuery = true;
                startWorker();
                lock.notifyAll();
            }
        }

        void refreshBattery(BatteryRefreshCallback callback) {
            synchronized (lock) {
                if (callback != null) {
                    pendingBatteryRefreshes.add(new PendingBatteryRefresh(callback, batteryUpdates));
                }
                wantedBatteryRefresh = true;
                startWorker();
                lock.notifyAll();
            }
        }

        /** The base context is fine; upgrade to the Application once it exists. */
        private Context contextForCodeLoading() {
            Context current = context;
            if (current != null) {
                Context app = current.getApplicationContext();
                if (app != null) {
                    context = app;
                    return app;
                }
                return current;
            }
            Context app = AndroidAppHelper.currentApplication();
            if (app != null) context = app;
            return app;
        }

        /* ---------------- public api ---------------- */

        void request(int status) {
            Logs.trace("request mode=" + status);
            synchronized (lock) {
                wantedMode = status;          // newest click wins
                retries = 0;                  // every click gets one fresh retry
                startWorker();
                lock.notifyAll();
            }
        }

        private void startWorker() {
            if (workerStarted) return;
            workerStarted = true;
            Thread worker = new Thread(this, "az100-airoha");
            worker.setDaemon(true);
            worker.start();
        }

        void release(String reason) {
            Logs.trace("release " + key + " reason=" + reason);
            List<PendingBatteryRefresh> cancelled;
            synchronized (lock) {
                wantedMode = -1;
                wantedQuery = false;
                wantedBatteryRefresh = false;
                cancelled = new ArrayList<>(pendingBatteryRefreshes);
                pendingBatteryRefreshes.clear();
            }
            teardown();
            completeBatteryRefreshes(cancelled);
        }

        /** Socket is useless: drop it. The worker exits until the next command. */
        private void teardown() {
            attemptSeq++;
            closeSocket();
            linkDead = true;
            clearAckWait();
            synchronized (ackLock) {
                ackLock.notifyAll();
            }
            synchronized (batteryLock) {
                batteryLock.notifyAll();
            }
            adaptiveOn = false;
            adaptiveKnown = false;
            agentChannelKnown = false;
            linkState = LINK_IDLE;
            synchronized (linkLock) {
                linkLock.notifyAll();
            }
        }

        private void closeSocket() {
            BluetoothSocket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable t) {
                    Logs.d("socket close failed", t);
                }
            }
        }

        /* ---------------- worker ---------------- */

        @Override public void run() {
            Logs.trace("worker start");
            try {
                loop();
            } catch (Throwable t) {
                Logs.trace("worker crashed: " + t);
                Logs.e("airoha worker crashed", t);
            } finally {
                synchronized (lock) {
                    workerStarted = false;
                    // Nothing may be left un-consumed: if the loop died with a
                    // command pending, restart instead of dropping clicks.
                    if (wantedMode >= 0 || wantedQuery || wantedBatteryRefresh) startWorker();
                }
            }
        }

        private void loop() {
            while (true) {
                if (needsReset) {
                    needsReset = false;
                    int again = pendingMode;
                    Logs.trace("link dropped mid-command");
                    teardown();
                    // The socket died while the command was in flight (the
                    // channel is shared by two module processes): retry once,
                    // then give up instead of redialing the headset forever.
                    if (again >= 0 && retries < 1 && connectionState() == PROBE_CONNECTED) {
                        retries++;
                        Logs.trace("retry command mode=" + again);
                        synchronized (lock) {
                            if (wantedMode < 0) wantedMode = again;
                        }
                    } else {
                        pendingMode = -1;
                    }
                }

                int mode = -1;
                boolean query = false;
                boolean batteryRefresh = false;
                List<PendingBatteryRefresh> refreshCallbacks = null;
                synchronized (lock) {
                    if (wantedMode < 0 && !wantedQuery && !wantedBatteryRefresh) {
                        // Idle: leave. Object.wait() would wake this process
                        // every minute for no work. The next click or sync
                        // starts a new daemon.
                        return;
                    }
                    if (wantedMode >= 0) {
                        mode = wantedMode;
                        wantedMode = -1;
                    }
                    if (wantedQuery) {
                        query = wantedQuery;
                        wantedQuery = false;
                    }
                    if (wantedBatteryRefresh) {
                        batteryRefresh = true;
                        wantedBatteryRefresh = false;
                        refreshCallbacks = new ArrayList<>(pendingBatteryRefreshes);
                        pendingBatteryRefreshes.clear();
                    }
                }

                // One command = one short lived link: close whatever a former
                // attempt left behind before deciding anything.
                teardown();

                try {
                    int state = connectionState();
                    if (mode >= 0 || batteryRefresh) {
                        // A click after an explicit disconnect is ignored on purpose:
                        // the headset is on another device and must not be paged.
                        if (state == PROBE_ABSENT) {
                            pendingMode = -1;
                            Logs.trace(batteryRefresh
                                    ? "battery refresh ignored, headset not connected"
                                    : "click ignored, headset not connected");
                            continue;
                        }
                    } else if (query && state != PROBE_CONNECTED) {
                        // Background battery refresh only runs on a confirmed live link.
                        Logs.trace("query skipped, headset not confirmed");
                        continue;
                    }

                    if (!ensureLink(mode >= 0 || batteryRefresh)) {
                        pendingMode = -1;
                        continue;
                    }

                    try {
                        if (mode >= 0) applyMode(mode);
                        // A sync claimed alongside a click still has to run: the
                        // click path does not ask for battery.
                        if (query) queryState();
                        else if (batteryRefresh) queryBattery();
                    } finally {
                        teardown();
                    }
                } finally {
                    completeBatteryRefreshes(refreshCallbacks);
                }
            }
        }

        /* ---------------- link ---------------- */

        private boolean ensureLink(boolean userInitiated) {
            if (linkState == LINK_READY) return true;
            // One strict gate before any RFCOMM work.  Explicitly gone means
            // never dial (the user has the headset on another device); unknown
            // allows the single attempt that follows a real user click.
            int state = connectionState();
            if (state == PROBE_ABSENT) {
                Logs.trace("not connected, skip connect");
                return false;
            }
            if (state != PROBE_CONNECTED && !userInitiated) {
                Logs.trace("link unconfirmed, background path skipped");
                return false;
            }

            long now = SystemClock.elapsedRealtime();
            if (linkState == LINK_CONNECTING && now - connectStartedAt > CONNECT_TIMEOUT_MS) {
                Logs.trace("connect watchdog fired");
                markFailed("connect watchdog");
                teardown();
                return false;
            }

            if (linkState != LINK_CONNECTING) {
                linkState = LINK_CONNECTING;
                connectStartedAt = now;
                openLink();
            }
            if (linkState == LINK_CONNECTING) {
                synchronized (linkLock) {
                    long deadline = SystemClock.elapsedRealtime() + LINK_WAIT_MS;
                    while (linkState == LINK_CONNECTING
                            && SystemClock.elapsedRealtime() < deadline) {
                        try {
                            linkLock.wait(500L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }
            return linkState == LINK_READY;
        }

        private void markReady() {
            Logs.trace("link ready");
            linkState = LINK_READY;
            synchronized (linkLock) {
                linkLock.notifyAll();
            }
            Logs.i("airoha link ready for " + key);
        }

        private void markFailed(String reason) {
            Logs.trace("link failed: " + reason);
            if (linkState != LINK_READY) linkState = LINK_IDLE;
            synchronized (linkLock) {
                linkLock.notifyAll();
            }
            Logs.d("airoha link attempt failed: " + reason);
        }

        /**
         * ACL broadcast first, cached binder probe second:
         * {@code PROBE_CONNECTED} / {@code PROBE_ABSENT} / {@code PROBE_UNKNOWN}.
         * Only an explicit "gone" forbids talking to the headset; "unknown"
         * merely forbids the background paths -- a user click may still make a
         * single attempt (the ACL broadcast may predate this process).
         */
        private int connectionState() {
            int known = aclPresent;
            if (known != 0) return known;
            long now = SystemClock.elapsedRealtime();
            if (now - probeAt < PRESENCE_TTL_MS) return probed;
            probeAt = now;
            probed = PROBE_UNKNOWN;
            Context base = contextForCodeLoading();
            if (base == null) return probed;
            probeError = null;
            boolean answered = false;
            boolean found = false;
            // BluetoothAdapter first: on this build the BluetoothManager
            // wrapper rejects the audio profiles with "Profile not supported",
            // which must not be read as "device absent".
            BluetoothAdapter adapter = null;
            try {
                adapter = BluetoothAdapter.getDefaultAdapter();
            } catch (Throwable t) {
                probeError = "adapter: " + t;
            }
            if (adapter != null) {
                for (int profile : PROBE_PROFILES) {
                    int state = probe(adapter, profile);
                    if (state >= 0) answered = true;
                    if (state > 0) found = true;
                }
            }
            BluetoothManager manager = null;
            if (!answered) {
                try {
                    manager = (BluetoothManager) base.getSystemService(Context.BLUETOOTH_SERVICE);
                } catch (Throwable t) {
                    probeError = "manager: " + t;
                }
                if (manager != null) {
                    for (int profile : PROBE_PROFILES) {
                        int state = probe(manager, profile);
                        if (state >= 0) answered = true;
                        if (state > 0) found = true;
                    }
                }
            }
            probed = answered ? (found ? PROBE_CONNECTED : PROBE_ABSENT) : PROBE_UNKNOWN;
            if (!answered) {
                Logs.trace("probe unanswered (" + probeError + ")");
            } else {
                Logs.trace("probe answered=" + answered + " connected=" + found);
            }
            return probed;
        }

        /** -1 profile unsupported, 0 not connected, 1 this headset connected. */
        @SuppressWarnings("unchecked")
        private int probe(BluetoothAdapter adapter, int profile) {
            try {
                // BluetoothAdapter.getConnectedDevices(int) is a hidden API;
                // reflect so the module keeps building against the public SDK.
                Object result = adapter.getClass()
                        .getMethod("getConnectedDevices", int.class)
                        .invoke(adapter, Integer.valueOf(profile));
                return matches((List<BluetoothDevice>) result);
            } catch (Throwable t) {
                if (probeError == null) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    probeError = "p" + profile + ": " + cause;
                }
                return -1;
            }
        }

        /** -1 profile unsupported, 0 not connected, 1 this headset connected. */
        private int probe(BluetoothManager manager, int profile) {
            try {
                return matches(manager.getConnectedDevices(profile));
            } catch (Throwable t) {
                if (probeError == null) probeError = "m" + profile + ": " + t;
                return -1;
            }
        }

        private int matches(List<BluetoothDevice> devices) {
            if (devices == null) return 0;
            for (BluetoothDevice device : devices) {
                if (address.equalsIgnoreCase(device.getAddress())) return 1;
            }
            return 0;
        }

        /**
         * Open RFCOMM off the worker thread (connect can block for tens of
         * seconds) and start the H4 reader as soon as the socket is up -- which
         * is exactly what the stock {@code SppController} does, minus the
         * teardown that used to make every click pay for a fresh connect.
         */
        private void openLink() {
            Logs.trace("connecting");
            synchronized (linkLock) {
                if (openerRunning) return;
                openerRunning = true;
                openOk = false;
                openError = null;
            }
            final int seq = attemptSeq;
            Thread opener = new Thread(new Runnable() {
                @Override public void run() {
                    BluetoothSocket opened = null;
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter == null || !adapter.isEnabled()) {
                            throw new IOException("bluetooth disabled");
                        }
                        BluetoothDevice device = adapter.getRemoteDevice(address);
                        opened = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        opened.connect();
                        if (seq != attemptSeq) {
                            // A watchdog already gave up on this attempt.
                            throw new IOException("attempt superseded");
                        }
                        socket = opened;
                        linkDead = false;
                        startReader(opened);
                        // Start reading before the first write.  Keep a small,
                        // bounded settling window without inventing a protocol
                        // handshake that Audio Connect never sends.
                        sleep(LINK_STABILIZE_MS);
                        if (seq != attemptSeq || opened != socket || linkDead) {
                            throw new IOException("link ended before ready");
                        }
                        synchronized (linkLock) {
                            openOk = true;
                            openerRunning = false;
                            linkLock.notifyAll();
                        }
                        markReady();
                        return;
                    } catch (Throwable t) {
                        if (opened != null) {
                            try {
                                opened.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        synchronized (linkLock) {
                            openError = t;
                            openerRunning = false;
                            linkLock.notifyAll();
                        }
                    }
                }
            }, "az100-spp-open");
            opener.setDaemon(true);
            opener.start();

            long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
            synchronized (linkLock) {
                while (openerRunning && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        linkLock.wait(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (openerRunning) {
                Logs.trace("open timed out, aborting socket");
                // Invalidate the in-flight attempt so a late connect() cannot
                // commit a socket the worker has already given up on.
                attemptSeq++;
                closeSocket();
                synchronized (linkLock) {
                    openerRunning = false;
                }
                markFailed("open timeout");
            } else if (!openOk) {
                markFailed("open: " + openError);
            }
        }

        private void startReader(final BluetoothSocket current) {
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] chunk = new byte[2000];
                    AirohaRace.Parser parser = new AirohaRace.Parser();
                    AirohaRace.Sink sink = frame -> {
                        if (current == socket) Session.this.onFrame(frame);
                    };
                    try {
                        InputStream in = current.getInputStream();
                        while (current == socket) {
                            int n = in.read(chunk);
                            // -1 is EOF. 0 should not happen for a positive
                            // buffer, but spinning on it would peg a core.
                            if (n <= 0 || current != socket) break;
                            Logs.trace("rx raw " + AirohaRace.hex(slice(chunk, n)));
                            parser.feed(chunk, 0, n, sink);
                        }
                    } catch (Throwable t) {
                        Logs.trace("reader stopped: " + t);
                    } finally {
                        if (current == socket) {
                            // The socket died (the channel is shared by the two
                            // hooked processes): wake the worker and let it
                            // decide whether one retry is still allowed.
                            needsReset = true;
                            linkDead = true;
                            markFailed("reader ended");
                            synchronized (ackLock) {
                                ackLock.notifyAll();
                            }
                            synchronized (batteryLock) {
                                batteryLock.notifyAll();
                            }
                            synchronized (lock) {
                                lock.notifyAll();
                            }
                        }
                    }
                }
            }, "az100-spp-read");
            reader.setDaemon(true);
            reader.start();
        }

        /* ---------------- frames ---------------- */

        private boolean write(byte[] frame) {
            BluetoothSocket current = socket;
            if (current == null) return false;
            try {
                OutputStream out = current.getOutputStream();
                out.write(frame);
                out.flush();
                Logs.trace("tx " + AirohaRace.hex(frame));
                return true;
            } catch (Throwable t) {
                Logs.trace("write failed: " + t);
                needsReset = true;
                linkDead = true;
                synchronized (ackLock) {
                    ackLock.notifyAll();
                }
                synchronized (batteryLock) {
                    batteryLock.notifyAll();
                }
                synchronized (lock) {
                    lock.notifyAll();
                }
                return false;
            }
        }

        /**
         * Send one command and wait for its answer.  Every MMI command is type
         * 0x5A and is answered by type 0x5B carrying the same race id and a
         * status byte.  Indications update state but never complete this ACK
         * wait; TWS battery explicitly waits for its indication afterwards.
         */
        private AirohaRace.Frame command(int raceId, byte[] payload) {
            AirohaRace.Frame frame = sendAndWait(raceId, payload);
            if (frame == null) {
                Logs.trace("!! no answer for race=" + raceId);
                needsReset = true;
            }
            return frame;
        }

        private AirohaRace.Frame sendAndWait(int raceId, byte[] payload) {
            final int seq = attemptSeq;
            synchronized (ackLock) {
                ack = null;
                ackFor = raceId;
                ackType = AirohaRace.TYPE_RESP;
            }
            if (!write(AirohaRace.command(raceId, payload))) {
                clearAckWait();
                return null;
            }
            long deadline = SystemClock.elapsedRealtime() + RACE_RESPONSE_TIMEOUT_MS;
            synchronized (ackLock) {
                // A dead socket reports itself through linkDead; the full
                // timeout would only make a stale click slower to give up.
                while (ack == null && !linkDead && seq == attemptSeq
                        && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        ackLock.wait(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ack = null;
                        ackFor = -1;
                        ackType = -1;
                        return null;
                    }
                }
                AirohaRace.Frame answer = ack;
                ack = null;
                ackFor = -1;
                ackType = -1;
                return answer;
            }
        }

        private void clearAckWait() {
            synchronized (ackLock) {
                ack = null;
                ackFor = -1;
                ackType = -1;
            }
        }

        /**
         * Every H4 packet, on the reader thread.  Acks wake the worker; the
         * unsolicited indications are decoded here because they carry state the
         * worker never asked for (outside-control changes made on the buds).
         */
        @Override public void onFrame(AirohaRace.Frame frame) {
            if (!frame.race()) return;
            int type = frame.type();
            int raceId = frame.raceId();
            if (type == AirohaRace.TYPE_RESP || type == AirohaRace.TYPE_IND) {
                Logs.trace("rx " + AirohaRace.hex(frame.raw));
            }

            // Outside-control indication: mode, noise level, ambient level.
            if (raceId == AirohaRace.RID_GET_OUTSIDE && type == AirohaRace.TYPE_IND
                    && frame.raw.length >= 10) {
                onOutsideCtrl(frame.u8(7), frame.u8(8), frame.u8(9), true);
            } else if (raceId == AirohaRace.RID_GET_AGENT && type == AirohaRace.TYPE_RESP
                    && frame.status() == 0 && frame.raw.length >= 8
                    && frame.u8(7) <= 1) {
                agentIsRight = frame.u8(7) == 1;
                agentChannelKnown = true;
                Logs.trace("rx agent channel=" + (agentIsRight ? "right" : "left"));
            } else if (raceId == AirohaRace.RID_GET_BATTERY
                    && type == AirohaRace.TYPE_IND
                    && frame.raw.length >= 9 && frame.status() == 0) {
                // Battery arrives as an indication (role, level) after the ack.
                int role = frame.u8(7);
                if (role <= 1 && agentChannelKnown) {
                    int side = agentIsRight ? 1 - role : role;
                    onBattery(side, frame.u8(8), role);
                } else {
                    Logs.trace("battery indication ignored: unknown role/channel role=" + role);
                }
            } else if (raceId == AirohaRace.RID_GET_CRADLE && type == AirohaRace.TYPE_RESP
                    && frame.status() == 0 && frame.raw.length >= 8) {
                onBattery(2, frame.u8(7), -1);
            }

            // Wake the worker only for the response type it requested.  TWS
            // battery is deliberately two-stage: its 0x5B ACK must not consume
            // the later 0x5D (role, level) indication.
            if (type == AirohaRace.TYPE_RESP) {
                synchronized (ackLock) {
                    if (ack == null && ackFor == raceId && ackType == type) {
                        ack = frame;
                        ackLock.notifyAll();
                    }
                }
            }
        }

        private void onOutsideCtrl(int mode, int noise, int ambient, boolean indication) {
            if (noise >= 1 && noise <= 100) ancLevel = (byte) noise;
            if (ambient >= 1 && ambient <= 100) ambientLevel = (byte) ambient;
            Logs.trace("rx outsideCtrl mode=" + mode + " anc=" + ancLevel
                    + " amb=" + ambientLevel + (indication ? " (ind)" : ""));
            Az100Hook.onAirohaMode(address,
                    mode == CTRL_ANC ? (adaptiveOn ? STATUS_ADAPTIVE : STATUS_ANC)
                            : mode == CTRL_AMBIENT ? STATUS_AMBIENT : STATUS_OFF);
        }

        private void onBattery(int side, int level, int role) {
            if (side < 0 || side > 2) return;
            int[] values = BATTERY.get(key);
            if (values == null) values = new int[]{-1, -1, -1, 4, 4, 4};
            else values = values.clone();
            values[side] = level <= 100 ? level : -1;
            if (values[side] < 0) values[side + 3] = 4;
            else if (values[side + 3] != 1) values[side + 3] = 4;
            BATTERY.put(key, values);
            synchronized (batteryLock) {
                batteryUpdates++;
                if (role >= 0 && role < batteryRoleUpdates.length) batteryRoleUpdates[role]++;
                batteryLock.notifyAll();
            }
            Logs.trace("rx battery side=" + side + " level=" + level);
            Az100Hook.onAirohaBattery(address, values);
        }

        /* ---------------- commands ---------------- */

        private void applyMode(int status) {
            pendingMode = status;
            // The panel offers 关闭 / 降噪 / 通透 only, and 自适应 may still be
            // on from the official app: leaving it running under a "降噪" label
            // is audible (the level keeps following the surroundings), so it is
            // cleared first.  Only the first command of a link pays for the
            // extra packet, while the flag is still unknown.
            if (adaptiveOn || !adaptiveKnown) {
                AirohaRace.Frame answer = command(AirohaRace.RID_SET_ADAPTIVE, new byte[]{0});
                adaptiveOn = false;
                adaptiveKnown = true;
                if (answer == null) return;
            }
            byte mode = status == STATUS_ANC ? CTRL_ANC
                    : status == STATUS_AMBIENT ? CTRL_AMBIENT : CTRL_OFF;
            Logs.trace("apply ctrl mode=" + mode + " anc=" + ancLevel + " amb=" + ambientLevel);
            AirohaRace.Frame answer = command(AirohaRace.RID_SET_OUTSIDE,
                    new byte[]{mode, ancLevel, ambientLevel});
            if (answer == null) return;
            int ackStatus = answer.status();
            if (ackStatus != 0) {
                // Refused: leave the UI to the state the headset reports.
                Logs.trace("!! setOutsideCtrl rejected status=" + ackStatus);
                pendingMode = -1;
            } else {
                pendingMode = -1;
                retries = 0;
                // Stock does exactly this (OnSetOutsideCtrl -> getOutsideCtrl):
                // the read-back is what keeps levels and mode authoritative.
                AirohaRace.Frame readBack = command(AirohaRace.RID_GET_OUTSIDE, null);
                if (readBack != null && readBack.status() == 0 && readBack.raw.length >= 10) {
                    onOutsideCtrl(readBack.u8(7), readBack.u8(8), readBack.u8(9), false);
                }
            }
        }

        private void queryState() {
            // Once per connection. Outside control is what the volume tile
            // labels itself from; the three battery frames fill the settings
            // cache. Nothing here is repeated while the buds stay connected.
            AirohaRace.Frame outside = command(AirohaRace.RID_GET_OUTSIDE, null);
            if (outside == null) return;
            if (outside != null && outside.status() == 0 && outside.raw.length >= 10) {
                onOutsideCtrl(outside.u8(7), outside.u8(8), outside.u8(9), false);
            }
            queryBattery();
        }

        private void queryBattery() {
            if (needsReset || linkDead) return;
            // Official OnBattery maps role 0/1 through the current agent's
            // channel.  Refresh it on each short-lived link because the AZ100
            // can change agent when an earbud is returned to the case.
            AirohaRace.Frame channel = command(AirohaRace.RID_GET_AGENT, null);
            if (channel == null) return;
            if (!agentChannelKnown) {
                Logs.trace("!! no valid agent channel status=" + channel.status());
                return;
            }
            if (!queryTwsBattery(0) && (needsReset || linkDead)) return;
            if (!queryTwsBattery(1) && (needsReset || linkDead)) return;
            command(AirohaRace.RID_GET_CRADLE, null);
        }

        /**
         * TWS battery uses an ACK followed by a separate indication.  Waiting
         * for only the ACK makes the worker close RFCOMM before the level is
         * delivered.  The role counter also retains an indication received in
         * the same RFCOMM read as the ACK, before the worker starts this wait.
         */
        private boolean queryTwsBattery(int role) {
            final int seq = attemptSeq;
            long startUpdates;
            synchronized (batteryLock) {
                startUpdates = batteryRoleUpdates[role];
            }
            AirohaRace.Frame answer = command(AirohaRace.RID_GET_BATTERY,
                    new byte[]{(byte) role});
            if (answer == null || answer.status() != 0) {
                if (answer != null) {
                    Logs.trace("battery ACK rejected role=" + role
                            + " status=" + answer.status());
                }
                return false;
            }

            long deadline = SystemClock.elapsedRealtime() + BATTERY_INDICATION_TIMEOUT_MS;
            synchronized (batteryLock) {
                while (batteryRoleUpdates[role] <= startUpdates && !linkDead
                        && seq == attemptSeq
                        && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        batteryLock.wait(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                boolean received = batteryRoleUpdates[role] > startUpdates;
                if (!received) Logs.trace("!! no battery indication role=" + role);
                return received;
            }
        }

        private void completeBatteryRefreshes(List<PendingBatteryRefresh> callbacks) {
            if (callbacks == null || callbacks.isEmpty()) return;
            long currentUpdates = batteryUpdates;
            int[] values = cachedBattery(address);
            for (PendingBatteryRefresh pending : callbacks) {
                boolean fresh = currentUpdates > pending.startUpdates;
                try {
                    pending.callback.onComplete(fresh ? values : null, fresh);
                } catch (Throwable t) {
                    Logs.d("battery refresh callback failed: " + t);
                }
            }
        }

        /* ---------------- misc ---------------- */

        private static byte[] slice(byte[] source, int count) {
            byte[] copy = new byte[count];
            System.arraycopy(source, 0, copy, 0, count);
            return copy;
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
