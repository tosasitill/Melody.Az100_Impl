package com.tosasitill.az100;

import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes OPPO's "dual mode" policy treat the AZ100 as an LE-Audio-coexistent
 * device, so the vendor code stops forbidding A2DP and HFP.
 *
 * <h2>What goes wrong without this</h2>
 *
 * Once {@link NativeLeaDeviceHook} lets {@code 0000184E} through, the vendor
 * stack starts treating the AZ100 as a real LE Audio peer.  That routes it into
 * {@code com.oplus.bluetooth.leaudio.OplusLeAudioPhonePolicyExt}, whose whole
 * design assumes the earbud can run classic and LE audio <em>at the same
 * time</em>:
 *
 * <pre>
 *   // OplusLeAudioPhonePolicyExt.oplusInitProfilePriorities()
 *   if (leaPolicy == 100) {
 *       handleLeAudioAllowedScenario(device, uuids, isSupportDualConnection, ...);
 *   }
 *
 *   private void handleLeAudioAllowedScenario(...) {
 *       if (!isSupportDualConnection) {
 *           Log.d(TAG, "setting peer hfp and a2dp to forbidden for not lea coexistence device");
 *           setProfilePolicy(device, 1, 0);      // hfp  -&gt; FORBIDDEN
 *           setProfilePolicy(device, 2, 0);      // a2dp -&gt; FORBIDDEN
 *           return;
 *       }
 *       ...
 *   }
 * </pre>
 *
 * and the same {@code !isSupportDualConnection} test appears in
 * {@code OplusAdapterServiceExtImpl.connectEnabledProfiles()} (which zeroes
 * both policies again) and in
 * {@code OplusLeAudioPhonePolicyExt.oplusLeAudioProfileStateChanged()}.
 *
 * <h2>Why it is false for this headset</h2>
 *
 * {@code isSupportDualConnection} is not a capability query.  It is a lookup in
 * a {@code SharedPreferences} file:
 *
 * <pre>
 *   // OplusDualModeManagerImpl.isSupportDualConnectionInternal()
 *   boolean isLeaCoexistenceSupport =
 *           this.mDualModeSupportPrefs.getBoolean(device.getAddress(), false);
 * </pre>
 *
 * and the only writer is
 * {@code OplusDualModeManagerImpl.notifyDualModeSupportInternal()}, fed from
 * {@code OplusBluetoothSmartAudioServer.onRemoteSmartAudioFeatureCallback()}:
 *
 * <pre>
 *   boolean isLeaCoexistenceSupported = (16384 &amp; remoteFeature) != 0;   // bit 14
 *   dualModeManager.notifyDualModeSupport(device, isLeaCoexistenceSupported);
 * </pre>
 *
 * {@code remoteFeature} is a bitmask the phone reads back over OPPO's
 * proprietary "Echo" / SmartAudio handshake -- a private protocol only OPPO
 * earbuds answer.  A third-party headset never replies, so the bit is never
 * seen, so the entry is never written, so the default {@code false} sticks
 * forever.  Verified on device: the AZ100's entry does not exist
 *
 * <pre>
 *   /data/user_de/0/com.android.bluetooth/shared_prefs/dual_mode_support.xml
 *   &lt;?xml version='1.0' encoding='utf-8' standalone='yes' ?&gt;
 *   &lt;map /&gt;
 * </pre>
 *
 * and the native side says the same thing in its own words:
 *
 * <pre>
 *   btif_smart_audio: update_device_connection_state_changed:
 *       remote_addr = xx:xx:xx:xx:84:85, connect = false, is_oplus_buds false
 *   oplus_bta_echo_api: oplus_bta_echo_notify_acl_status error:
 *       can't find l2cap info
 * </pre>
 *
 * <h2>The resulting deadlock</h2>
 *
 * {@code a2dp = 0} and {@code hfp = 0} mean no classic profile may connect.
 * LE audio is then supposed to be the only link, but it never establishes, so
 * nothing usable is connected at all -- the earbud gives up and drops the ACL
 * ({@code reason=19 REMOTE_USER_TERMINATED_CONNECTION}), the phone reconnects,
 * and the cycle repeats.
 *
 * <p>It cannot recover on its own either.  The branch that would restore the
 * two policies is guarded by the very state it needs to fix:
 *
 * <pre>
 *   boolean hfpAllowed  = ... getConnectionPolicy(device) == 100;   // false, it is 0
 *   boolean a2dpAllowed = ... getConnectionPolicy(device) == 100;   // false, it is 0
 *   if ((!hfpAllowed &amp;&amp; !a2dpAllowed) || ...) { }   // -&gt; always bails out
 * </pre>
 *
 * <h2>The fix</h2>
 *
 * Three hooks, all on classes in
 * {@code /system_ext/framework/oplus_bluetooth_common_ext.jar}:
 *
 * <ol>
 *   <li>{@code OplusDualModeManagerImpl.isSupportDualConnection(BluetoothDevice)}
 *       -- answer {@code true} for the AZ100.  Every {@code !isSupportDualConnection}
 *       guard above flips, so the vendor code stops writing {@code 0}.</li>
 *   <li>{@code OplusLeAudioPhonePolicyExt.oplusInitProfilePriorities(...)} --
 *       restore {@code hfp}/{@code a2dp} to {@code 100} <em>before</em> the
 *       method reads them, which is what un-sticks the deadlock described
 *       above.  Doing it inside the {@code isSupportDualConnection} hook would
 *       be too late: the {@code hfpAllowed}/{@code a2dpAllowed} reads happen
 *       first.</li>
 *   <li>{@code OplusDualModeManagerImpl.isDualConnectionNeeded(BluetoothDevice)}
 *       -- answer {@code true} as well.  This is the second half of the story
 *       and the reason LE Audio still would not come up after hooks 1 and 2.</li>
 * </ol>
 *
 * <h2>Why hook 3 is required for LE Audio to connect at all</h2>
 *
 * Turning {@code isSupportDualConnection} on is not enough: it makes the vendor
 * code <em>skip</em> the LE Audio connect instead of performing it.
 *
 * <pre>
 *   // OplusAdapterServiceExtImpl.oplusConnectEnabledProfiles()
 *   if (leAudioService != null &amp;&amp; isProfileSupported(device, 22)
 *           &amp;&amp; leAudioService.getConnectionPolicy(device) &gt; 0) {
 *       if (isSupportDualConnection &amp;&amp; !isDualConnectionNeeded) {
 *           Log.d(TAG, "connectEnabledProfiles: dual mode is supported but not needed, return false");
 *       } else {
 *           leAudioService.connect(device);
 *       }
 *   }
 * </pre>
 *
 * That is OPPO's actual design: an earbud that supports LEA coexistence keeps
 * classic audio as the default link, and LE Audio is only brought up on demand
 * -- when {@code isDualConnectionNeeded()} says so.  For OPPO's own earbuds that
 * flips on when a low-latency game is detected:
 *
 * <pre>
 *   // OplusDualModeManagerImpl.isDualConnectionNeeded()
 *   boolean prelim = needConnectionByPolicy &amp;&amp; supportDual &amp;&amp; preference;
 *   if (prelim &amp;&amp; hasLowLatencyPackage()) hasLowLatency = true;
 *   return hasLowLatency;
 * </pre>
 *
 * so on this headset it is permanently {@code false}, and LE Audio would never
 * be connected on any path -- {@code oplusProcessDeviceConnected()},
 * {@code onRemoteUuidsUpdated()} and {@code onLowLatencyPackageChanged()} are
 * all gated on the same flag.  Forcing it to {@code true} selects the vendor's
 * own "dual connection" mode: both links come up and
 * {@code oplusOnLeAudioDevicesAdded()} hands the active-device slot to LE Audio
 * once it is streaming.
 *
 * <p>Every other use of {@code isDualConnectionNeeded()} in the vendor jar is
 * likewise "if needed, connect / keep LE Audio", so answering {@code true} only
 * ever pushes towards LE Audio being up.
 *
 * <p>The headset is then a normal dual-mode device: classic audio works, and LE
 * audio comes up alongside it and takes over when it becomes active -- which is
 * exactly what {@code OplusLeAudioPhonePolicyExt.oplusOnLeAudioDevicesAdded()}
 * is written to do.
 *
 * <h2>Why the install is deferred</h2>
 *
 * Both classes live in a separate dex that is attached lazily:
 *
 * <pre>
 *   // OplusBTFactory.newInstance()
 *   ((BaseDexClassLoader) classLoader).addDexPath(
 *           "/system_ext/framework/oplus_bluetooth_common_ext.jar");
 *   return Class.forName(str2, false, classLoader)...
 * </pre>
 *
 * so at {@code handleLoadPackage()} time they cannot be resolved yet.
 * {@link #install} therefore hooks {@code OplusBTFactory.newInstance} and
 * retries once it has run.
 *
 * <h2>How to tell whether it did anything</h2>
 *
 * <pre>
 * adb logcat -s AZ100:V | grep dual-coexist
 * adb shell su -c "cat /data/misc/bluetooth/az100.log"
 * adb shell dumpsys bluetooth_manager | grep -A 6 "Connection Policy"
 * </pre>
 *
 * Expected: {@code dual-coexist: ... marked LEA-coexistent} and
 * {@code a2dp: ALLOWED} / {@code hfp: ALLOWED} instead of {@code FORBIDDEN}.
 */
final class OplusDualModeCoexistHook {

    /** Flip to false and reinstall to leave the vendor policy alone. */
    private static final boolean ENABLED = true;

    private static final String FACTORY =
            "com.oplus.bluetooth.common.OplusBTFactory";
    private static final String DUAL_MODE_IMPL =
            "com.oplus.bluetooth.btservice.OplusDualModeManagerImpl";
    private static final String LEA_POLICY_EXT =
            "com.oplus.bluetooth.leaudio.OplusLeAudioPhonePolicyExt";
    private static final String ADM_EXT_IMPL =
            "com.oplus.bluetooth.btservice.OplusActiveDeviceManagerExtImpl";
    private static final String ADAPTER_SERVICE_EXT_IMPL =
            "com.oplus.bluetooth.btservice.OplusAdapterServiceExtImpl";
    private static final String LEA_UTILS =
            "com.oplus.bluetooth.leaudio.OplusLeAudioUtils";
    private static final String ACTIVE_DEVICE_MANAGER =
            "com.android.bluetooth.btservice.ActiveDeviceManager";
    private static final String LE_AUDIO_SERVICE =
            "com.android.bluetooth.le_audio.LeAudioService";
    private static final String A2DP_SERVICE =
            "com.android.bluetooth.a2dp.A2dpService";
    private static final String CONNECTABLE_PROFILE =
            "com.android.bluetooth.profile.ConnectableProfile";
    private static final String HEADSET_SERVICE =
            "com.android.bluetooth.hfp.HeadsetService";

    /**
     * {@code A2dpService.connect(BluetoothDevice)} -- the public method, not a
     * javac lambda name.
     *
     * <h2>Measured failure of the previous choice (2026-10-13)</h2>
     *
     * <p>This used to be {@code "lambda$retriggerContactDownload$0"}, on the
     * assumption recorded here that javac lambda names are "stable across builds
     * of the same source".  On the shipping ROM that assumption is false.  The
     * method does not exist on {@code A2dpService} at all, and the first real
     * disconnect after LE Audio had taken over produced:
     *
     * <pre>
     * 12:25:26.417 dual-coexist: groupSwitch.apiRequest
     * 12:25:26.841 dual-coexist: A2DP reconnect failed -
     *              java.lang.NoSuchMethodError: com.android.bluetooth.a2dp.A2dpService
     *              #lambda$retriggerContactDownload$0[class android.bluetooth.BluetoothDevice]
     * </pre>
     *
     * <p>and {@code A2dpStateMachine} stayed at
     * {@code mConnectionState: STATE_DISCONNECTED, mLastConnectionState: STATE_DISCONNECTING}
     * with {@code getConnectionPolicy: 100} -- i.e. allowed, but nothing ever
     * asked it to connect.  LDAC did not come back until the headset was
     * reconnected by hand.
     *
     * <p>{@code connect(BluetoothDevice)} is declared public on this exact
     * build (verified by decompiling {@code /system/app/Bluetooth/Bluetooth.apk}):
     *
     * <pre>
     *   public boolean connect(BluetoothDevice device) {
     *       if (okToConnect(device)) { ... getOrCreateStateMachine(device).sendMessage(1); }
     *   }
     * </pre>
     *
     * <p>It is the same funnel {@code A2dpServiceBinder.connect()} reaches, and
     * the {@code okToConnect} it calls is the very method
     * {@link #hookA2dpActiveGuard} already hooks -- so the two stay consistent.
     */
    private static final String A2DP_CONNECT = "connect";

    /**
     * The address whose A2DP this class is holding down for {@code device}.
     *
     * <p>An LE Audio group is keyed by whichever identity the stack used to
     * activate it, and for this headset that is the <em>LE-only</em> earbud:
     *
     * <pre>
     *   XX:XX:XX:XX:84:85   [ DUAL ]  A2DP Sink, HFP AG, LE Audio -- the identity
     *                                  A2dpService and HeadsetService know
     *   XX:XX:XX:XX:3C:62   [  LE  ]  no classic profiles at all
     * </pre>
     *
     * <p>{@code LeAudioService} activates the group on {@code 3C:62}, so that is
     * what {@link #scheduleExclusiveEnter} records -- and therefore what the
     * release used to hand to {@code A2dpService.connect()}.  That call cannot
     * succeed: {@code connect()} refuses a device whose remote UUIDs lack
     * {@code A2DP_SINK}, which is every LE-only identity.  Decompiled from
     * {@code Bluetooth.apk} on this build:
     *
     * <pre>
     *   public boolean connect(BluetoothDevice device) {
     *       if (okToConnect(device)) {
     *           if (Util.arrayContains(getAdapterService().getRemoteUuids(device),
     *                                  BluetoothUuid.A2DP_SINK)) { ... return true; }
     *           Log.e(TAG, "Cannot connect to " + device
     *                   + " : Remote does not have A2DP Sink UUID");
     *           return false;
     *       }
     *   }
     * </pre>
     *
     * <p>Measured 2026-10-13: {@code leaExclusiveOff@...3C:62} followed by
     * {@code A2DP reconnect requested, started=false} and
     * {@code mConnectionState: STATE_DISCONNECTED}, LDAC never coming back.
     *
     * <p>So resolve to the bonded twin that actually owns A2DP before asking for
     * a reconnect.  The result is cached per LE address because the lookup walks
     * the bonded list and asks the stack for remote UUIDs.
     *
     * @return the device to reconnect, or {@code null} when no bonded identity of
     *         this earbud advertises A2DP Sink (a genuinely LE-only headset).
     */
    private static BluetoothDevice a2dpPeerOf(BluetoothDevice device) {
        if (device == null) return null;
        String address = device.getAddress();
        if (address == null) return null;
        String cached = A2DP_PEER.get(address);
        if (cached != null) return lookupBonded(cached);

        BluetoothDevice peer = device;
        try {
            if (!advertisesA2dpSink(device)) {
                peer = null;
                for (BluetoothDevice bonded : bondedDevices()) {
                    if (bonded == null || bonded.equals(device)) continue;
                    // Same earbud, other identity: the stack reports both under one
                    // name, and this is the only link between the two addresses --
                    // there is no public identity-address API on this build.
                    if (!sameName(device, bonded)) continue;
                    if (!advertisesA2dpSink(bonded)) continue;
                    peer = bonded;
                    break;
                }
            }
        } catch (Throwable t) {
            Logs.trace("dual-coexist: A2DP peer lookup failed - " + t);
            return null;
        }
        // Only the positive result is cached.  A miss is usually just "SDP has
        // not landed yet" -- the LE Audio activation can beat the classic
        // connection -- and caching that would pin the wrong answer for the life
        // of the process.  The lookup only runs on a group transition, so paying
        // for it again is cheap.
        if (peer != null && peer != device) {
            A2DP_PEER.put(address, peer.getAddress());
            Logs.trace("dual-coexist: A2DP peer of " + mask(address) + " is "
                    + mask(peer.getAddress()));
        }
        return peer;
    }

    /** Does this bonded device advertise the A2DP Sink profile? */
    private static boolean advertisesA2dpSink(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid uuid : uuids) {
            if (uuid != null && A2DP_SINK_UUID.equals(uuid.toString())) return true;
        }
        return false;
    }

    private static boolean sameName(BluetoothDevice a, BluetoothDevice b) {
        try {
            String name = a.getName();
            return name != null && name.equals(b.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BluetoothDevice lookupBonded(String address) {
        for (BluetoothDevice bonded : bondedDevices()) {
            if (bonded != null && address.equalsIgnoreCase(bonded.getAddress())) return bonded;
        }
        return null;
    }

    /**
     * The bonded device set, read through the live {@code AdapterService}.
     *
     * <p>{@code AdapterService.getBondedDevices()} is public on this build and
     * returns the {@code AdapterProperties} set directly.  Going through the
     * service rather than {@code BluetoothAdapter.getDefaultAdapter()} keeps this
     * on the same in-process object graph as every other hook here, so it cannot
     * be affected by an adapter that is mid-restart.
     */
    private static Iterable<BluetoothDevice> bondedDevices() {
        Object lea = leaServiceRef;
        if (lea == null) return java.util.Collections.emptyList();
        try {
            Object adapter = XposedHelpers.callMethod(lea, "getAdapterService");
            if (adapter == null) return java.util.Collections.emptyList();
            Object bonded = XposedHelpers.callMethod(adapter, "getBondedDevices");
            if (bonded instanceof Iterable) {
                @SuppressWarnings("unchecked")
                Iterable<BluetoothDevice> devices = (Iterable<BluetoothDevice>) bonded;
                return devices;
            }
        } catch (Throwable ignored) {
        }
        return java.util.Collections.emptyList();
    }

    /**
     * The A2DP Sink profile UUID.
     */
    private static final String A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb";

    /**
     * LE address -&gt; address of the bonded twin that owns A2DP.
     *
     * <p>Positive results only; a miss is re-resolved each time (see
     * {@link #a2dpPeerOf}).  Bounded by the bonded device count, and cleared with
     * the rest of the class's state on process restart.
     */
    private static final Map<String, String> A2DP_PEER = new ConcurrentHashMap<>();

    /**
     * How long to let an LE Audio activation settle before touching A2DP.
     *
     * <p>{@code setActiveGroupWithDevice()} runs on the LeAudioService thread and
     * calls into native; disconnecting A2DP from inside its hook would mean
     * re-entering the Bluetooth stack from under that call.  Measured on device,
     * the A2DP side is quiet for well over a second after the hand-over
     * (16:42:42.298 was 1.4 s after 16:42:40.899), so deferring costs nothing.
     */
    private static final long EXCLUSIVE_SETTLE_MS = 600L;

    /**
     * How long to wait before re-checking the HFP active slot.
     *
     * <p>Measured: HFP reclaimed the slot 565 ms after the A2DP disconnect went
     * in, so a single pass is not enough.  Long enough to be past that, short
     * enough that the user never hears the gap.
     */
    private static final long HFP_REASSERT_MS = 1400L;

    /**
     * Hand the audio path to LE Audio once it is connected.
     *
     * <p>The vendor ROM keeps classic audio as the active link for a
     * dual-connection device, so {@code OplusActiveDeviceManagerExtImpl
     * .oplusHandleLeaConnected()} answers {@code false} unless the user is in
     * "ultra game mode".  That short-circuits
     * {@code ActiveDeviceManager.handleLeaConnected()} and
     * {@code LeAudioService.setActiveDevice()} is never reached, leaving the
     * group at {@code currentlyActiveGroupId: -1} -- LE Audio connected but
     * silent.  Flipping the gate, and then releasing the classic active slots,
     * is what the ROM itself does whenever
     * {@code Utils.isDualModeAudioEnabled()} is {@code false}.
     */
    private static final boolean LE_AUDIO_TAKES_OVER = true;

    /**
     * How long LE Audio is defended after it has been made active.
     *
     * <p>Right after {@code LeAudioService.setActiveDevice()} succeeds the
     * classic profiles are still coming up, so
     * {@code isAllSupportedClassicAudioProfilesActive()} is false for a few
     * hundred milliseconds and every classic-active-device callback tries to
     * revoke the LE Audio slot.  Measured on device: LE Audio became active at
     * 16:07:43.753 and was revoked at 16:07:43.952 -- 199 ms later, triggered
     * by {@code handleHfpActiveDeviceChanged} arriving at 16:07:43.928, before
     * A2DP had even connected (16:07:44.351).
     *
     * <p>Once both classic profiles are active the ROM keeps LE Audio on its
     * own, so this window only has to cover the hand-over.
     */
    private static final long LEA_HOLD_WINDOW_MS = 15000L;

    /** Holder of the {@code AdapterService} on each of the two hooked classes. */
    private static final String ADAPTER_FIELD_ON_LEA_EXT = "mAdapterService";
    private static final String ADAPTER_FIELD_ON_DUAL_MODE = "mService";
    private static final String ADAPTER_FIELD_ON_ADM = "mAdapterService";

    private static final int PROFILE_HFP = 1;
    private static final int PROFILE_A2DP = 2;
    private static final int POLICY_ALLOWED = 100;

    /** Addresses already reported, so the hot path never touches the log file. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * {@code setProfileConnectionPolicy} fans out to
     * {@code OplusAdapterPropertiesExtImpl.updateOnProfileConnectionChanged()},
     * which calls {@code isSupportDualConnection()} right back.  Without this
     * guard that is unbounded recursion.
     */
    private static final ThreadLocal<Boolean> IN_FIX = new ThreadLocal<Boolean>();

    /**
     * One handler for the life of the process.  {@code onRemoteUuidsUpdated()}
     * fires on a binder thread and can fire twice for the same earbud within a
     * single discovery, so the follow-up connect is posted here and collapsed
     * per address by {@link #LEA_CONNECT_PENDING}.
     */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Addresses that already have a {@link #connectLeaNow} waiting to run. */
    private static final Set<String> LEA_CONNECT_PENDING = ConcurrentHashMap.newKeySet();

    /**
     * Set while {@code LeAudioService.removeActiveDevice()} is on the stack.
     *
     * <p>That method is the <em>API</em> teardown -- {@code setActiveDevice(null)}
     * ends up there, and so does every vendor call that hands the slot back to
     * a classic profile:
     *
     * <pre>
     *   // LeAudioService.removeActiveDevice()
     *   public boolean removeActiveDevice(boolean z) {
     *       setActiveGroupWithDevice(null, z);     // &lt;-- same funnel
     *       return true;
     *   }
     * </pre>
     *
     * <p>The deactivation guard below sits on that same funnel, so without this
     * marker it would also swallow deliberate requests and the user could never
     * move audio off the earbud (e.g. to the phone speaker) again.  The marker
     * is consumed -- and cleared -- by the nested call.
     */
    private static final ThreadLocal<Boolean> IN_REMOVE_ACTIVE = new ThreadLocal<Boolean>();

    /**
     * {@code SystemClock.elapsedRealtime()} of the last successful
     * {@code setLeAudioActiveDevice(device, ...)}.  See
     * {@link #LEA_HOLD_WINDOW_MS}.
     */
    private static volatile long leaActiveAt;

    /**
     * The live {@code LeAudioService}, captured from whichever hook sees it
     * first ({@code isGroupAvailableForStream()} runs on every LE Audio
     * connect).  Needed by the A2DP guard, which lives on a different class and
     * has no direct route to it.  A {@code null} value means "allow" -- the
     * safe default.
     */
    private static volatile Object leaServiceRef;

    /**
     * AZ100s currently held in LE-Audio-exclusive mode, keyed by address.
     *
     * <p>See {@link #scheduleExclusiveEnter}.  A non-empty map means "A2DP is
     * deliberately down for these devices", and {@link #hookA2dpActiveGuard}
     * uses that to keep it down.  It is <em>not</em> a connection policy, so it
     * lives and dies with the Bluetooth process.
     */
    private static final Map<String, BluetoothDevice> EXCLUSIVE = new ConcurrentHashMap<>();

    /**
     * Addresses whose A2DP reconnect this class has just issued.
     *
     * <p>{@link #classicMustStayDown} is consulted from inside
     * {@code A2dpService.connect()} -- the very call {@link #doExclusiveExit}
     * makes to bring classic audio back -- through
     * {@link #hookA2dpActiveGuard}'s {@code okToConnect} hook.  Without this
     * marker the release would veto itself:
     *
     * <pre>
     * dual-coexist: groupSwitch.apiRequest
     * dual-coexist: a2dpConnectRefused            &lt;- our own request, refused
     * dual-coexist: leaExclusiveOff@...
     * dual-coexist: A2DP reconnect requested, started=false
     * </pre>
     *
     * <p>measured 2026-10-13, with {@code A2dpStateMachine} left at
     * {@code STATE_DISCONNECTED}.  The marker is per address and removed as soon
     * as the connect call returns, so a user-initiated activation is still
     * refused for as long as LE Audio owns the path.
     */
    private static final Set<String> RELEASING = ConcurrentHashMap.newKeySet();

    /** Set while {@link #doExclusiveExit} is queued, so callers cannot stack. */
    private static volatile boolean exitScheduled;

    /**
     * Runs the enter/exit work off the Bluetooth service threads.  Single
     * threaded on purpose: enter and exit must not interleave.
     */
    private static final ScheduledExecutorService DEFERRED =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "az100-lea-exclusive");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile ClassLoader loader;
    private static volatile boolean factoryHooked;
    private static volatile boolean extHooked;
    private static volatile boolean leaHooked;

    private OplusDualModeCoexistHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        loader = classLoader;

        synchronized (OplusDualModeCoexistHook.class) {
            if (!factoryHooked) {
                factoryHooked = true;
                try {
                    Class<?> factory = XposedHelpers.findClass(FACTORY, classLoader);
                    XposedHelpers.findAndHookMethod(factory, "newInstance",
                            String.class, String.class, new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam param) {
                                    installExtHooks();
                                }
                            });
                } catch (Throwable t) {
                    Logs.e("dual-coexist: OplusBTFactory hook failed", t);
                }
            }
        }
        // Harmless if the dex has not been attached yet -- it just retries.
        installExtHooks();

        // These live in Bluetooth.apk itself, so they can go in immediately.
        // Guarded so a repeated install() cannot stack a duplicate set of hooks
        // on the same methods -- Xposed would happily run both copies.
        synchronized (OplusDualModeCoexistHook.class) {
            if (LE_AUDIO_TAKES_OVER && !leaHooked) {
                leaHooked = true;
                hookLeAudioActiveBypass(classLoader);
                hookGroupAvailableForStream(classLoader);
                hookA2dpActiveGuard(classLoader);
                hookLeaConnectedUnstick(classLoader);
            }
        }
    }

    /**
     * Resolves and hooks the two vendor classes.  Returns quietly while the
     * extension dex is still unattached; {@code OplusBTFactory.newInstance}
     * calls this again once {@code addDexPath()} has run.
     */
    private static void installExtHooks() {
        synchronized (OplusDualModeCoexistHook.class) {
            if (extHooked) return;
            ClassLoader cl = loader;
            if (cl == null) return;
            Class<?> impl;
            Class<?> leaExt;
            try {
                impl = XposedHelpers.findClass(DUAL_MODE_IMPL, cl);
                leaExt = XposedHelpers.findClass(LEA_POLICY_EXT, cl);
            } catch (Throwable t) {
                return;
            }
            hookIsSupportDualConnection(impl);
            hookIsDualConnectionNeeded(impl);
            hookDirectLeaConnect(impl);
            hookLeaInitPriorities(leaExt);
            hookEarbudsMultiConn(impl);
            hookVendorTeardownPaths(cl);
            if (LE_AUDIO_TAKES_OVER) {
                try {
                    hookLeaConnectedGate(XposedHelpers.findClass(ADM_EXT_IMPL, cl));
                } catch (Throwable t) {
                    Logs.e("dual-coexist: ActiveDeviceManagerExt not found", t);
                }
                hookClassicReleaseOnLeaActive(cl);
                hookHfpActiveGuard(cl);
            }
            extHooked = true;
            Logs.trace("dual-coexist: vendor hooks installed");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 1. stop the vendor code from forbidding classic audio               */
    /* ------------------------------------------------------------------ */

    private static void hookIsSupportDualConnection(Class<?> impl) {
        try {
            XposedHelpers.findAndHookMethod(impl, "isSupportDualConnection",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!isAz100(device)) return;
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            param.setResult(Boolean.TRUE);
                            note(device, "supportDualConnection");
                            ensureClassicAllowed(param.thisObject,
                                    ADAPTER_FIELD_ON_DUAL_MODE, device);
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: isSupportDualConnection hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2. select the vendor's "dual connection" mode, which is the only     */
    /*    state in which oplusConnectEnabledProfiles() calls               */
    /*    leAudioService.connect(device) at all                            */
    /* ------------------------------------------------------------------ */

    /**
     * Drive {@code OplusLeAudioPhonePolicyExt.connectLeAudio()} for the AZ100
     * as soon as {@code 0000184E} is known, instead of waiting for the vendor
     * "dual connection preference" to change.
     *
     * <p>The stock caller is {@code onRemoteUuidsUpdated()}, and it only acts
     * when the device is already in {@code mPendingLeaConnectAfterDiscover}.
     * That set is filled exclusively by {@code handlePreferenceChanged()},
     * which runs when {@code AppSettings} notifies a preference change -- a
     * connection never does.  So for a headset that is not an OPPO earbud the
     * method returns before it reaches {@code connectLeAudio()}, and the only
     * link that comes up is classic A2DP.  Measured: {@code [ACL BR/EDR:Y LE:N]}
     * with not a single {@code connectLeAudio} line in the Bluetooth process.
     *
     * <p>Hooking the exit covers every reason the stock body bailed out -- the
     * device was not pending, or {@code isDualConnectionNeeded()} said no --
     * and re-runs only the tail: mark the group enabled, then
     * {@code connectLeAudio()}.  That is the branch that does
     * {@code setConnectionPolicy(LEA, 100)} and connects LE Audio directly,
     * because {@link #hookIsSupportDualConnection} already makes
     * {@code isSupportDualConnection()} answer true.  A group that is already
     * connected is left alone, and the call is posted off this binder thread so
     * it cannot re-enter the UUID callback that invoked it.
     */
    private static void hookDirectLeaConnect(final Class<?> impl) {
        try {
            XposedHelpers.findAndHookMethod(impl, "onRemoteUuidsUpdated",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof BluetoothDevice)) return;
                            final BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!isAz100(device)) return;
                            final String address = device.getAddress();
                            if (address == null || !LEA_CONNECT_PENDING.add(address)) return;
                            final Object self = param.thisObject;
                            MAIN.post(new Runnable() {
                                @Override public void run() {
                                    LEA_CONNECT_PENDING.remove(address);
                                    connectLeaNow(impl, self, device);
                                }
                            });
                        }
                    });
            Logs.trace("dual-coexist: direct LEA connect armed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: direct LEA connect hook failed", t);
        }
    }

    /**
     * The tail of {@code OplusDualModeManagerImpl.onRemoteUuidsUpdated()}, run
     * for the AZ100 whether or not it was pending.  Both calls are the vendor's
     * own; this only removes the preference-change gate in front of them.
     */
    private static void connectLeaNow(Class<?> impl, Object self, BluetoothDevice device) {
        try {
            // The settings page's master switch.  Off means this module never
            // forces the upgrade; the system's own LE Audio switch still decides
            // whether the profile may be used at all.
            if (!Prefs.autoLea()) {
                noteOnce("leaAutoConnectDisabledBySetting@" + device.getAddress());
                return;
            }
            Object service = XposedHelpers.getObjectField(self, ADAPTER_FIELD_ON_DUAL_MODE);
            if (service == null) return;
            Object lea = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(service, "getLeAudioService"), "orElse", (Object) null);
            if (lea == null) return;
            Object state = XposedHelpers.callMethod(lea, "getConnectionState", device);
            if (state instanceof Integer && ((Integer) state).intValue() == 2) return;

            XposedHelpers.callMethod(self, "setLeAudioGroupEnabled", device, Boolean.TRUE);

            Class<?> leaExt = XposedHelpers.findClass(LEA_POLICY_EXT, impl.getClassLoader());
            Object policy = XposedHelpers.getStaticObjectField(leaExt, "sInstance");
            if (policy == null) {
                Logs.trace("dual-coexist: direct LEA connect skipped, policy not up yet");
                return;
            }
            XposedHelpers.callMethod(policy, "connectLeAudio", device);
            note(device, "directLeaConnect");
        } catch (Throwable t) {
            Logs.d("dual-coexist: direct LEA connect failed", t);
        }
    }

    private static void hookIsDualConnectionNeeded(Class<?> impl) {
        try {
            XposedHelpers.findAndHookMethod(impl, "isDualConnectionNeeded",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!isAz100(device)) return;
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            param.setResult(Boolean.TRUE);
                            note(device, "dualConnectionNeeded");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: isDualConnectionNeeded hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3. un-stick the already-forbidden policies                          */
    /* ------------------------------------------------------------------ */

    /**
     * Runs before the method reads {@code hfpAllowed}/{@code a2dpAllowed}, so
     * the two policies are back at {@code 100} by the time the
     * {@code (!hfpAllowed &amp;&amp; !a2dpAllowed)} bail-out is evaluated.
     */
    private static void hookLeaInitPriorities(Class<?> leaExt) {
        try {
            XposedHelpers.findAndHookMethod(leaExt, "oplusInitProfilePriorities",
                    boolean.class, BluetoothDevice.class, ParcelUuid[].class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[1] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[1];
                            if (!isAz100(device)) return;
                            ensureClassicAllowed(param.thisObject,
                                    ADAPTER_FIELD_ON_LEA_EXT, device);
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: oplusInitProfilePriorities hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3b. ignore the phantom "earbuds are busy elsewhere" report          */
    /* ------------------------------------------------------------------ */

    /**
     * The vendor stack tears LE Audio down, and mutes system audio, whenever
     * its native "earbuds multi connection" watcher reports that the earbuds
     * are occupied by another link:
     *
     * <pre>
     *   // OplusDualModeManagerImpl.handleEarbudsMultiConnOccupied()
     *   if (isLeaActive) {
     *       leaService.setActiveDevice((BluetoothDevice) null);
     *       muteSystemAudio(true);
     *       return;
     *   }
     * </pre>
     *
     * The report is fed by OPPO's private SmartAudio / "Echo" channel:
     *
     * <pre>
     *   native
     *     -&gt; OplusBluetoothSmartAudioInterface.onEarbudsMultiConnStatusChanged()  [Bluetooth.apk]
     *       -&gt; OplusBluetoothSmartAudioServer.onEarbudsMultiConnStatusChanged()   [vendor jar]
     *         -&gt; OplusDualModeManagerImpl.onEarbudsMultiConnStatusChanged()
     * </pre>
     *
     * which is the same handshake a third-party headset never answers -- the
     * reason {@code isSupportDualConnection} is stuck at {@code false} as well.
     * Measured on device, 1 second after LE Audio first became active:
     *
     * <pre>
     *   16:13:55.896 LeAudioGroupDescriptor.setActiveState (groupId: 1): inactive -&gt; active
     *   16:13:56.850 [API call] setActiveDevice: device=null, current out=3C:62, current in=3C:62
     *   16:13:56.852 LeAudioGroupDescriptor.setActiveState (groupId: 1): active -&gt; inactive
     * </pre>
     *
     * The headset was connected to this phone and nothing else.  Both the
     * status update and the action are skipped for the AZ100: the earbud
     * manages its own second link, and classic audio remains connected
     * underneath as a fallback either way.
     */
    private static void hookEarbudsMultiConn(Class<?> impl) {
        try {
            XposedHelpers.findAndHookMethod(impl, "handleEarbudsMultiConnOccupied",
                    BluetoothDevice.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            param.setResult(null);
                            noteOnce("earbudsMultiConnOccupiedSkipped");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: handleEarbudsMultiConnOccupied hook failed", t);
        }
        try {
            XposedHelpers.findAndHookMethod(impl, "onEarbudsMultiConnStatusChanged",
                    BluetoothDevice.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            param.setResult(null);
                            noteOnce("earbudsMultiConnStatusIgnored");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: onEarbudsMultiConnStatusChanged hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3c. the remaining vendor paths that revoke LE Audio                 */
    /* ------------------------------------------------------------------ */

    /**
     * The other places in the vendor jar that switch LE Audio off.  Separate
     * entry points with separate triggers, but they all rest on the same two
     * assumptions this file exists to correct: an OPPO-only capability report
     * the headset never sends, and "classic audio is the host link, LE Audio
     * is the guest".
     *
     * <dl>
     *   <dt>{@code OplusAdapterServiceExtImpl.disableLeAudioProfiles}</dt>
     *   <dd>Armed as a 6 second timer from
     *       {@code oplusHandleHfpActiveDeviceChanged} and, if A2DP has not
     *       become active by then, it writes {@code connectionPolicy = 0} for
     *       the whole LE Audio group.  {@code oplusHandleA2dpActiveDeviceChanged}
     *       normally cancels it, but a slow or failed A2DP connection lets it
     *       land.</dd>
     *
     *   <dt>{@code OplusDualModeManagerImpl.disconnectLeaGroupForPreference}</dt>
     *   <dd>Runs when the vendor "dual connection preference" flips to false.
     *       That preference is written by
     *       {@code handleEarbudsGameLowLatencyRequest()}, which is fed by the
     *       same SmartAudio channel as the multi-connection report -- and the
     *       preference is also read straight back by
     *       {@code isDualConnectionNeeded()}, which this module already forces
     *       on.</dd>
     *
     *   <dt>{@code OplusLeAudioUtils.oplusDisableProfiles}</dt>
     *   <dd>A no-op today, because it is gated on
     *       {@code persist.bluetooth.enable_dual_mode_audio} being
     *       <em>off</em> and on this ROM it is on.  Guarded anyway so that
     *       property cannot silently start tearing LE Audio down.</dd>
     * </dl>
     */
    private static void hookVendorTeardownPaths(ClassLoader cl) {
        try {
            Class<?> adapterExt = XposedHelpers.findClass(ADAPTER_SERVICE_EXT_IMPL, cl);
            XposedHelpers.findAndHookMethod(adapterExt, "disableLeAudioProfiles",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            param.setResult(null);
                            noteOnce("disableLeAudioProfilesSkipped");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: disableLeAudioProfiles hook failed", t);
        }

        try {
            Class<?> admExt = XposedHelpers.findClass(ADM_EXT_IMPL, cl);
            XposedHelpers.findAndHookMethod(admExt, "disableLeAudioProfiles",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            param.setResult(null);
                            noteOnce("admDisableLeAudioProfilesSkipped");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: adm disableLeAudioProfiles hook failed", t);
        }

        try {
            Class<?> leaUtils = XposedHelpers.findClass(LEA_UTILS, cl);
            XposedHelpers.findAndHookMethod(leaUtils, "oplusDisableProfiles",
                    String.class, BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[1])) return;
                            param.setResult(null);
                            noteOnce("oplusDisableProfilesSkipped");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: oplusDisableProfiles hook failed", t);
        }

        try {
            Class<?> impl = XposedHelpers.findClass(DUAL_MODE_IMPL, cl);
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            XposedHelpers.findAndHookMethod(impl, "disconnectLeaGroupForPreference",
                    BluetoothDevice.class, leaService, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            param.setResult(null);
                            noteOnce("disconnectLeaGroupForPreferenceSkipped");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: disconnectLeaGroupForPreference hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 4. let LE Audio become the active audio path                        */
    /* ------------------------------------------------------------------ */

    private static void hookLeaConnectedGate(Class<?> admExt) {
        try {
            XposedHelpers.findAndHookMethod(admExt, "oplusHandleLeaConnected",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!isAz100(device)) return;
                            param.setResult(Boolean.TRUE);
                            note(device, "leaConnectedGate");
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: oplusHandleLeaConnected hook failed", t);
        }
    }

    /**
     * Keeps LE Audio as the active path once it has been made active.
     *
     * <h2>Why LE Audio was revoked 199 ms after it started working</h2>
     *
     * On this headset the two earbuds are two different LE Audio group members
     * with two different addresses -- the left one is the group lead
     * ({@code 3c:62}) and the classic profiles live on the right one
     * ({@code 84:85}).  Measured on device:
     *
     * <pre>
     *   Connection Events:
     *     16:07:42.528 CONNECTED  xx:xx:xx:xx:3c:62      // group lead
     *     16:07:42.816 CONNECTED  xx:xx:xx:xx:84:85      // classic + LE
     *   ActiveDevice Manager Event:
     *     16:07:43.892 handleHfpConnected: 84:85
     *     16:07:43.928 handleHfpActiveDeviceChanged: 84:85
     *     16:07:44.198 handleLeAudioActiveDeviceChanged: null
     *     16:07:44.351 handleA2dpConnected: 84:85
     * </pre>
     *
     * {@code handleHfpActiveDeviceChanged()} (ActiveDeviceManager:389) then
     * takes two shots at the LE Audio slot:
     *
     * <pre>
     *   updateLeAudioActiveDeviceIfDualMode(mHfpActiveDevice, 84:85);
     *
     *   // ActiveDeviceManager.updateLeAudioActiveDeviceIfDualMode()
     *   } else if (adapterService.isAllSupportedClassicAudioProfilesActive(btDevice2)) {
     *       setLeAudioActiveDevice(btDevice2, true);       // keep
     *   } else {
     *       setLeAudioActiveDevice(null, true);            // drop  &lt;-- 1st
     *   }
     *
     *   BluetoothDevice lea = this.mLeAudioActiveDevice;   // 3c:62, the lead
     *   if (lea != null &amp;&amp; bluetoothDevice != null &amp;&amp; !lea.equals(bluetoothDevice)) {
     *       setLeAudioActiveDevice(null, false);           // drop  &lt;-- 2nd
     *   }
     * </pre>
     *
     * The first one fires because HFP is up but A2DP has not connected yet, so
     * "all supported classic profiles active" is false.  The second one fires
     * because {@code mLeAudioActiveDevice} holds the <em>group lead</em>
     * ({@code 3c:62}) while the classic callback carries the <em>other</em>
     * earbud ({@code 84:85}), so the {@code equals()} never matches.
     *
     * <p>Both collapse into one place: every revoke goes through
     * {@code setLeAudioActiveDevice(null, ...)}, so that is what is guarded
     * here.  Once both classic profiles are active the ROM maintains LE Audio
     * by itself (the {@code isAllSupportedClassicAudioProfilesActive()} branch
     * above), which is why the guard is time-boxed rather than permanent -- a
     * deliberate switch to the phone speaker later on still works.
     */
    private static void hookClassicReleaseOnLeaActive(ClassLoader cl) {
        try {
            Class<?> adm = XposedHelpers.findClass(ACTIVE_DEVICE_MANAGER, cl);

            XposedHelpers.findAndHookMethod(adm, "setLeAudioActiveDevice",
                    BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // Only revokes (device == null) are guarded; handing the
                            // slot to a real device always runs the original code.
                            if (param.args[0] instanceof BluetoothDevice) return;
                            if (SystemClock.elapsedRealtime() - leaActiveAt
                                    > LEA_HOLD_WINDOW_MS) {
                                return;
                            }
                            if (!leaGroupHasAz100(param.thisObject)) return;
                            param.setResult(Boolean.TRUE);
                            noteOnce("leaReleaseHeld");
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!isAz100(device)) return;
                            if (!Boolean.TRUE.equals(param.getResult())) return;
                            leaActiveAt = SystemClock.elapsedRealtime();
                            note(device, "leAudioActive");
                        }
                    });

            // The dual-mode sync drops LE Audio whenever the classic profiles
            // are not all active yet.  For an AZ100 member there is nothing to
            // sync -- the two links belong to the same earbud pair.
            XposedHelpers.findAndHookMethod(adm, "updateLeAudioActiveDeviceIfDualMode",
                    BluetoothDevice.class, BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // args[0] is the previous classic active device,
                            // args[1] the one taking over.  Only skip when the
                            // AZ100 is the device being handed the slot, or when
                            // the slot is being vacated altogether with an AZ100
                            // holding it.  A *different* non-null device (a car
                            // kit, another headset) must still run the original
                            // sync, otherwise LE Audio would never be released
                            // for it.
                            boolean az100TakesSlot = isAz100Arg(param.args[1]);
                            boolean az100SlotVacated =
                                    param.args[1] == null && isAz100Arg(param.args[0]);
                            if (!az100TakesSlot && !az100SlotVacated) return;
                            param.setResult(null);
                            noteOnce("dualModeSyncSkipped");
                        }
                    });

            // Once LE Audio is the active path, A2DP must not take it back.
            //
            // Both links stay connected here (dual mode), and the audio policy
            // engine hands the media strategies to whichever of the two is
            // *active* -- there is no preferred device of its own:
            //
            //   Preferred devices for strategy:          (empty)
            //   Applied devices roles for strategies (internal):
            //     strategy: 0 role:2 devices:[... type:bt_a2dp ...]   <- media
            //
            // Measured on device, A2DP re-activated itself 1.4 s after the
            // hand-over and took every strategy back:
            //
            //   16:42:40.899 LeAudioService -&gt; AudioManager:
            //                mActiveAudioOutDevice: 84:85 isLeOutput: true
            //   16:42:41.465 BluetoothActiveDeviceChanged for A2DP, 84:85 -&gt; null
            //   16:42:42.298 handleA2dpActiveDeviceChanged: 84:85, mA2dpActiveDevice=null
            //   16:42:43     dumpsys audio: strategy 0..11 -&gt; type:bt_a2dp
            //
            // That leaves silence on both paths: the earbud tears the classic
            // link down once the LE Audio CIS is up (16:42:41.634
            // {@code prof=2 state=0}), and the media strategies still point at
            // the A2DP device that no longer carries anything.
            //
            // So: refuse an A2DP *activation* while LE Audio is the active path
            // for the same earbud.  Deactivations ({@code device == null}) and
            // every device that is not an AZ100 pass straight through, so a
            // genuine fallback to classic audio still works once the LE Audio
            // group goes inactive.
            XposedHelpers.findAndHookMethod(adm, "setA2dpActiveDevice",
                    BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            if (!leaGroupHasAz100(param.thisObject)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("a2dpReactivationRefused");
                        }
                    });

            Logs.trace("dual-coexist: ActiveDeviceManager hooks installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: ActiveDeviceManager hook failed", t);
            Logs.trace("dual-coexist: ActiveDeviceManager hook FAILED - " + t);
        }
    }

    /**
     * True while the LE Audio group still holds an AZ100 member.  Called only
     * from the guarded revoke path, i.e. at most a handful of times per
     * connection.
     */
    private static boolean leaGroupHasAz100(Object admInstance) {
        try {
            Object adapter = XposedHelpers.getObjectField(admInstance, ADAPTER_FIELD_ON_ADM);
            if (adapter == null) return false;
            return leaGroupHasAz100OnService(
                    optionalGet(XposedHelpers.callMethod(adapter, "getLeAudioService")));
        } catch (Throwable ignored) {
            // A missing service means "do not interfere".
        }
        return false;
    }

    /**
     * Unwraps a {@code java.util.Optional} without reflection guessing.
     *
     * <p>The obvious {@code callMethod(optional, "orElse", new Object[]{null})}
     * does not work: Xposed derives the parameter types from the argument values,
     * and a {@code null} argument becomes {@code Object.class}, which fails to
     * match {@code orElse(T)} once the compiler has specialised the call site.
     * Asking {@code isPresent()} first avoids the problem entirely.
     */
    private static Object optionalGet(Object optional) {
        if (optional == null) return null;
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(optional, "isPresent"))) {
                return null;
            }
            return XposedHelpers.callMethod(optional, "get");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The same question, asked straight of a {@code LeAudioService}: is the
     * active LE Audio group led by an AZ100 that is still connected?
     *
     * <p>The connection check matters -- it is what keeps the guard out of the
     * way when the earbud really is being removed or turned off, where
     * releasing the active device is correct and must be allowed through.
     */
    private static boolean leaGroupHasAz100OnService(Object leaService) {
        if (leaService == null) return false;
        try {
            Object active = XposedHelpers.callMethod(leaService, "getActiveDevices");
            if (!(active instanceof List)) return false;
            for (Object entry : (List<?>) active) {
                if (!(entry instanceof BluetoothDevice)) continue;
                BluetoothDevice device = (BluetoothDevice) entry;
                if (!isAz100(device)) continue;
                Object state = XposedHelpers.callMethod(leaService,
                        "getConnectionState", device);
                if (state instanceof Integer && ((Integer) state).intValue() == 2) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 5. first gate: LeAudioService.isGroupAvailableForStream()           */
    /* ------------------------------------------------------------------ */

    /**
     * The gate that actually kept LE Audio inactive on this headset.
     *
     * <p>{@code ActiveDeviceManager.handleLeAudioConnected()} gives up before
     * it ever considers handing the active-device slot to LE Audio, if the
     * group has no audio context available yet:
     *
     * <pre>
     *   // ActiveDeviceManager.lambda$profileConnectionStateChanged$3()
     *   this.mLeAudioConnectedDevices.add(bluetoothDevice);
     *   if (isBroadcastingAudio()) return;
     *   if (!leAudioService.get().isGroupAvailableForStream(
     *           leAudioService.get().getGroupId(bluetoothDevice))) {
     *       Log.i(TAG, "LE Audio device is not available for streaming now." + bluetoothDevice);
     *       return;                                   // -&gt; never activates
     *   }
     *   ...
     *   } else if (this.mOplusAdmExt.oplusHandleLeaConnected(bluetoothDevice)
     *           &amp;&amp; setLeAudioActiveDevice(bluetoothDevice, true)
     *           &amp;&amp; !Utils.isDualModeAudioEnabled()) {
     * </pre>
     *
     * <p>and {@code isGroupAvailableForStream()} is just
     * {@code mAvailableContexts != 0}:
     *
     * <pre>
     *   // LeAudioService.isGroupAvailableForStream()
     *   } else if (groupDescriptor.mAutoActiveModeEnabled.booleanValue()) {
     *       Integer num = groupDescriptor.mAvailableContexts;
     *       if (num != null &amp;&amp; num.intValue() != 0) z = true;
     *   }
     * </pre>
     *
     * <p>That is a deadlock on this phone.  {@code mAvailableContexts} is only
     * non-zero once something already points a stream at the group, and
     * nothing points a stream at the group until it is active -- AudioService
     * decides against it, because the group is inactive.  Measured on device,
     * the group connected at 16:22:03 and sat at {@code INACTIVE}; the only
     * activation that happened came from the {@code LeAudioService}
     * reactivation path, and native withdrew it 68 ms later:
     *
     * <pre>
     *   16:22:15.469 [From Native]: groupId: 1, status: Active
     *   16:22:15.534 setActiveState (groupId: 1): inactive -&gt; active
     *   16:22:15.537 [From Native]: groupId: 1, status: Inactive
     *   16:22:15.537 setActiveState (groupId: 1): active -&gt; inactive
     * </pre>
     *
     * <p>Answering {@code true} for a group that holds an AZ100 lets the
     * hand-over run at connect time -- the path that was measured working
     * earlier, where {@code groupActivated=true} and
     * {@code mActiveAudioOutDevice ... isLeOutput: true} both landed.  It also
     * reaches the vendor's own {@code groupSetAudioResume()} native call
     * (tagged {@code OPLUS_LE_A2DP_COEXIST} inside
     * {@code LeAudioService.setActiveGroupWithDevice()}), which is what keeps
     * the group up rather than letting native withdraw it.
     *
     * <p>Only groups that contain an AZ100 member are answered for, so a real
     * LE Audio device keeps the stock behaviour.
     */
    private static void hookGroupAvailableForStream(ClassLoader cl) {
        try {
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            XposedHelpers.findAndHookMethod(leaService, "isGroupAvailableForStream",
                    int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            leaServiceRef = param.thisObject;
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            Object raw = param.args[0];
                            if (!(raw instanceof Integer)) return;
                            int groupId = (Integer) raw;
                            if (groupId == -1) return;
                            if (!groupHasAz100(param.thisObject, groupId)) return;
                            param.setResult(Boolean.TRUE);
                            noteOnce("groupAvailableForStreamForced");
                        }
                    });
            Logs.trace("dual-coexist: isGroupAvailableForStream hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: isGroupAvailableForStream hook failed", t);
            Logs.trace("dual-coexist: isGroupAvailableForStream hook FAILED - " + t);
        }
    }

    /**
     * True while the <em>currently active</em> group still holds a connected
     * AZ100.  Used by the deactivation guard, so it is only consulted when the
     * service is already tearing the group down.
     */
    private static boolean activeGroupHasConnectedAz100(Object leaService) {
        try {
            int groupId = activeGroupId(leaService);
            if (groupId == -1) return false;
            Object devices = XposedHelpers.callMethod(leaService, "getGroupDevices", groupId);
            if (!(devices instanceof List)) return false;
            for (Object entry : (List<?>) devices) {
                if (!(entry instanceof BluetoothDevice)) continue;
                BluetoothDevice device = (BluetoothDevice) entry;
                if (!isAz100(device)) continue;
                Object state = XposedHelpers.callMethod(leaService, "getConnectionState", device);
                if (state instanceof Integer && ((Integer) state).intValue() == 2) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A missing descriptor means "do not interfere".
        }
        return false;
    }

    /** {@code LeAudioService.getActiveGroupId()}, or {@code -1} if unavailable. */
    private static int activeGroupId(Object leaService) {
        if (leaService == null) return -1;
        try {
            Object raw = XposedHelpers.callMethod(leaService, "getActiveGroupId");
            return raw instanceof Integer ? ((Integer) raw).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Does {@code groupId} hold an AZ100 member? */
    private static boolean groupHasAz100(Object leaService, int groupId) {
        try {
            Object devices = XposedHelpers.callMethod(leaService, "getGroupDevices", groupId);
            if (!(devices instanceof List)) return false;
            for (Object entry : (List<?>) devices) {
                if (entry instanceof BluetoothDevice && isAz100((BluetoothDevice) entry)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A missing descriptor means "do not interfere".
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 6. last gate: LeAudioService.setActiveDevice()                      */
    /* ------------------------------------------------------------------ */

    /**
     * With {@code Utils.isDualModeAudioEnabled()} true, the vendor ROM refuses
     * to activate the LE Audio group unless <em>every</em> supported classic
     * audio profile is already active on the same device:
     *
     * <pre>
     *   // LeAudioService.setActiveDevice()
     *   if (!Utils.isDualModeAudioEnabled()
     *           || getAdapterService().isAllSupportedClassicAudioProfilesActive(device)) {
     *       return setActiveGroupWithDevice(device, false);
     *   }
     *   Log...("failed because the device is not active for all supported classic audio profiles");
     *   return false;
     * </pre>
     *
     * That is circular once the classic active slots have been handed over, and
     * it is the reason {@code currentlyActiveGroupId} stayed at {@code -1}.  Go
     * straight to the private {@code setActiveGroupWithDevice()} the ROM itself
     * would have called -- the connectivity precondition is re-checked here, so
     * an unconnected device is still rejected by the original code.
     */
    private static void hookLeAudioActiveBypass(ClassLoader cl) {
        try {
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            XposedHelpers.findAndHookMethod(leaService, "setActiveDevice",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object raw = param.args[0];
                            if (!(raw instanceof BluetoothDevice)) {
                                // A tear-down request (device == null).  Catch-all for
                                // any other caller that revokes LE Audio while the
                                // hand-over is still in flight -- see LEA_HOLD_WINDOW_MS.
                                if (SystemClock.elapsedRealtime() - leaActiveAt
                                        > LEA_HOLD_WINDOW_MS) {
                                    return;
                                }
                                if (!leaGroupHasAz100OnService(param.thisObject)) return;
                                param.setResult(Boolean.FALSE);
                                noteOnce("leaTearDownRefused");
                                return;
                            }
                            BluetoothDevice device = (BluetoothDevice) raw;
                            if (!isAz100(device)) return;
                            Object state = XposedHelpers.callMethod(param.thisObject,
                                    "getConnectionState", device);
                            if (!(state instanceof Integer) || ((Integer) state).intValue() != 2) {
                                return;
                            }
                            try {
                                Object ok = XposedHelpers.callMethod(param.thisObject,
                                        "setActiveGroupWithDevice", device, Boolean.FALSE);
                                param.setResult(ok);
                                note(device, "groupActivated=" + ok);
                            } catch (Throwable t) {
                                Logs.trace("dual-coexist: setActiveGroupWithDevice failed - " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: LeAudioService.setActiveDevice hook failed", t);
            Logs.trace("dual-coexist: LeAudioService.setActiveDevice hook FAILED - " + t);
        }

        try {
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            // The other half of the same gate.  setActiveDevice(null) reaches
            // removeActiveDevice(false), but the vendor jar also calls
            // removeActiveDevice(true) directly -- from
            // OplusAdapterServiceExtImpl.setActiveDevice() when it hands the
            // slot to a classic profile, among others.
            XposedHelpers.findAndHookMethod(leaService, "removeActiveDevice",
                    boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // Mark the nested setActiveGroupWithDevice(null, ...)
                            // call as a deliberate API request, so the
                            // deactivation guard lets it through.
                            IN_REMOVE_ACTIVE.set(Boolean.TRUE);
                            if (SystemClock.elapsedRealtime() - leaActiveAt
                                    > LEA_HOLD_WINDOW_MS) {
                                return;
                            }
                            if (!leaGroupHasAz100OnService(param.thisObject)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("removeActiveDeviceRefused");
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            // The nested call normally consumes the marker; clear
                            // it here too so a throw cannot leak it onto the
                            // thread and disarm the guard later.
                            IN_REMOVE_ACTIVE.remove();
                        }
                    });
        } catch (Throwable t) {
            Logs.e("dual-coexist: LeAudioService.removeActiveDevice hook failed", t);
            Logs.trace("dual-coexist: LeAudioService.removeActiveDevice hook FAILED - " + t);
        }

        try {
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            // The gate that undid the hand-over 38 ms after it succeeded.
            //
            // LeAudioService inactivates the group whenever a native context
            // report says no audio context is available:
            //
            // <pre>
            //   // LeAudioService:4795, handling a context-available event
            //   if (groupDescriptor.mAvailableContexts.intValue() == 0) {
            //       if (groupDescriptor.isActive()) {
            //           Log.i(TAG, " Inactivating group " + i2 + " due to unavailable context types");
            //           groupDescriptor.mInactivatedDueToContextType = Boolean.TRUE;
            //           setActiveGroupWithDevice(null, false);        // &lt;-- here
            //       }
            //       return;
            //   }
            // </pre>
            //
            // Measured on device, immediately after the hand-over this file
            // makes possible:
            //
            // <pre>
            //   ...26703 dual-coexist: groupActivated=true
            //   ...26703 dual-coexist: leAudioActive
            //   ...26741 dual-coexist: groupSwitch.null          (38 ms later)
            // </pre>
            //
            // and the group then sits INACTIVE, which puts every media strategy
            // back on {@code bt_a2dp}.  Auto-active mode is the correct policy
            // for a headset that only speaks LE Audio, but here
            // {@code mAvailableContexts} is driven by the vendor's dual-mode
            // bookkeeping and reads zero even while media is playing, so the
            // matching reactivation path (LeAudioService:4806) never runs
            // either.  Forcing the availability check open without also holding
            // the group up just produces a 38 ms on/off cycle.
            //
            // So: refuse the deactivation while the active group still holds a
            // connected AZ100.  A real disconnect empties the group, so the
            // teardown still goes through then.
            //
            // <p>The guard deliberately covers only the two automatic,
            // context-driven call sites -- LeAudioService:4799 (contexts went to
            // zero) and LeAudioService:1465 ({@code handleGroupHealthAction()}
            // reporting a blocked media context).  Both set
            // {@code mInactivatedDueToContextType} to true, i.e. the stack
            // itself considers the state temporary and would bring the group
            // back when contexts return.  The third call site,
            // LeAudioService:5300, is {@code removeActiveDevice()} -- an
            // explicit request -- and is let through via {@link #IN_REMOVE_ACTIVE}.
            //
            // <p>The remaining two call sites pass a real device
            // (LeAudioService:4809 reactivation, :5391 setActiveDevice) and
            // never reach this branch.
            XposedHelpers.findAndHookMethod(leaService, "setActiveGroupWithDevice",
                    BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof BluetoothDevice) {
                                noteOnce("groupSwitch.device");
                                return;
                            }
                            // Consume the API marker first, whatever we decide.
                            boolean apiRequest = Boolean.TRUE.equals(IN_REMOVE_ACTIVE.get());
                            IN_REMOVE_ACTIVE.remove();
                            if (apiRequest) {
                                // Deliberate request -- AudioService giving the
                                // output back to the speaker, or the vendor jar
                                // handing the slot to a classic profile.  The
                                // time-boxed refusal in the removeActiveDevice
                                // hook above already covers the hand-over
                                // window; after that this must go through.
                                noteOnce("groupSwitch.apiRequest");
                                return;
                            }
                            noteOnce("groupSwitch.null");
                            if (!activeGroupHasConnectedAz100(param.thisObject)) return;
                            param.setResult(Boolean.TRUE);
                            noteOnce("groupDeactivationRefused");
                        }

                        /**
                         * The one place that knows whether the group really moved.
                         *
                         * <p>{@code true} with a device means LE Audio now owns the
                         * audio path for it, which is the moment to take A2DP out of
                         * the picture ({@link #scheduleExclusiveEnter}).
                         *
                         * <p>{@code true} with {@code null} is <em>not</em> enough to
                         * conclude the group went inactive: the guard above answers
                         * {@code true} precisely when it <em>refuses</em> a teardown,
                         * so the return value cannot be told apart from a real one.
                         * Ask the service for the state instead.  Only a group that
                         * really is inactive hands A2DP back
                         * ({@link #scheduleExclusiveExit}).
                         */
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.getResult())) return;
                            leaServiceRef = param.thisObject;
                            Object raw = param.args[0];
                            if (raw instanceof BluetoothDevice) {
                                BluetoothDevice device = (BluetoothDevice) raw;
                                if (isAz100(device)) scheduleExclusiveEnter(device);
                                return;
                            }
                            if (activeGroupId(param.thisObject) != -1) return;
                            scheduleExclusiveExit();
                        }
                    });

            Logs.trace("dual-coexist: LeAudioService hooks installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: LeAudioService hooks failed", t);
            Logs.trace("dual-coexist: LeAudioService hooks FAILED - " + t);
        }

        try {
            Class<?> leaService = XposedHelpers.findClass(LE_AUDIO_SERVICE, cl);
            // Second exit trigger: the earbud leaving LE Audio altogether.
            //
            //   // LeAudioService.notifyConnectionStateChanged()
            //   public void notifyConnectionStateChanged(
            //           BluetoothDevice bluetoothDevice, int i, int i2) {   // i = new, i2 = previous
            //
            // The group-deactivation afterHook above covers the usual path, but
            // it is not guaranteed to run when the peer simply vanishes (turned
            // off, walked away, unpaired).  Without this second trigger an entry
            // left in EXCLUSIVE would keep A2DP blocked for the rest of the
            // Bluetooth process's life, which is exactly the "no LDAC ever
            // again" failure this design is trying to avoid.
            XposedHelpers.findAndHookMethod(leaService, "notifyConnectionStateChanged",
                    BluetoothDevice.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            Object state = param.args[1];
                            if (!(state instanceof Integer)) return;
                            if (((Integer) state).intValue() != 0) return;
                            leaServiceRef = param.thisObject;
                            scheduleExclusiveExit();
                        }
                    });
            Logs.trace("dual-coexist: notifyConnectionStateChanged hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: notifyConnectionStateChanged hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 7. the vendor's own A2DP choke point                                */
    /* ------------------------------------------------------------------ */

    /**
     * The single funnel every A2DP activation goes through -- and the place the
     * vendor ROM already put its own guard:
     *
     * <pre>
     *   // A2dpService.setActiveDevice()
     *   public boolean setActiveDevice(BluetoothDevice bluetoothDevice) {
     *       if (!this.mOplusA2dpService.oplusSetActiveBefore(bluetoothDevice)) {
     *           return false;
     *       }
     *       ...
     *
     *   // OplusA2dpServiceExtImpl.oplusSetActiveBefore()
     *   if (leAudioService != null &amp;&amp; leAudioService.isPreventA2dpProfile()) {
     *       Log.d(TAG, "Broadcaster is enable, not to set A2dp active device");
     *       leAudioService.setA2dpActiveDevice(device);
     *       return false;
     *   }
     * </pre>
     *
     * <p>{@code mPreventA2dpProfile} is only ever set by
     * {@code LeAudioService.inactiveA2dpProfile()}, and that method has no
     * caller in either jar -- so on this ROM the guard is wired up but never
     * armed.  Rather than arm a flag with no clear owner (a stale {@code true}
     * would block A2DP for every device, not just this earbud), answer the same
     * question directly: refuse while the AZ100's LE Audio group is the active
     * path.
     *
     * <p>This covers strictly more than the {@code ActiveDeviceManager}
     * refusal above -- notably the direct {@code a2dpService.setActiveDevice()}
     * calls in {@code LeAudioService.activeA2dpProfile()} and in the vendor
     * jar, which never pass through {@code ActiveDeviceManager} at all.  Only
     * activations are refused; {@code null} and non-AZ100 devices pass through.
     */
    private static void hookA2dpActiveGuard(ClassLoader cl) {
        try {
            Class<?> a2dpService = XposedHelpers.findClass(A2DP_SERVICE, cl);
            XposedHelpers.findAndHookMethod(a2dpService, "setActiveDevice",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!classicMustStayDown(device)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("a2dpActivationRefusedAtService");
                        }
                    });
            Logs.trace("dual-coexist: A2dpService.setActiveDevice hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: A2dpService hook failed", t);
            Logs.trace("dual-coexist: A2dpService hook FAILED - " + t);
        }

        try {
            Class<?> a2dpService = XposedHelpers.findClass(A2DP_SERVICE, cl);
            // The connection gate, and the half that keeps A2DP from simply
            // coming back after the disconnect.
            //
            //   // A2dpService.okToConnect()
            //   public boolean okToConnect(BluetoothDevice bluetoothDevice, boolean z) {
            //       if (getAdapterService().isQuietModeEnabled() &amp;&amp; !z) return false;
            //       if (!connectionAllowedCheckMaxDevices(bluetoothDevice)) return false;
            //       int connectionPolicy = getConnectionPolicy(bluetoothDevice);
            //       if (connectionPolicy == -1 || connectionPolicy == 100) return true;   // &lt;-- ours
            //       ...
            //
            // Both directions come through here -- the outgoing funnel
            // ({@code lambda$retriggerContactDownload$0()}, i.e. the public
            // connect API and the vendor's {@code connectEnabledProfiles()}) and
            // the incoming request from the earbud, which
            // {@code A2dpStateMachine} asks about with {@code z == false}.  So a
            // single refusal covers reconnect attempts from either side.
            //
            // Deliberately NOT done by writing
            // {@code setConnectionPolicy(device, A2DP, FORBIDDEN)}: that is
            // persisted into the Bluetooth config file and would survive a
            // reboot, leaving LDAC dead until someone re-enabled it by hand.
            // This refusal lives in the process, so killing Bluetooth -- or
            // simply disabling this module -- restores normal behaviour.
            XposedHelpers.findAndHookMethod(a2dpService, "okToConnect",
                    BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!classicMustStayDown(device)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("a2dpConnectRefused");
                        }
                    });
            Logs.trace("dual-coexist: A2dpService.okToConnect hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: A2dpService.okToConnect hook failed", t);
            Logs.trace("dual-coexist: A2dpService.okToConnect hook FAILED - " + t);
        }

        try {
            // And the outgoing half, which uses a *different* method.
            //
            // A2dpService declares only the two-argument okToConnect() above;
            // the outgoing funnel calls the one-argument version it inherits
            // from ConnectableProfile:
            //
            //   // A2dpService.lambda$retriggerContactDownload$0()  (the connect funnel)
            //   if (!okToConnect(bluetoothDevice)) return false;      // 1 arg -&gt; super
            //
            //   // A2dpStateMachine (incoming, from the earbud)
            //   if (!mA2dpService.okToConnect(mDevice, false)) ...    // 2 args -&gt; A2dpService
            //
            // so covering only one of them would leave the other direction open.
            // The one-argument method is declared on ConnectableProfile and is
            // shared with HFP, HID, LE Audio, Hearing Aid and friends, hence the
            // instance check -- without it this would start rejecting their
            // connections too.
            Class<?> connectable = XposedHelpers.findClass(CONNECTABLE_PROFILE, cl);
            Class<?> a2dpService = XposedHelpers.findClass(A2DP_SERVICE, cl);
            XposedHelpers.findAndHookMethod(connectable, "okToConnect",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!a2dpService.isInstance(param.thisObject)) return;
                            if (!isAz100Arg(param.args[0])) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!classicMustStayDown(device)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("a2dpConnectRefused");
                        }
                    });
            Logs.trace("dual-coexist: ConnectableProfile.okToConnect hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: ConnectableProfile.okToConnect hook failed", t);
            Logs.trace("dual-coexist: ConnectableProfile.okToConnect hook FAILED - " + t);
        }
    }

    /**
     * Should the classic audio profiles stay down for this device?
     *
     * <p>True while either of the two independent signals says LE Audio owns the
     * audio path: the device is in {@link #EXCLUSIVE}, or the live LE Audio
     * group still holds a connected AZ100.
     *
     * <p>Used for both A2DP and HFP.  HFP needs it just as much: measured on
     * device, after A2DP had been taken away HFP simply took the slot instead
     * (17:10:45 {@code handleHfpActiveDeviceChanged: 84:85}) and every audio
     * policy strategy moved to {@code bt_sco_hs} -- with {@code mAudioState: 10}
     * (audio not connected), i.e. media pointed at an output that carries
     * nothing.  The earbud reaches the phone through one of the two classic
     * profiles or through LE Audio; there is no arrangement in which LE Audio
     * holds the path and a classic profile should hold it too.
     */
    private static boolean classicMustStayDown(BluetoothDevice device) {
        if (isReleasing(device)) return false;
        if (isExclusive(device)) return true;
        Object lea = leaServiceRef;
        return lea != null && leaGroupHasAz100OnService(lea);
    }

    /**
     * True while this class is itself asking A2DP to come back for the device.
     * See {@link #RELEASING} -- without this the release refuses itself.
     */
    private static boolean isReleasing(BluetoothDevice device) {
        if (device == null) return false;
        String address = device.getAddress();
        return address != null && RELEASING.contains(address);
    }

    /**
     * Is this device currently held down by an LE Audio exclusive session?
     *
     * <p>Both identities of the earbud have to be recognised.  The group is keyed
     * by the LE-only address ({@code 3C:62}), but {@code okToConnect} and
     * {@code setActiveDevice} are reached with the classic one ({@code 84:85}) --
     * so a plain {@code EXCLUSIVE.containsKey(address)} never matched, and this
     * signal was silently dead.  Only
     * {@link #leaGroupHasAz100OnService} was actually holding A2DP down.
     *
     * <p>The reverse lookup is bounded by {@code EXCLUSIVE}'s size (one or two
     * entries) and costs a map read per entry, which is fine for a hook that runs
     * on every connect attempt.
     */
    private static boolean isExclusive(BluetoothDevice device) {
        if (device == null) return false;
        String address = device.getAddress();
        if (address == null) return false;
        if (EXCLUSIVE.containsKey(address)) return true;
        for (String le : EXCLUSIVE.keySet()) {
            if (address.equalsIgnoreCase(A2DP_PEER.get(le))) return true;
        }
        return false;
    }

    /**
     * Keeps HFP from taking the slot that A2DP just vacated.
     *
     * <p>Hooking A2DP alone is not enough.  Measured on device, nine seconds
     * after LE Audio activated the classic side simply switched profiles:
     *
     * <pre>
     *   17:10:35.642 handleLeAudioActiveDeviceChanged: 3C:62      &lt;- LE Audio active
     *   17:10:36.079 handleHfpActiveDeviceChanged: null           &lt;- ROM clears HFP
     *   17:10:45.000 handleHfpActiveDeviceChanged: 84:85          &lt;- HFP takes it back
     * </pre>
     *
     * and every strategy followed it to {@code bt_sco_hs} while
     * {@code HeadsetService mAudioState: 10} -- audio not connected, so media was
     * pointed at an output carrying nothing.
     *
     * <p>The veto is the ROM's own: {@code setHfpActiveDevice()} starts with
     *
     * <pre>
     *   if (!this.mOplusAdmExt.oplusSetHfpActiveDevice(bluetoothDevice)) {
     *       return false;
     *   }
     * </pre>
     *
     * and {@code oplusSetHfpActiveDevice()} itself returns {@code true}
     * immediately for {@code null}, so clearing the active device is always
     * allowed and only a real hand-over to the AZ100 can be refused.  Refusing
     * costs nothing on calls: with LE Audio active the earbud is still reachable,
     * and its own telephony strategy goes over the LE Audio link.
     */
    private static void hookHfpActiveGuard(ClassLoader cl) {
        try {
            Class<?> admExt = XposedHelpers.findClass(ADM_EXT_IMPL, cl);
            XposedHelpers.findAndHookMethod(admExt, "oplusSetHfpActiveDevice",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!classicMustStayDown(device)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("hfpActivationRefused");
                        }
                    });
            Logs.trace("dual-coexist: oplusSetHfpActiveDevice hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: oplusSetHfpActiveDevice hook failed", t);
            Logs.trace("dual-coexist: oplusSetHfpActiveDevice hook FAILED - " + t);
        }

        try {
            // ...and the place HFP's active device is actually set.
            //
            // The veto above only guards ActiveDeviceManager's own hand-over.
            // Measured on device, HFP became active anyway 13 ms after that veto
            // fired (17:14:43.188 refused, 17:14:43.201
            // "handleHfpActiveDeviceChanged: 84:85"), i.e. some other caller went
            // straight to HeadsetService:
            //
            //   // HeadsetService.setActiveDevice()
            //   public boolean setActiveDevice(BluetoothDevice bluetoothDevice, boolean z) {
            //       if (bluetoothDevice == null) { removeActiveDevice(); return true; }
            //       ...
            //       this.mActiveDevice = bluetoothDevice;
            //       ...
            //
            // The one-argument overload delegates here, so this single hook covers
            // every route.  Null still passes, so clearing keeps working.
            Class<?> headsetService = XposedHelpers.findClass(HEADSET_SERVICE, cl);
            XposedHelpers.findAndHookMethod(headsetService, "setActiveDevice",
                    BluetoothDevice.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (!classicMustStayDown(device)) return;
                            param.setResult(Boolean.FALSE);
                            noteOnce("hfpSetActiveRefused");
                        }
                    });
            Logs.trace("dual-coexist: HeadsetService.setActiveDevice hook installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: HeadsetService.setActiveDevice hook failed", t);
            Logs.trace("dual-coexist: HeadsetService.setActiveDevice hook FAILED - " + t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 8. LE Audio exclusivity                                             */
    /* ------------------------------------------------------------------ */

    /**
     * LE Audio owns the audio path -- take A2DP down for that earbud.
     *
     * <h2>Why this is necessary</h2>
     *
     * The vendor design is "classic audio is the primary link, LE Audio is the
     * guest": with dual mode on, both profiles stay connected and the audio
     * policy engine hands the media strategies to whichever of the two is
     * <em>active</em>.  There is no preferred device of its own, so A2DP simply
     * wins whenever it is there:
     *
     * <pre>
     *   Preferred devices for strategy:      (empty)
     *   Applied devices roles for strategies (internal):
     *     strategy: 0 role:2 devices:[... type:bt_a2dp ...]     &lt;- media
     * </pre>
     *
     * Measured on device: with A2DP connected, LE Audio activated, then lost
     * the slot 1.4 s later ({@code 16:42:42.298 handleA2dpActiveDeviceChanged:
     * 84:85}); with A2DP absent ({@code mA2dpConnectedDeviceNum: 0}) the same
     * hand-over held and media came out over LC3
     * ({@code 16:57 currentlyActiveGroupId: 1, mActiveAudioOutDevice: 84:85
     * isLeOutput: true, codec: 2b000000}).  So the only reliable way to keep LE
     * Audio is to remove A2DP from the picture while it owns the path.
     *
     * <h2>What it does, and what it deliberately does not</h2>
     *
     * <ol>
     *   <li>{@code A2dpService.disconnect(device)} -- the earbud drops A2DP, so
     *       {@code AudioDeviceBroker} removes the {@code bt_a2dp} output and the
     *       media strategies have nowhere else to go.</li>
     *   <li>{@link #hookA2dpActiveGuard} then refuses both
     *       {@code setActiveDevice()} and {@code okToConnect()} for this device,
     *       so A2DP cannot quietly come back.</li>
     * </ol>
     *
     * <p>It does <em>not</em> touch the connection policy, and it does not use
     * the ROM's own {@code LeAudioService.inactiveA2dpProfile()}.  Both were
     * considered and rejected:
     *
     * <ul>
     *   <li>A {@code CONNECTION_POLICY_FORBIDDEN} write is persisted into the
     *       Bluetooth config file, so it would outlive a reboot and leave LDAC
     *       dead until manually restored.</li>
     *   <li>{@code inactiveA2dpProfile()} arms {@code mPreventA2dpProfile},
     *       which makes {@code OplusA2dpServiceExtImpl.oplusSetActiveBefore()}
     *       call {@code leAudioService.setA2dpActiveDevice(device)} -- and that
     *       ends in {@code LeAudioService.setActiveDevice(null)}, i.e. it tears
     *       down the very LE Audio group it was supposed to protect.  It is a
     *       feedback loop, not a guard, for this headset.</li>
     * </ul>
     *
     * <p>Everything here is in-process state, so disabling the module or
     * restarting Bluetooth restores stock behaviour immediately.
     */
    private static void scheduleExclusiveEnter(final BluetoothDevice device) {
        if (device == null) return;
        final String address = device.getAddress();
        if (address == null) return;
        if (EXCLUSIVE.putIfAbsent(address, device) != null) return;

        DEFERRED.schedule(() -> {
            Object lea = leaServiceRef;
            // Re-check the premise before acting on it.  The activation this was
            // queued from may already have been undone -- and disconnecting
            // A2DP for an earbud that no longer has LE Audio would leave it with
            // no audio path at all.
            if (!activeGroupHasConnectedAz100(lea)) {
                EXCLUSIVE.remove(address);
                noteOnce("leaExclusiveAborted@" + address);
                return;
            }
            Object a2dp = a2dpServiceOf(lea);
            if (a2dp == null) {
                EXCLUSIVE.remove(address);
                noteOnce("leaExclusiveNoA2dp@" + address);
                return;
            }
            // A2DP is keyed by the earbud's classic identity, which for this
            // headset is not the address the LE Audio group used -- see
            // a2dpPeerOf().  disconnect() looks the device up in its own state
            // machine table and silently logs "no state machine" for an LE-only
            // address, so the enter path needs the same resolution as the exit.
            BluetoothDevice classic = a2dpPeerOf(device);
            if (classic != null) {
                try {
                    XposedHelpers.callMethod(a2dp, "disconnect", classic);
                    noteOnce("leaExclusiveOn@" + address);
                } catch (Throwable t) {
                    EXCLUSIVE.remove(address);
                    Logs.trace("dual-coexist: A2DP disconnect failed - " + t);
                    return;
                }
            } else {
                // Nothing classic to take down; the LE Audio path is still the
                // right one, so keep the entry and let the guards stand.
                noteOnce("leaExclusiveOn@" + address);
            }
            // HFP holds the active slot just as readily as A2DP did, and unlike
            // A2DP it is set from inside HeadsetService rather than through
            // ActiveDeviceManager, so vetoing the hand-over is not enough --
            // something has to take the slot away once LE Audio has it.
            //
            // This is what the ROM itself does when dual mode is off
            // (ActiveDeviceManager.handleLeAudioConnected():
            // "setA2dpActiveDevice(null, false); setHfpActiveDevice(null);"),
            // reached through HeadsetService.removeActiveDevice(), which is
            // public and does the whole job -- stops voice recognition and
            // virtual calls, drops SCO audio, clears the native active device
            // and broadcasts the change.
            //
            // It has to come *after* the A2DP disconnect, because that is what
            // provokes the fallback: measured on device, HFP took the slot 565 ms
            // after the disconnect went in.
            clearHfpActiveDevice(lea, "hfpActiveCleared");
            reassertLeAudioOutput(lea, "leaOutputReasserted");
            // ...and the fallback is not instantaneous, so check once more after
            // it has had time to happen.  Bounded on purpose -- a second pass,
            // never a retry loop.
            DEFERRED.schedule(() -> {
                if (!EXCLUSIVE.containsKey(address)) return;
                Object live = leaServiceRef;
                if (!activeGroupHasConnectedAz100(live)) return;
                clearHfpActiveDevice(live, "hfpActiveReasserted");
                reassertLeAudioOutput(live, "leaOutputReassertedAgain");
            }, HFP_REASSERT_MS, TimeUnit.MILLISECONDS);
        }, EXCLUSIVE_SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Takes the HFP active slot away from the earbud, so the audio policy
     * strategies have LE Audio as their only candidate.
     *
     * <p>Deliberately skipped while call audio is live: {@code removeActiveDevice()}
     * calls {@code disconnectAudio()}, and silently dropping a running call to
     * win an audio-routing argument is not a trade worth making.  In that case
     * the strategies stay on SCO for the duration of the call, which is what
     * would have happened anyway.
     */
    private static void clearHfpActiveDevice(Object leaService, String reason) {
        Object headset = headsetServiceOf(leaService);
        if (headset == null) return;
        try {
            if (hfpCallAudioLive(headset)) {
                noteOnce("hfpActiveKeptInCall");
                return;
            }
            XposedHelpers.callMethod(headset, "removeActiveDevice");
            noteOnce(reason);
        } catch (Throwable t) {
            Logs.trace("dual-coexist: HFP active clear failed - " + t);
        }
    }

    /**
     * Is HFP carrying call audio right now?
     *
     * <p>{@code BluetoothHeadset.STATE_AUDIO_CONNECTED} is {@code 12}; anything
     * else (including the {@code 10} of "disconnected") means the SCO link is
     * idle and safe to tear down.
     */
    private static boolean hfpCallAudioLive(Object headsetService) {
        if (headsetService == null) return false;
        try {
            Object active = XposedHelpers.callMethod(headsetService, "getActiveDevice");
            if (!(active instanceof BluetoothDevice)) return false;
            Object state = XposedHelpers.callMethod(headsetService, "getAudioState", active);
            return state instanceof Integer && ((Integer) state).intValue() == 12;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Tells AudioService, one last time, that LE Audio owns the output.
     *
     * <p>Clearing a classic active device <em>is</em> a Bluetooth active-device
     * change, and AudioService re-evaluates the output whenever it sees one.
     * Measured on device, the LE Audio device came up correctly and was then
     * downgraded 425 ms later, immediately after the HFP clear:
     *
     * <pre>
     *   17:18:49:590 BT connected: 3C:62 isLeOutput=true  codec=AUDIO_FORMAT_LC3
     *   17:18:49:853 stopBluetoothSco: setBluetoothActiveDevice
     *   17:18:50:015 setBluetoothActiveDevice: 3C:62 isLeOutput=false volume=-1
     *   17:18:50:017 BT connected: 3C:62 isLeOutput=false codec=AUDIO_FORMAT_DEFAULT
     * </pre>
     *
     * <p>So ordering is the whole game: whoever touches AudioService last wins.
     * {@code sendPreferredAudioProfileChangeToAudioFramework()} is the ROM's own
     * re-announce -- it rebuilds the output info from scratch with
     * {@code createLeAudioOutputInfo(true, groupVolume)} -- so calling it after
     * the classic teardown puts LE Audio back on top with the LE flag and the
     * group volume intact.
     *
     * <p>Its return value is the number of announcements it made; {@code 0} means
     * LE Audio had no active device at all, which is worth seeing in the log.
     */
    private static void reassertLeAudioOutput(Object leaService, String reason) {
        if (leaService == null) return;
        try {
            Object count = XposedHelpers.callMethod(leaService,
                    "sendPreferredAudioProfileChangeToAudioFramework");
            noteOnce(reason + "=" + count);
        } catch (Throwable t) {
            Logs.trace("dual-coexist: LE Audio output re-assert failed - " + t);
        }
    }

    /** The live {@code HeadsetService}, via the LE Audio service's own adapter. */
    private static Object headsetServiceOf(Object leaService) {
        if (leaService == null) return null;
        try {
            Object adapter = XposedHelpers.callMethod(leaService, "getAdapterService");
            if (adapter == null) return null;
            return optionalGet(XposedHelpers.callMethod(adapter, "getHeadsetService"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * LE Audio no longer owns the path -- let A2DP come back.
     *
     * <p>Queued from the group-deactivation afterHook and from the LE
     * disconnect callback, and de-duplicated so the two firing together only
     * produce one pass.  Unlike {@link #scheduleExclusiveEnter} this one does
     * <em>not</em> re-check the premise: whatever the reason, an entry left in
     * {@link #EXCLUSIVE} would block A2DP for the rest of the process's life,
     * so the safe direction is always "release".
     */
    private static void scheduleExclusiveExit() {
        if (EXCLUSIVE.isEmpty() || exitScheduled) return;
        exitScheduled = true;
        DEFERRED.schedule(() -> {
            try {
                doExclusiveExit();
            } catch (Throwable t) {
                Logs.trace("dual-coexist: LE Audio exclusive exit failed - " + t);
            } finally {
                exitScheduled = false;
            }
        }, EXCLUSIVE_SETTLE_MS, TimeUnit.MILLISECONDS);
    }

    private static void doExclusiveExit() {
        Object lea = leaServiceRef;
        Object a2dp = a2dpServiceOf(lea);
        for (Map.Entry<String, BluetoothDevice> entry : EXCLUSIVE.entrySet()) {
            BluetoothDevice device = entry.getValue();
            if (a2dp == null) {
                EXCLUSIVE.remove(entry.getKey());
                continue;
            }
            // Only reconnect while the earbud is actually gone from LE Audio.
            // If it is still LE-connected, the group going inactive was a
            // transient and the stack is about to bring LE Audio back -- racing
            // A2DP up against that would just flap the two against each other.
            // The LE disconnect callback calls this again once it really leaves.
            if (leDeviceConnected(lea, device)) {
                noteOnce("leaExclusiveHeld@" + entry.getKey());
                continue;
            }
            // A2DP lives on the earbud's classic identity, not necessarily on the
            // one the LE Audio group was keyed by -- see a2dpPeerOf().  Asking
            // connect() for an LE-only address always fails.
            BluetoothDevice target = a2dpPeerOf(device);
            if (target == null) {
                EXCLUSIVE.remove(entry.getKey());
                noteOnce("leaExclusiveNoA2dpPeer@" + entry.getKey());
                continue;
            }
            // Mark the release before the call: connect() re-enters
            // classicMustStayDown() through the okToConnect hook, and without
            // this the request would be refused by our own guard.  The entry
            // itself is removed only after the request has gone in, so a failed
            // call does not silently clear the bookkeeping -- measured
            // 2026-10-13, see A2DP_CONNECT and RELEASING.
            RELEASING.add(target.getAddress());
            try {
                Object started = XposedHelpers.callMethod(a2dp, A2DP_CONNECT, target);
                EXCLUSIVE.remove(entry.getKey());
                noteOnce("leaExclusiveOff@" + entry.getKey());
                Logs.trace("dual-coexist: A2DP reconnect requested for "
                        + mask(target.getAddress()) + ", started=" + started);
                if (Boolean.FALSE.equals(started)) {
                    // Accepted by the guard but the state machine declined (no
                    // A2DP Sink UUID, max devices, ...).  Nothing more to do.
                    noteOnce("a2dpReconnectDeclined@" + entry.getKey());
                }
            } catch (Throwable t) {
                // Still never leave the entry behind -- a stuck entry blocks A2DP
                // for the rest of the process's life, which is the failure this
                // whole class exists to avoid.  Release it and retry once instead.
                EXCLUSIVE.remove(entry.getKey());
                Logs.e("dual-coexist: A2DP reconnect failed", t);
                noteOnce("a2dpReconnectFailed@" + entry.getKey());
                retryA2dpConnect(a2dp, target, entry.getKey());
            } finally {
                RELEASING.remove(target.getAddress());
            }
        }
    }

    /**
     * One bounded second attempt at bringing A2DP back, after the first one
     * threw.  Never a loop: the entry is already released by the caller, so a
     * second failure only leaves classic audio down until the next connect --
     * it cannot pin A2DP off the way a retained {@link #EXCLUSIVE} entry would.
     */
    private static void retryA2dpConnect(final Object a2dp,
                                         final BluetoothDevice device,
                                         final String key) {
        DEFERRED.schedule(() -> {
            RELEASING.add(key);
            try {
                Object started = XposedHelpers.callMethod(a2dp, A2DP_CONNECT, device);
                Logs.trace("dual-coexist: A2DP reconnect retry started=" + started);
                noteOnce("a2dpReconnectRetried@" + key);
            } catch (Throwable t) {
                Logs.e("dual-coexist: A2DP reconnect retry failed", t);
            } finally {
                RELEASING.remove(key);
            }
        }, HFP_REASSERT_MS, TimeUnit.MILLISECONDS);
    }

    /** The live {@code A2dpService}, via the LE Audio service's own adapter. */
    private static Object a2dpServiceOf(Object leaService) {
        if (leaService == null) return null;
        try {
            Object adapter = XposedHelpers.callMethod(leaService, "getAdapterService");
            if (adapter == null) return null;
            return optionalGet(XposedHelpers.callMethod(adapter, "getA2dpService"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Is this device connected to LE Audio right now? */
    private static boolean leDeviceConnected(Object leaService, BluetoothDevice device) {
        if (leaService == null || device == null) return false;
        try {
            Object state = XposedHelpers.callMethod(leaService, "getConnectionState", device);
            return state instanceof Integer && ((Integer) state).intValue() == 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 9. reconnect: clear the "already connected" early return            */
    /* ------------------------------------------------------------------ */

    /**
     * Lets a reconnecting AZ100 be activated again.
     *
     * <p>{@code ActiveDeviceManager.handleLeAudioConnected()} keeps its own list
     * of LE Audio peers and bails out on a duplicate, <em>before</em> any of the
     * activation logic:
     *
     * <pre>
     *   // ActiveDeviceManager.lambda$profileConnectionStateChanged$3()
     *   leAudioService.get().deviceConnected(bluetoothDevice);
     *   if (this.mLeAudioConnectedDevices.contains(bluetoothDevice)) {
     *       Log.w(TAG, "This device is already connected: " + bluetoothDevice);
     *       return;                                   // -&gt; nothing else runs
     *   }
     *   this.mLeAudioConnectedDevices.add(bluetoothDevice);
     *   ...
     * </pre>
     *
     * <p>and that list is only ever cleaned in
     * {@code handleLeAudioDisconnected()}.  So when the earbud reconnects while
     * the previous entry is still there -- the measured regression, where the
     * module log showed no activation at all for the second connection -- the
     * whole hand-over is skipped and the audio stays on LDAC.
     *
     * <p>The entry is dropped only while LE Audio does <em>not</em> already own
     * the slot, so a duplicate callback for an already-working connection is
     * left alone.  The removal takes the manager's own {@code mLock}, because
     * the hook runs at method entry, before the method's own
     * {@code synchronized (this.mLock)} block is entered.
     */
    private static void hookLeaConnectedUnstick(ClassLoader cl) {
        try {
            Class<?> adm = XposedHelpers.findClass(ACTIVE_DEVICE_MANAGER, cl);
            XposedHelpers.findAndHookMethod(adm, "lambda$profileConnectionStateChanged$3",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAz100Arg(param.args[0])) return;
                            if (leaGroupHasAz100(param.thisObject)) return;
                            try {
                                Object list = XposedHelpers.getObjectField(param.thisObject,
                                        "mLeAudioConnectedDevices");
                                if (!(list instanceof List)) return;
                                Object lock = XposedHelpers.getObjectField(param.thisObject, "mLock");
                                boolean removed;
                                synchronized (lock) {
                                    removed = ((List<?>) list).remove(param.args[0]);
                                }
                                if (removed) noteOnce("leaConnectedDedupCleared");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Logs.trace("dual-coexist: handleLeAudioConnected unstick installed");
        } catch (Throwable t) {
            Logs.e("dual-coexist: handleLeAudioConnected unstick failed", t);
            Logs.trace("dual-coexist: handleLeAudioConnected unstick FAILED - " + t);
        }
    }

    /* ------------------------------------------------------------------ */

    private static void ensureClassicAllowed(Object extInstance, String adapterField,
                                             BluetoothDevice device) {
        if (Boolean.TRUE.equals(IN_FIX.get())) return;
        IN_FIX.set(Boolean.TRUE);
        try {
            Object adapter = XposedHelpers.getObjectField(extInstance, adapterField);
            if (adapter == null) return;
            restore(adapter, device, PROFILE_HFP);
            restore(adapter, device, PROFILE_A2DP);
        } catch (Throwable t) {
            Logs.trace("dual-coexist: policy restore failed - " + t);
        } finally {
            IN_FIX.set(Boolean.FALSE);
        }
    }

    private static void restore(Object adapter, BluetoothDevice device, int profile) {
        Object current = XposedHelpers.callMethod(adapter,
                "getProfileConnectionPolicy", device, profile);
        if (current instanceof Integer && ((Integer) current).intValue() == POLICY_ALLOWED) {
            return;
        }
        XposedHelpers.callMethod(adapter, "setProfileConnectionPolicy",
                device, profile, POLICY_ALLOWED);
        Logs.trace("dual-coexist: profile " + profile + " ("
                + (profile == PROFILE_HFP ? "hfp" : "a2dp") + ") "
                + current + " -> " + POLICY_ALLOWED);
    }

    private static boolean isAz100(BluetoothDevice device) {
        if (device == null) return false;
        String address = device.getAddress();
        if (address == null) return false;
        if (Az100Hook.isAz100Mac(address)) return true;
        if (Az100Hook.AZ100_LE_MAC.equalsIgnoreCase(address)) return true;
        try {
            String name = device.getName();
            return name != null && name.contains("EAH-AZ100");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAz100Arg(Object arg) {
        return arg instanceof BluetoothDevice && isAz100((BluetoothDevice) arg);
    }

    /** One line per address per reason per process; the hook itself is a hot path. */
    private static void note(BluetoothDevice device, String reason) {
        String address = device.getAddress();
        if (address != null && REPORTED.add(reason + '@' + address)) {
            Logs.trace("dual-coexist: " + mask(address) + " marked LEA-coexistent (" + reason + ")");
        }
    }

    /** Same, for events that carry no address. */
    private static void noteOnce(String reason) {
        if (REPORTED.add(reason)) {
            Logs.trace("dual-coexist: " + reason);
        }
    }

    /**
     * Never log a full address: the module's own traces stay redacted.  See
     * {@code AGENTS.md} -- Bluetooth addresses are device-specific data.
     */
    private static String mask(String address) {
        if (address == null || address.length() < 5) return "?";
        return "**:**:**" + address.substring(address.length() - 5);
    }
}
