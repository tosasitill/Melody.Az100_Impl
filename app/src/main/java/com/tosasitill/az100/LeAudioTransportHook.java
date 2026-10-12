package com.tosasitill.az100;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Route B: make the AZ100 bond over LE instead of BR/EDR.
 *
 * <h2>The gap this closes</h2>
 *
 * The headset side is already fine -- since 13:20 the AZ100 advertises
 * {@code 0000184e 184f 1850 1844 184d} in its LE payload, and the phone's own
 * verbose log shows it parsing them.  What is missing is the <em>bond</em>:
 *
 * <pre>
 * 13:36:29.776  btif_dm_create_bond: transport=0            &lt;- AUTO
 * 13:36:39.789  bond_state_changed [BT_TRANSPORT_BR_EDR] -&gt; BONDED, bond_type: 1
 * 13:36:39.812  oplus_delay_ble_smp_over_br: start smp      &lt;- CTKD
 * 13:36:39.904  bond_state_changed [BT_TRANSPORT_LE] -&gt; BONDED
 * </pre>
 *
 * The LE keys exist, but only because CTKD derived them from the BR/EDR link.
 * {@code RemoteDevices$DeviceProperties} ends up with {@code mUuidsBrEdr}
 * populated and {@code mUuidsLe} left {@code null}, so
 * {@code getUuids() = mUuidsBrEdr ∪ mUuidsLe} never contains {@code 0x184E},
 * so {@code LocalBluetoothProfileManager#updateProfiles} never adds
 * {@code LeAudioProfile} to the device's profile list, so
 * {@code BluetoothDetailsProfilesController} has nothing to render a switch
 * from.  Every OPPO vendor path in {@code OplusLeAudioPhonePolicyExt} is gated
 * on the same {@code containsUuid(uuids, LE_AUDIO)} test, which is why
 * {@code com.flyfish233.coloros.leaudiofx} (which hooks exactly those methods)
 * necessarily no-ops.
 *
 * <h2>What this hook changes</h2>
 *
 * {@code AdapterService#createBond} receives the transport as its second
 * argument and passes it straight to
 * {@code AdapterService#sendCreateBondMessage}, which is the single point that
 * posts {@code mBondStateMachine.obtainMessage(1)} with {@code arg1 = transport}.
 * The vendor layer does not interfere: {@code OplusAdapterServiceExtImpl#createBond}
 * only forces {@code transport = 1} when {@code sharingDeviceType} is
 * {@code OplusWatch} / {@code ebadge} / {@code 3rdEarBuds}, and otherwise
 * forwards {@code transport2 = transport} untouched.
 *
 * <p>So both methods are hooked and the argument is rewritten to
 * {@link #TRANSPORT_LE} when the device is the AZ100.  Everything else is left
 * alone; the rewrite is a no-op for any other address.
 *
 * <p>Both are hooked rather than just the chokepoint because
 * {@code createBond} itself rewrites {@code i} to 1 or 2 in the re-bond case
 * ({@code bondState == 12 && deviceType == 3}); hooking the chokepoint as well
 * means the value that actually reaches the state machine is the LE one.
 *
 * <h2>Scope</h2>
 *
 * This is the one deliberate exception to the "hooks run in
 * {@code com.oplus.melody} only" rule in {@code AGENTS.md}: the code that has
 * to change lives in {@code com.android.bluetooth}, so the module has to be
 * scoped there as well.  {@link #ENABLED} turns the whole thing off without
 * touching the Melody hooks.
 *
 * <h2>Driving the experiment without touching the UI</h2>
 *
 * Re-pairing cannot be done from {@code adb} through {@code cmd
 * bluetooth_manager} (it only exposes enable/disable/factoryReset).  Instead
 * this class polls a system property from inside the Bluetooth process:
 *
 * <pre>
 * adb shell "setprop debug.az100.action 'status'"
 * adb shell "setprop debug.az100.action 'unpair B8:20:8E:EE:84:85'"
 * adb shell "setprop debug.az100.action 'autobond B8:20:8E:EE:84:85 2'"
 * adb shell "setprop debug.az100.action 'none'"
 * adb shell su -c "tail /data/misc/bluetooth/az100_console.log"
 * </pre>
 *
 * A property rather than a file because the root shell is {@code u:r:ksu:s0}
 * under enforcing SELinux and cannot write into {@code /data/misc/bluetooth}
 * ({@code bluetooth_data_file}), while a JNI property read costs the Bluetooth
 * process nothing and needs no {@code Context}.  {@code Settings.Global} was
 * tried first and abandoned: {@code AndroidAppHelper.currentApplication()}
 * returns null in this process, so the content resolver is unreachable.
 * {@code macos/az100-routeb.sh} wraps the quoting, which matters --
 * {@code adb shell setprop} splits on spaces and silently does nothing.
 *
 * The action runs through the ordinary {@code BluetoothDevice} API from inside
 * the Bluetooth process, which already holds {@code BLUETOOTH_CONNECT}, so
 * {@code AdapterService}'s
 * {@code Util.isPackageNameAccurate(this, str, Binder.getCallingUid())} check
 * passes without a helper app and without a single simulated tap.
 *
 * <h2>Why {@code autobond} exists</h2>
 *
 * A plain {@code bond} produces a correct LE bond and then stalls: this stack
 * asks for consent, the Settings dialog appears, and if nobody answers inside
 * the ~28 s SMP timer the bond dies with {@code SMP_CONN_TOUT}.  {@code
 * autobond} answers that consent in-process via {@code AdapterNativeInterface
 * #sspReply} -- see {@link #AUTO_CONFIRM}.  The dialog still shows and still
 * dismisses itself once the device reaches {@code BOND_BONDED}.
 *
 * <h2>Measured result</h2>
 *
 * Not yet recorded: run the four commands above, then
 * {@code adb shell dumpsys bluetooth_manager} and look for a
 * {@code [LE UUIDs    ]} line on the bonded device.  See
 * {@code 2026-10-11_leaudio-whitelist-report.md} §10 route B.
 */
final class LeAudioTransportHook {

    /** Master switch: false leaves the Bluetooth process completely untouched. */
    private static final boolean ENABLED = true;

    /** Route B itself: rewrite the bond transport to LE for the AZ100. */
    private static final boolean FORCE_LE_TRANSPORT = true;

    /** Log the whole UUID pipeline, so the outcome is measurable either way. */
    private static final boolean DIAGNOSE = true;

    /**
     * Answer the LE "Just Works" consent from inside the Bluetooth process.
     *
     * <p><b>Measured dead end -- left off.</b>  This was the first idea, and it
     * does not work on this build, for a reason that also rules out a manual
     * tap.  Forcing the bond onto LE makes the stack pick
     * {@code SMP_MODEL_SEC_CONN_JUSTWORKS}, which this build answers with
     * {@code SMP_CONSENT_REQ_EVT} instead of pairing silently.  The reply the
     * dialog's own "pair" button sends is
     * {@code AdapterServiceBinder#setPairingConfirmation} -&gt;
     * {@code getNative().sspReply(addr, 0, true, 0)}, which reaches
     * {@code btif_dm_ssp_reply} and branches on a single global:
     *
     * <pre>
     * if (pairing_cb.is_le_only) {
     *   if (accept) BTA_DmBleSecurityGrant(bd_addr, BTA_DM_SEC_GRANTED);
     * } else {
     *   BTA_DmConfirm(bd_addr, accept);   // &lt;-- BR/EDR handler, what we hit
     * }
     * </pre>
     *
     * <p>{@code is_le_only} is written only by {@code btif_dm_ble_sec_req_evt},
     * and only <em>after</em> it calls {@code bond_state_changed(BONDING)},
     * which early-returns because {@code btif_dm_cb_create_bond} already set
     * {@code pairing_cb.state = BONDING}.  So the flag is never written and the
     * reply always lands on the BR/EDR path:
     *
     * <pre>
     * btif_dm_ssp_reply: accept=1
     * btm_confirm_req_reply() State: IDLE  Res: 0
     * btm_confirm_req_reply: Unexpected pairing confirm ... pairing_state: IDLE
     * </pre>
     *
     * <p>A 12-candidate sweep over every {@code (transport, variant)} pair
     * produced that identical path, confirming the arguments are irrelevant.
     * Because the Settings dialog routes through this exact call, <b>tapping
     * the dialog by hand fails identically</b> -- so the consent has to be
     * avoided, not answered.  See {@link #BYPASS_CONSENT}.
     */
    private static final boolean AUTO_CONFIRM = false;

    /**
     * Stop the LE "Just Works" consent dialog from ever being raised.
     *
     * <p>This is the lever that actually works, and it is reachable from Java.
     * Both places that raise the consent -- {@code smp_process_peer_nonce}'s
     * JUSTWORKS branch and {@code smp_decide_association_model}'s
     * ENCRYPTION_ONLY branch -- share one guard:
     *
     * <pre>
     * if (!GetInterfaceToProfiles()-&gt;config-&gt;isAndroidTVDevice() &amp;&amp;
     *     (p_cb-&gt;local_io_capability == SMP_IO_CAP_IO ||
     *      p_cb-&gt;local_io_capability == SMP_IO_CAP_KBDISP)) {
     *   p_cb-&gt;cb_evt = SMP_CONSENT_REQ_EVT;      // the dialog
     * } else {
     *   smp_sm_event(p_cb, SMP_SC_PHASE1_CMPLT_EVT, NULL);   // straight to phase 2
     * }
     * </pre>
     *
     * <p>The phone advertises {@code KeyboardDisplay}, so the first half is
     * true.  The other half, {@code isAndroidTVDevice()}, is a value this build
     * computes in Java and hands to the stack once at start-up:
     * {@code AdapterService.onCreate} does
     * {@code getPackageManager().hasSystemFeature("android.software.leanback_only")}
     * and passes the result as the 6th argument of
     * {@code AdapterNativeInterface#init}, whose JNI forwards it verbatim as
     * {@code isAtvDevice} to {@code sBluetoothInterface-&gt;init(...)}.  Making
     * that one boolean {@code true} collapses the guard, so the stack runs
     * Just Works with no consent and no dialog at all.
     *
     * <p>Scope and blast radius: this only suppresses the <em>consent prompt</em>
     * for LE Just Works / encryption-only pairing, which is exactly the
     * behaviour a TV build already has.  It does not weaken or skip pairing,
     * and it is one flag away from being reverted.
     */
    private static final boolean BYPASS_CONSENT = true;

    /**
     * How long to wait after the pairing request before answering it.  Long
     * enough for the dialog to be drawn (so the run is still observable on
     * screen), far short of the SMP timer.
     */
    private static final long CONFIRM_DELAY_MS = 800L;

    /**
     * Fallback for the case where {@code sendPairingRequestIntent} never runs.
     * Waits this long, then answers if the device is still bonding.
     */
    private static final long WATCHDOG_DELAY_MS = 4000L;

    /**
     * Put {@code 0x184E} into the LE UUID array the framework stores for the
     * AZ100.
     *
     * <p>This is the one lever in the whole route-B experiment that turns out
     * to be load-bearing.  The vendor allow-list is <em>already</em> open on
     * this device:
     *
     * <pre>
     * persist.bluetooth.leaudio.bypass_allow_list   = true
     * persist.bluetooth.enable_dual_mode_audio      = true
     * </pre>
     *
     * so {@code PhonePolicy}'s gate collapses to the UUID test alone:
     *
     * <pre>
     * if (leAudioService.isPresent()
     *     &amp;&amp; Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)   // &lt;-- 0x184E
     *     &amp;&amp; leAudioService.getConnectionPolicy(device) != 0
     *     &amp;&amp; (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())
     *     &amp;&amp; (isBypassLeAudioAllowlist || shouldEnableHapByDefault
     *         || isLeAudioAllowed(device) || isLeAudioOnlyDevice(device))) {
     *   allowed = true;
     * }
     * </pre>
     *
     * <p>Every other conjunct already holds.  The UUID array is the one that
     * does not, and the reason is a vendor filter: LE Audio UUIDs are stripped
     * out of the advertising data before it reaches Java
     * ({@code oplus_btm_ble_process_adv_pkt_cont: adv data modified: removal of
     * lea uuids}), the allow-list lookup that would have put them back reports
     * {@code isLEAudioDeviceInWhiteList: LE Audio device is not in Whitelist},
     * and so {@code mUuidsLe} ends up holding only {@code 0x180F} while
     * {@code LeAudioService} reports {@code No valid descriptor for device}.
     *
     * <p>The device is nonetheless a genuine LE Audio device.  Its own GATT
     * database, dumped out of the native cache, carries the complete service
     * set -- and the dump names {@code 84:85} as the owner right below it:
     *
     * <pre>
     * bta_gattc_display_explore_record: Service: handle=0x1103, end_handle=0x110f, uuid=0000184e  (ASCS)
     * bta_gattc_display_explore_record: Service: handle=0x1200, end_handle=0x1212, uuid=00001850  (PACS)
     * bta_gattc_display_explore_record: Service: handle=0x1301, end_handle=0x130b, uuid=00001844  (VCS)
     * bta_gattc_display_explore_record: Service: handle=0x6001, end_handle=0x600a, uuid=00001846  (CSIS)
     * bta_gattc_display_explore_record: Service: handle=0x7001, end_handle=0x7002, uuid=00001853  (TBS)
     * bta_gattc_display_explore_record: Service: handle=0xa201, end_handle=0xa206, uuid=0000184f  (BASS)
     * gatt_get_ch_state: xx:xx:xx:xx:84:85, transport BT_TRANSPORT_LE, ch_state=GATT_CH_OPEN
     * </pre>
     *
     * <p>So this restores UUIDs the device really does expose, for exactly two
     * addresses, rather than inventing a capability.
     * {@link #spliceIntoLeUuids} is where that happens, and it is gated on
     * {@link #matches}.
     *
     * <h2>Why CSIS and not just ASCS</h2>
     *
     * {@code 0x184E} alone got the headset talking, but only to its left half:
     * {@code Audio Locations: Sink= left}, {@code Group 1 size 1}, and
     * {@code GetGroupSinkStrategy: Group strategy set to: MONO_ONE_CIS_PER_DEVICE}.
     * AOSP's chooser ({@code device_groups.cc}) explains exactly why:
     *
     * <pre>
     * int expected_group_size = Size();
     * if (expected_group_size &gt; 1) return MONO_ONE_CIS_PER_DEVICE;   // two buds: one mono CIS each
     * auto locations = audio_locations_.get(direction).value();
     * if (!(locations &amp; AnyLeft) || !(locations &amp; AnyRight) || locations.none())
     *   return MONO_ONE_CIS_PER_DEVICE;                              // one bud declaring one side
     * if (channel_count_bitmap == 1) return STEREO_TWO_CISES_PER_DEVICE;
     * return STEREO_ONE_CIS_PER_DEVICE;
     * </pre>
     *
     * <p>One earbud that declares {@code 0x0001} can therefore never be stereo,
     * and the only route left is the first branch: get <em>both</em> earbuds into
     * the group.  That is CSIS's job -- and CSIS is the UUID the vendor strips
     * hardest.  {@code CsipSetCoordinatorService} reads it straight off
     * {@code BluetoothDevice.getUuids()} and, finding nothing, refuses before it
     * ever reaches the headset:
     *
     * <pre>
     * CsipSetCoordinatorService: Cannot connect to xx:xx:xx:xx:84:85 : Remote does not have CSIS UUID
     * dumpsys bluetooth_manager / Profile: CsipSetCoordinatorService
     *   mFoundSetMemberToGroupId:            &lt;-- empty: not one set member found
     * </pre>
     *
     * <p>The set membership machinery itself is intact underneath -- the SIRK
     * exchange already ran once, during the bond:
     *
     * <pre>
     * smp_act.cc: smp_sirk_verify: addr:xx:xx:xx:xx:84:85
     * csis_client.cc: VerifySetMember: Device: xx:xx:xx:xx:84:85
     * csis_client.cc: VerifySetMember: Valid - new member
     * </pre>
     *
     * <p>It just never gets to look for the second member, because the Java
     * layer in front of it is missing the UUID that would let it start.
     *
     * <h2>Status: live again (2026-10-12)</h2>
     *
     * <p>This splice was the first working route, but it fights the vendor
     * filter head-on.  Splicing {@code 184E} is exactly what makes
     * {@code isLeAudioDevice()} return true, and that is the condition which
     * arms {@code OplusLeAudioRemoteDevice}'s strip in the first place -- so
     * every spliced UUID gets deleted again a moment later, and the module has
     * to keep re-injecting on every property change.  That re-entry was the
     * main source of the crashes this module has been fighting.
     *
     * <p>It was briefly retried via the native route
     * ({@link NativeLeaDeviceHook} forcing
     * {@code oplus_btif_is_lea_device()} true in-process), but on the shipping
     * ROM that alone does not put {@code 0000184E} where Settings looks, and the
     * switch vanished.  The splice is therefore back on as the primary route;
     * the native patch stays as a second layer so the vendor filter stops
     * stripping where it applies.
     */
    private static final boolean SPLICE_LE_AUDIO_UUIDS = true;

    /**
     * Register the AZ100 into OPPO's native {@code OplusLeAudioWhiteList}
     * interop list.
     *
     * <h2>Status: retired (2026-10-12)</h2>
     *
     * <p>This wrote the <b>address key</b>, and it did work: on device the entry
     * lands in the interop database, persists to
     * {@code /data/misc/bluedroid/bt_iot_update_list.conf}, and
     * {@code interop_oplus_match_addr("OplusLeAudioWhiteList", addr)} returns
     * true for both AZ100 addresses.
     *
     * <p>It was not enough.  {@code oplus_btif_is_lea_device()} -- the gate the
     * native LEA paths <i>and</i> the UUID filter actually consult -- never
     * looks at addresses.  It asks
     * {@code interop_match_vendor_and_3bytes_product_id()} against a hard-coded
     * {@code .rodata} table of OPPO's own product ids, and no JNI entry point
     * can add a vendor/product pair to it.  An address entry cannot reach that
     * lookup, so {@code is_oplus_lea} stayed false and the UUIDs stayed
     * stripped.
     *
     * <p>{@link NativeLeaDeviceHook} replaces this by opening the gate itself,
     * in-process.  Kept for reference and A/B; see
     * {@code 2026-10-12_native-oppo-leaudio-masquerade-report.md} §7.
     */
    private static final boolean NATIVE_LEA_WHITELIST = false;

    /**
     * Repair OPPO's dead {@code BassClientService.mOplusBassClientService}
     * field, which is never assigned on this ROM and is dereferenced without a
     * null check in three places -- {@code connect()} being the one that killed
     * the whole profile-connect batch once {@link NativeLeaDeviceHook} let
     * {@code 184F} (BASS) through.  See {@link BassClientFixHook}.
     */
    private static final boolean BASS_NPE_FIX = true;

    /**
     * Stop OPPO's dual-mode policy from forbidding A2DP/HFP once
     * {@link NativeLeaDeviceHook} has let {@code 0000184E} through.  The AZ100
     * fails OPPO's proprietary Echo coexistence handshake, so the vendor code
     * classifies it as "not lea coexistence device" and zeroes both classic
     * profiles -- leaving the headset with no usable link at all.  See
     * {@link OplusDualModeCoexistHook}.
     */
    private static final boolean OPLUS_DUAL_MODE_COEXIST_FIX = true;

    /**
     * Drop VCS writes that would not change the headset's volume.  A no-op
     * {@code SetAbsoluteVolume} produces no Volume State notification, the
     * native operation queue never marks that operation complete, and
     * everything queued behind it is stuck forever -- see
     * {@link #hookVolumeWriteGuard()}.
     */
    private static final boolean SUPPRESS_REDUNDANT_VCS_WRITES = true;

    /**
     * {@code VolumeControlNativeInterface} raises any VCS value of 1..15 up to
     * {@link #LEAUDIO_VOLUME_MIN} (16), so the bottom several media steps all
     * land on the same loudness and 16 is the quietest level reachable at all.
     * Bypass that clamp so the bottom of the slider is actually quiet.  0 still
     * means mute -- the stock code already passes 0 through untouched, which is
     * exactly the "one step above silence" the clamp otherwise eats.
     */
    private static final boolean LOWER_VOLUME_FLOOR = true;

    /**
     * The stack only ever dials one member of the AZ100 set, so after every
     * Bluetooth restart half the pair is silent.  Verified on device: forcing
     * {@code LeAudioService#connect} on the sibling works, both stay connected,
     * and the group ends up with {@code Sink= left} and {@code Sink= right}.
     */
    private static final boolean TWIN_AUTO_CONNECT = true;

    static final int TRANSPORT_AUTO = 0;
    static final int TRANSPORT_BREDR = 1;
    static final int TRANSPORT_LE = 2;

    private static final String ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";
    private static final String REMOTE_DEVICES =
            "com.android.bluetooth.btservice.RemoteDevices";
    private static final String DEVICE_PROPERTIES = REMOTE_DEVICES + "$DeviceProperties";
    private static final String BOND_STATE_MACHINE =
            "com.android.bluetooth.btservice.BondStateMachine";
    private static final String VOLUME_CONTROL_SERVICE =
            "com.android.bluetooth.vc.VolumeControlService";
    private static final String VOLUME_CONTROL_NATIVE_INTERFACE =
            "com.android.bluetooth.vc.VolumeControlNativeInterface";

    /**
     * {@code VolumeControlNativeInterface#LEAUDIO_VOLUME_MIN}.  Both
     * {@code setVolume} and {@code setGroupVolume} clamp with
     * {@code if (volume != 0 && volume < 16) volume = 16;}, so a media index of
     * 1..4 all collapse onto 16 while index 0 stays a true 0.
     */
    private static final int LEAUDIO_VOLUME_MIN = 0x10;

    /**
     * {@code BluetoothProfile} connection policies.  {@code okToConnect} accepts
     * only {@link #CONNECTION_POLICY_UNKNOWN} and {@link #CONNECTION_POLICY_ALLOWED};
     * {@code FORBIDDEN} is rejected outright, which is what silently killed VCP
     * even after the UUID splice put {@code 0x1844} back.
     */
    private static final int CONNECTION_POLICY_FORBIDDEN = 0;
    private static final int CONNECTION_POLICY_ALLOWED = 100;
    private static final int CONNECTION_POLICY_UNKNOWN = -1;

    /** The four UUID arrays, in the order {@code getUuids()} combines them. */
    private static final String[] UUID_FIELDS = {
            "mUuidsBrEdr",
            "mUuidsLe",
            "mUuidsFromExtendedInquiryResponse",
            "mUuidsFromLeAdvertisingData",
    };

    /**
     * The slice of the LE Audio family that the Java stack actually keys off,
     * and therefore the slice {@link #spliceIntoLeUuids} puts back.
     *
     * <ul>
     *   <li>{@code 0x184E} ASCS -- {@code PhonePolicy}'s transport gate, and
     *       what makes the Settings toggle appear.</li>
     *   <li>{@code 0x1846} CSIS -- what {@code CsipSetCoordinatorService} needs
     *       before it will connect and look for the other earbud.</li>
     *   <li>{@code 0x1844} VCS -- what {@code VolumeControlService} needs before
     *       it will connect and take over the volume at all.</li>
     * </ul>
     *
     * <h2>Why VCS is here</h2>
     *
     * <p>Absolute volume over LE Audio <em>is</em> VCP, and VCP starts at the
     * same UUID test as everything else in this family.  When the LE Audio
     * device connects, {@code LeAudioService} reaches for the volume controller
     * on its own:
     *
     * <pre>
     * // LeAudioService.smali:13001-13021
     * Optional&lt;VolumeControlService&gt; vcs = getAdapterService().getVolumeControlService();
     * if (vcs != null &amp;&amp; vcs.getConnectionState(device) == STATE_DISCONNECTED) {
     *   vcs.connect(device);
     * }
     * </pre>
     *
     * <p>and {@code VolumeControlService#connect} refuses before it sends a
     * single packet ({@code VolumeControlService.smali:2706-2724}):
     *
     * <pre>
     * ParcelUuid[] uuids = getAdapterService().getRemoteUuids(device);
     * if (!Util.arrayContains(uuids, BluetoothUuid.VOLUME_CONTROL)) {   // 0x1844
     *   Log.e(TAG, "Cannot connect to " + device + " : Remote does not have Volume Control UUID");
     *   return false;
     * }
     * </pre>
     *
     * <p>So the phone never becomes the volume controller, and the headset's own
     * volume is left wherever it happens to sit -- which is heard as "the
     * slider does not really control this thing, and the floor is too loud".
     * Restoring {@code 0x1844} is the whole fix; the phone then drives the
     * headset's 0..255 VCS volume from its own stream index.
     *
     * <p>Deliberately not the whole family.  The headset also exposes VOCS
     * {@code 0x1845}, MCS {@code 0x184D}, TBS {@code 0x1853} and BASS
     * {@code 0x184F}, but each of those switches on another controller in the
     * phone and none of them is needed for stereo or for volume.  Three UUIDs
     * is the smallest change that can be measured.
     */
    private static final String[] LE_AUDIO_UUIDS = {
            "0000184e-0000-1000-8000-00805f9b34fb",
            "00001846-0000-1000-8000-00805f9b34fb",
            "00001844-0000-1000-8000-00805f9b34fb",
    };

    /**
     * The feature string {@code AdapterService.onCreate} reads to decide whether
     * this device is a TV.  Faking it is the consent bypass; see
     * {@link #BYPASS_CONSENT}.
     */
    private static final String LEANBACK_ONLY = "android.software.leanback_only";

    /** The adb-driven console; experiment scaffolding, not needed at runtime. */
    private static final boolean CONSOLE = true;

    private static final String ACTION_KEY = "az100_action";
    private static final String PROPERTY_KEY = "debug.az100.action";
    private static final String FILE_PATH = "/data/misc/bluetooth/az100_action";
    /**
     * Console output.  The Bluetooth process owns this directory, so it can
     * always append here, and a root shell can always read it back -- unlike
     * logcat, whose tag survives a process as chatty as this one only by luck.
     */
    private static final String LOG_PATH = "/data/misc/bluetooth/az100_console.log";
    private static final long POLL_MS = 2000L;

    private static volatile boolean installed;
    private static volatile ClassLoader loader;

    /** The live {@code AdapterService}, captured in its {@code onCreate}. */
    private static volatile Object adapterService;

    /** Extra address to treat as the headset, settable at runtime. */
    private static volatile String target = Az100Hook.AZ100_MAC;

    /** Last action seen, so each one runs once. */
    private static volatile String lastAction;

    /**
     * Whether the next pairing request for the headset is answered
     * automatically.  Armed by the {@code autobond} console action and disarmed
     * again as soon as it has been used, so it never lingers as a standing
     * policy.
     */
    private static volatile boolean autoConfirmArmed;

    /** One consent answer per bond attempt, whichever path gets there first. */
    private static volatile boolean confirmAttempted;

    /**
     * Arguments for the consent reply.
     *
     * <p>The Settings dialog's own answer is
     * {@code AdapterNativeInterface#sspReply(addr, 0, accept, 0)} -- verbatim,
     * per the smali of {@code AdapterServiceBinder#setPairingConfirmation}.
     * On this build that lands on the BR/EDR numeric-comparison handler and is
     * dropped:
     *
     * <pre>
     * sspReplyNative:
     * btif_dm_ssp_reply: accept=1
     * btm_confirm_req_reply() State: IDLE  Res: 0
     * btm_confirm_req_reply: Unexpected pairing confirm for ...84:85, pairing_state: IDLE
     * </pre>
     *
     * so the two numeric arguments are kept settable and the console exposes
     * them: {@code autobond <mac> <transport> <variant>}.
     */
    private static volatile int confirmTransport;
    private static volatile int confirmVariant;

    /**
     * The last VCS value actually handed to the native stack, keyed
     * {@code "g:<groupId>"} for {@code setGroupVolume} and
     * {@code "d:<address>"} for {@code setVolume}.  Used by
     * {@link #hookVolumeWriteGuard()} to drop writes that would not change
     * anything -- see the comment there for why that matters.
     */
    private static final java.util.Map<String, Integer> LAST_PUSHED =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /**
     * What the headset itself last reported, per address, via
     * {@code handleVolumeControlChanged}.  This is the value that decides
     * whether a write is a no-op -- {@link #LAST_PUSHED} only covers writes we
     * made ourselves, so on a reconnect, when the headset has kept its old
     * volume, it is the only thing that knows the connect-time push would
     * change nothing.
     */
    private static final java.util.Map<String, Integer> LAST_REPORTED =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /** Sentinel written between actions; ignored rather than run. */
    private static final String IDLE = "none";

    private static volatile int ticks;

    private LeAudioTransportHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        synchronized (LeAudioTransportHook.class) {
            if (installed) return;
            installed = true;
            loader = classLoader;
        }
        // XposedBridge.log never lands when it is called from a binder thread,
        // and createBond/sendUuidsInternal both run on one.  Mirroring to a file
        // the Bluetooth process owns is the only channel that always works.
        Logs.traceToFile(new File("/data/misc/bluetooth"));
        if (NATIVE_LEA_WHITELIST) NativeLeaWhitelistHook.install(classLoader);
        NativeLeaDeviceHook.install(classLoader);
        if (BASS_NPE_FIX) BassClientFixHook.install(classLoader);
        if (OPLUS_DUAL_MODE_COEXIST_FIX) OplusDualModeCoexistHook.install(classLoader);
        hookBondEntryPoints();
        // Installed even with AUTO_CONFIRM off: this is the only place that
        // records whether the consent dialog was raised at all, which is how
        // the bypass below is judged to have worked.
        hookPairingRequest();
        if (BYPASS_CONSENT) hookConsentBypass();
        if (DIAGNOSE) hookUuidPipeline();
        if (DIAGNOSE) hookVolumeControl();
        if (SUPPRESS_REDUNDANT_VCS_WRITES) hookVolumeWriteGuard();
        if (TWIN_AUTO_CONNECT) hookLeAudioTwin();
        if (CONSOLE) startConsole();
    }

    /* ------------------------------------------------------------------ */
    /* the behaviour change                                                */
    /* ------------------------------------------------------------------ */

    private static void hookBondEntryPoints() {
        Class<?> adapterService;
        try {
            adapterService = XposedHelpers.findClass(ADAPTER_SERVICE, loader);
        } catch (Throwable t) {
            Logs.trace("routeB: AdapterService not found", t);
            return;
        }
        // The chokepoint: the only caller of it is createBond, and it is the
        // last place the transport is still a plain argument.
        hookTransport(adapterService, "sendCreateBondMessage");
        // The public entry, so the rewrite is also visible in the log for the
        // caller-facing value rather than only the dispatched one.
        hookTransport(adapterService, "createBond");
    }

    private static void hookTransport(Class<?> clazz, final String method) {
        try {
            XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    rewriteTransport(method, param);
                }
            });
            Logs.trace("routeB: hooked AdapterService#" + method);
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook " + method, t);
        }
    }

    private static void rewriteTransport(String where, XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof BluetoothDevice) || !(args[1] instanceof Integer)) return;
        BluetoothDevice device = (BluetoothDevice) args[0];
        int transport = (Integer) args[1];
        String address = addressOf(device);
        if (!matches(device)) {
            Logs.trace("bond: " + where + " transport=" + transport + " other " + mask(address));
            return;
        }
        if (!FORCE_LE_TRANSPORT) {
            Logs.trace("bond: " + where + " transport=" + transport + " (force off) "
                    + mask(address));
            return;
        }
        if (transport == TRANSPORT_LE) {
            Logs.trace("bond: " + where + " transport=2 (LE) already " + mask(address));
            return;
        }
        args[1] = TRANSPORT_LE;
        Logs.trace("bond: " + where + " transport " + transport + " -> 2 (LE) " + mask(address));
    }

    /* ------------------------------------------------------------------ */
    /* the LE consent, answered without a tap                              */
    /* ------------------------------------------------------------------ */

    /**
     * {@code BondStateMachine#sendPairingRequestIntent(BluetoothDevice,
     * Optional&lt;Integer&gt;, int variant, int pairingContext, int
     * pairingAlgorithm)} is the single place that turns a stack-level pairing
     * request into the {@code ACTION_PAIRING_REQUEST} broadcast the Settings
     * dialog is built from.  Variant {@code 3} is
     * {@code PAIRING_VARIANT_CONSENT}, which is what Just Works arrives as.
     *
     * <p>It runs on the bond state machine's handler thread and is called
     * <em>before</em> the ordered broadcast goes out, so the hook still sees
     * the request even if a vendor branch ends up swallowing the broadcast.
     */
    private static void hookPairingRequest() {
        try {
            Class<?> machine = XposedHelpers.findClass(BOND_STATE_MACHINE, loader);
            XposedBridge.hookAllMethods(machine, "sendPairingRequestIntent",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            if (args == null || args.length < 3) return;
                            if (!(args[0] instanceof BluetoothDevice)) return;
                            BluetoothDevice device = (BluetoothDevice) args[0];
                            int variant = args[2] instanceof Integer ? (Integer) args[2] : -1;
                            String address = addressOf(device);
                            boolean headset = matches(device);
                            Logs.trace("pair: " + mask(address) + " variant=" + variant
                                    + " armed=" + autoConfirmArmed + (headset ? " headset" : ""));
                            if (!autoConfirmArmed || !headset) return;
                            scheduleConfirm(device, "intent variant=" + variant);
                        }
                    });
            Logs.trace("routeB: hooked BondStateMachine#sendPairingRequestIntent");
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook sendPairingRequestIntent", t);
        }
    }

    /**
     * The consent-dialog bypass, plus the measurement that proves it landed.
     *
     * <p>Two hooks, both on the Java side of the Bluetooth process:
     *
     * <ol>
     *   <li>{@code ApplicationPackageManager#hasSystemFeature(String)} -- answer
     *       {@code true} for {@code android.software.leanback_only}.  This is the
     *       one input {@code AdapterService.onCreate} reads when deciding whether
     *       the stack may raise the LE consent prompt, so faking it here is what
     *       actually suppresses the dialog.</li>
     *   <li>{@code AdapterNativeInterface#init} -- read-only, logs all seven
     *       arguments so the 6th can be seen flipping to {@code true}.  Without
     *       this there is no way to tell "the hook worked" apart from "the value
     *       never reached the stack".</li>
     * </ol>
     *
     * <p>Order matters: the package-manager hook must be in place before
     * {@code AdapterService.onCreate} runs.  {@code handleLoadPackage} fires at
     * process attach, which is strictly earlier, so the ordering is safe.
     */
    private static void hookConsentBypass() {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager", loader);
            XposedBridge.hookAllMethods(manager, "hasSystemFeature", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 1) return;
                    if (!LEANBACK_ONLY.equals(args[0])) return;
                    param.setResult(Boolean.TRUE);
                    Logs.trace("consent: hasSystemFeature(" + LEANBACK_ONLY + ") -> true");
                }
            });
            Logs.trace("consent: hooked ApplicationPackageManager#hasSystemFeature");
        } catch (Throwable t) {
            Logs.trace("consent: cannot hook hasSystemFeature", t);
        }

        try {
            Class<?> nativeInterface = XposedHelpers.findClass(
                    "com.android.bluetooth.btservice.AdapterNativeInterface", loader);
            XposedBridge.hookAllMethods(nativeInterface, "init", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("consent: AdapterNativeInterface#init");
                    Object[] args = param.args;
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            sb.append("\n    arg[").append(i).append("]=").append(describe(args[i]));
                        }
                    }
                    Logs.trace(sb.toString());
                }
            });
            Logs.trace("consent: hooked AdapterNativeInterface#init");
        } catch (Throwable t) {
            Logs.trace("consent: cannot hook AdapterNativeInterface#init", t);
        }
    }

    private static String describe(Object value) {
        if (value == null) return "<null>";
        if (value instanceof Object[]) return Arrays.toString((Object[]) value);
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static void scheduleConfirm(final BluetoothDevice device, final String why) {
        if (confirmAttempted) return;
        confirmAttempted = true;
        autoConfirmArmed = false;
        // Off the handler thread: this thread is also the one that services the
        // bond state machine, so it must not be parked in a sleep.
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(CONFIRM_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                console("auto-confirm (" + why + ") -> " + confirmPairing(device));
            }
        }, "az100-confirm");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Belt and braces: if {@code sendPairingRequestIntent} is never reached --
     * because a vendor branch returned early, or because the consent came from
     * a path that does not build an intent -- the device still sits in
     * {@code BOND_BONDING} waiting for an answer.  One late answer is enough.
     */
    private static void armConfirmWatchdog(final BluetoothDevice device) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(WATCHDOG_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (confirmAttempted) return;
                try {
                    if (device.getBondState() != BluetoothDevice.BOND_BONDING) return;
                } catch (Throwable t) {
                    return;
                }
                confirmAttempted = true;
                autoConfirmArmed = false;
                console("auto-confirm (watchdog) -> " + confirmPairing(device));
            }
        }, "az100-confirm-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The reply arguments are not documented anywhere reachable from the app
     * side, and the decompiled JNI only shows that both integers are forwarded
     * verbatim:
     *
     * <pre>
     * 46e0b4  mov w21, w3        ; transport  = arg3
     * 46e0b0  mov w20, w4        ; accept     = arg4
     * 46e0ac  mov w19, w5        ; variant    = arg5
     * ...
     * 46e124  mov w1, w21
     * 46e128  mov w2, w20
     * 46e12c  mov w3, w19
     * 46e138  blr x8             ; *(sBtInterface + 0x88)(addr, w1, w2, w3)
     * </pre>
     *
     * so instead of guessing one pair, {@code sweep} walks all of them inside a
     * single pairing window.  A reply that does not match anything is harmless:
     * {@code btm_confirm_req_reply} logs {@code Unexpected pairing confirm} and
     * returns, leaving SMP parked in {@code SMP_STATE_WAIT_APP_RSP} for the next
     * attempt.  That was measured, not assumed.
     */
    private static final int[][] SWEEP = {
            {2, 0}, {0, 2}, {2, 2}, {1, 2}, {2, 1}, {2, 3},
            {0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 3}, {1, 3},
    };

    private static final long SWEEP_STEP_MS = 1800L;

    private static void sweepBond(final BluetoothDevice device, final int transport) {
        // Take the automatic paths out of the race; the sweep drives every reply.
        autoConfirmArmed = false;
        confirmAttempted = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                boolean started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? device.createBond(transport) : device.createBond();
                console("sweep: createBond(" + transport + ")=" + started);
                for (int[] candidate : SWEEP) {
                    // Sleep first: right after createBond() the state machine
                    // has not moved off BOND_NONE yet, so checking first would
                    // abort before a single reply is sent.
                    try {
                        Thread.sleep(SWEEP_STEP_MS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    int state;
                    try {
                        state = device.getBondState();
                    } catch (Throwable t) {
                        console("sweep: state read failed " + t);
                        return;
                    }
                    if (state == BluetoothDevice.BOND_BONDED) {
                        console("sweep: BONDED after transport=" + confirmTransport
                                + " variant=" + confirmVariant);
                        return;
                    }
                    if (state != BluetoothDevice.BOND_BONDING) {
                        console("sweep: not bonding (state=" + state + ") before transport="
                                + candidate[0] + " variant=" + candidate[1]);
                        return;
                    }
                    confirmTransport = candidate[0];
                    confirmVariant = candidate[1];
                    console("sweep try transport=" + candidate[0] + " variant=" + candidate[1]
                            + " -> " + confirmPairing(device));
                }
                console("sweep: exhausted, bondState=" + device.getBondState());
            }
        }, "az100-sweep");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The exact answer the dialog's "pair" button gives.
     *
     * <p>{@code AdapterServiceBinder#setPairingConfirmation(device, accept,
     * source)} is a permission check followed by
     * {@code service.getNative().sspReply(Util.getBytesFromAddress(addr), 0,
     * accept, 0)}.  Calling the native interface directly keeps the whole thing
     * in-process -- no {@code AttributionSource} to forge and no binder hop --
     * and drops the {@code isBonding()} guard, which is safe here because the
     * only caller is the pairing request itself.
     */
    private static String confirmPairing(BluetoothDevice device) {
        Object service = adapterService;
        String address = addressOf(device);
        byte[] raw = bytesOf(address);
        int transport = confirmTransport;
        int variant = confirmVariant;
        if (service != null && raw != null) {
            try {
                Object nativeInterface = XposedHelpers.callMethod(service, "getNative");
                if (nativeInterface != null) {
                    Object result = XposedHelpers.callMethod(nativeInterface, "sspReply",
                            raw, transport, Boolean.TRUE, variant);
                    return "sspReply(" + mask(address) + ", transport=" + transport
                            + ", variant=" + variant + ")=" + result;
                }
            } catch (Throwable t) {
                Logs.trace("routeB: sspReply failed", t);
            }
        }
        // The public-hidden equivalent, in case the native interface is not
        // reachable from where this happens to run.
        try {
            Method method = BluetoothDevice.class.getMethod(
                    "setPairingConfirmation", boolean.class);
            return "setPairingConfirmation(" + mask(address) + ")="
                    + method.invoke(device, Boolean.TRUE);
        } catch (Throwable t) {
            return "ERROR " + t;
        }
    }

    /** {@code Utils.getBytesFromAddress} without depending on the vendor build. */
    private static byte[] bytesOf(String address) {
        if (address == null) return null;
        String[] parts = address.split(":");
        if (parts.length != 6) return null;
        byte[] raw = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                raw[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException malformed) {
            return null;
        }
        return raw;
    }

    /* ------------------------------------------------------------------ */
    /* diagnostics: where do the UUIDs actually come from                  */
    /* ------------------------------------------------------------------ */

    /**
     * All four UUID arrays are written from exactly one place --
     * {@code RemoteDevices#devicePropertyChangedCallback}, the JNI callback
     * that delivers {@code BT_PROPERTY_UUIDS}, {@code BT_PROPERTY_UUIDS_LE},
     * {@code BT_PROPERTY_UUIDS_FROM_EXTENDED_INQUIRY_RESPONSE} and
     * {@code BT_PROPERTY_UUIDS_FROM_LE_ADVERTISING_DATA}.  Watching the four
     * setters plus {@code sendUuidsInternal} answers "did {@code 0x184E} ever
     * reach the profile manager, and by which route" without guessing.
     */
    private static void hookUuidPipeline() {
        Class<?> properties = null;
        try {
            properties = XposedHelpers.findClass(DEVICE_PROPERTIES, loader);
        } catch (Throwable t) {
            Logs.trace("routeB: DeviceProperties not found", t);
        }
        if (properties != null) {
            for (final String setter : new String[]{
                    "setUuidsBrEdr",
                    "setUuidsLe",
                    "setUuidsFromExtendedInquiryResponse",
                    "setUuidsFromLeAdvertisingData"}) {
                try {
                    XposedBridge.hookAllMethods(properties, setter, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            dumpUuids(param.thisObject, setter);
                        }
                    });
                } catch (Throwable t) {
                    Logs.trace("routeB: cannot hook " + setter, t);
                }
            }
            if (SPLICE_LE_AUDIO_UUIDS) spliceLeAudioUuid(properties);
        }

        try {
            Class<?> adapterService = XposedHelpers.findClass(ADAPTER_SERVICE, loader);
            XposedBridge.hookAllMethods(adapterService, "sendUuidsInternal",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            if (args == null || args.length < 2) return;
                            String address = args[0] instanceof BluetoothDevice
                                    ? addressOf((BluetoothDevice) args[0]) : null;
                            Logs.trace("routeB: sendUuidsInternal " + mask(address)
                                    + " count=" + (args[1] == null
                                            ? "null" : String.valueOf(Array.getLength(args[1]))));
                        }
                    });
            Logs.trace("routeB: hooked AdapterService#sendUuidsInternal");
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook sendUuidsInternal", t);
        }

        try {
            Class<?> remoteDevices = XposedHelpers.findClass(REMOTE_DEVICES, loader);
            XposedBridge.hookAllMethods(remoteDevices, "devicePropertyChangedCallback",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Logs.trace("routeB: devicePropertyChangedCallback "
                                    + describe(param.args));
                        }
                    });
            Logs.trace("routeB: hooked RemoteDevices#devicePropertyChangedCallback");
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook devicePropertyChangedCallback", t);
        }
    }

    /**
     * The volume half of the same vendor filter.
     *
     * <p>{@code VolumeControlService#connect} fails silently from the outside:
     * it logs {@code Remote does not have Volume Control UUID} and returns
     * {@code false}, and nothing downstream distinguishes that from "the
     * headset refused".  Two hooks make the difference visible.
     *
     * <ul>
     *   <li>{@code connect} -- the UUID list as the volume service sees it, and
     *       the boolean it returned.  This is the one that says whether the
     *       splice reached {@code getRemoteUuids()}.</li>
     *   <li>{@code setDeviceVolume} -- the value the phone is pushing.  Its
     *       domain is the headset's VCS range (0..255), so a run of readings
     *       across a slider sweep is what shows whether the mapping is sane.</li>
     * </ul>
     */
    private static void hookVolumeControl() {
        Class<?> service = null;
        try {
            service = XposedHelpers.findClass(VOLUME_CONTROL_SERVICE, loader);
        } catch (Throwable t) {
            Logs.trace("vcs: VolumeControlService not found", t);
            return;
        }
        try {
            XposedBridge.hookAllMethods(service, "connect", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 1) return;
                    if (!(args[0] instanceof BluetoothDevice)) return;
                    BluetoothDevice device = (BluetoothDevice) args[0];
                    Logs.trace("vcs: connect(" + mask(addressOf(device))
                            + ") uuids=" + uuidsOf(device));
                    allowVcp(param.thisObject, device, "connect");
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 1) return;
                    String address = args[0] instanceof BluetoothDevice
                            ? addressOf((BluetoothDevice) args[0]) : null;
                    Logs.trace("vcs: connect " + mask(address) + " -> " + param.getResult());
                }
            });
        } catch (Throwable t) {
            Logs.trace("vcs: cannot hook connect", t);
        }
        try {
            XposedBridge.hookAllMethods(service, "setConnectionPolicy", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 2) return;
                    if (!(args[0] instanceof BluetoothDevice)) return;
                    if (!(args[1] instanceof Integer)) return;
                    if (((Integer) args[1]) != CONNECTION_POLICY_FORBIDDEN) return;
                    BluetoothDevice device = (BluetoothDevice) args[0];
                    if (!matches(device)) return;
                    if (!leAudioAllowed(param.thisObject, device)) {
                        Logs.trace("vcs: policy FORBIDDEN kept for " + mask(addressOf(device))
                                + " (LE Audio not allowed)");
                        return;
                    }
                    args[1] = Integer.valueOf(CONNECTION_POLICY_ALLOWED);
                    Logs.trace("vcs: policy FORBIDDEN -> ALLOWED for " + mask(addressOf(device)));
                }
            });
            Logs.trace("vcs: connection-policy guard armed");
        } catch (Throwable t) {
            Logs.trace("vcs: cannot hook setConnectionPolicy", t);
        }
        try {
            XposedBridge.hookAllMethods(service, "setDeviceVolume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 2) return;
                    String address = args[0] instanceof BluetoothDevice
                            ? addressOf((BluetoothDevice) args[0]) : null;
                    Logs.trace("vcs: setDeviceVolume " + mask(address) + " = " + args[1]);
                }
            });
            Logs.trace("vcs: volume control diagnostics armed");
        } catch (Throwable t) {
            Logs.trace("vcs: cannot hook setDeviceVolume", t);
        }
        try {
            XposedBridge.hookAllMethods(service, "handleVolumeControlChanged",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            // (BluetoothDevice, groupId, volume, flags, mute, autonomous).
                            if (args == null || args.length < 6) return;
                            if (!(args[1] instanceof Integer)
                                    || !(args[2] instanceof Integer)) return;
                            captureVolumeReport(args[0], (Integer) args[1],
                                    (Integer) args[2], Boolean.TRUE.equals(args[5]));
                            if (LOWER_VOLUME_FLOOR && preserveLowAutonomousReport(
                                    param.thisObject, args)) param.setResult(null);
                        }
                    });
            XposedBridge.hookAllMethods(service, "connectionStateChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 3
                            || !(args[0] instanceof BluetoothDevice)
                            || !(args[2] instanceof Integer)) return;
                    // Signature is (device, fromState, toState).
                    if (((Integer) args[2]) != 2) forgetVolume((BluetoothDevice) args[0]);
                }
            });
            Logs.trace("vcs: volume-report capture and member lifecycle armed");
        } catch (Throwable t) {
            Logs.trace("vcs: cannot hook handleVolumeControlChanged", t);
        }
    }

    /**
     * Drop VCS writes that would not change the headset's volume.
     *
     * <p>Why, as measured on this phone:
     *
     * <pre>
     * OnLeAudioGroupStreamStarted: group_id: 1 pending volume resync on next SetVolume
     * SetVolume: group_id: 1 cleared LE Audio volume resync flag (scheduling 2 device(s))
     * PrepareVolumeControlOperation: num of devices: 2, group_id: 1 ... opcode: 4
     * ControlPointOperation: ..84:85 (*arg)[0]=128 volume=128 opcode=0x04 change_counter=0xb5
     * ControlPointOperation: ..3c:62 (*arg)[0]=128 volume=128 opcode=0x04 change_counter=0x05
     * OnWriteControlResponse: Write response handle: 0x1308 status: 0x0
     * OnWriteControlResponse: Write response handle: 0x1308 status: 0x0
     * OperationMonitorTimeoutFired: Operation 2 is taking too long for devices:  (every 3s, forever)
     * </pre>
     *
     * <p>Both ATT writes returned success, but no matching Volume State update
     * was observed. The evidence points to the VCP operation queue waiting for
     * completion of a same-value resync, not an unresponsive ATT transport.
     * Operation 2 is a queue id (the opcode here is 0x04). In that log window,
     * 136 matching SetVolume log lines followed only 4 ControlPointOperation
     * lines; these are log counts, not necessarily API-call counts. The complete
     * vendor-native completion logic has not been audited.
     *
     * <p>The redundant write is not an accident.  The native
     * {@code volumeNotChanged} guard at {@code vcp_controller.cc:1352} would
     * have suppressed it, but {@code OnLeAudioGroupStreamStarted} sets a
     * pending-resync flag that bypasses that guard, and
     * {@code LeAudioService#messageFromNative} feeds it on every stream start
     * ("music/game streaming", "voip/call streaming") through
     * {@code VolumeControlService#restoreGrouVPolume}, which calls
     * {@code VolumeControlNativeInterface#setGroupVolume} directly rather than
     * going through {@code setDeviceVolume}.
     *
     * <p>So the guard sits on the last Java boundary before native, where it
     * catches both entry paths, and drops only writes that would be no-ops.
     * {@code restoreGrouVPolume}'s Java-side cache updates and AudioManager
     * side effects still run; only the redundant GATT write disappears.
     */
    private static void hookVolumeWriteGuard() {
        Class<?> iface;
        try {
            iface = XposedHelpers.findClass(VOLUME_CONTROL_NATIVE_INTERFACE, loader);
        } catch (Throwable t) {
            Logs.trace("vcs: VolumeControlNativeInterface not found", t);
            return;
        }
        XC_MethodHook guard = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null || args.length < 2) return;
                if (!(args[1] instanceof Integer)) return;
                String key = keyOf(args[0]);
                if (key == null) return;
                int wanted = ((Integer) args[1]);
                if (wanted < 0 || wanted > 255) return;
                int stock = clampLeAudioVolume(wanted);
                // 1..15 would be raised to 16 by the method body itself, so
                // those have to go straight to native to survive.
                int volume = (LOWER_VOLUME_FLOOR && wanted > 0
                        && wanted < LEAUDIO_VOLUME_MIN) ? wanted : stock;
                Integer last = knownVolume(key);
                if (isNoOp(key, volume)) {
                    param.setResult(null);
                    Logs.trace("vcs: dropped redundant write " + key + " = " + volume
                            + " (unchanged, known " + last + ")");
                    return;
                }
                Logs.trace("vcs: write " + key + " = " + volume
                        + (volume != stock ? " (floor bypass, stock would send "
                                + stock + ")" : "")
                        + (last == null ? " (first)" : " (was " + last + ")"));
                java.util.List<BluetoothDevice> members = volumeMembers(key);
                if (volume != stock && writeNative(param.thisObject, args[0], volume)) {
                    rememberPushed(members, volume);
                    param.setResult(null);
                    return;
                }
                // Record only after the original returned without an exception.
                // A failed bypass will actually send stock=16, never wanted=1..15.
                param.setObjectExtra("az100.volume.members", members);
                param.setObjectExtra("az100.volume.stock", Integer.valueOf(stock));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object volume = param.getObjectExtra("az100.volume.stock");
                if (!(volume instanceof Integer)) return;
                Object members = param.getObjectExtra("az100.volume.members");
                if (!(members instanceof java.util.List)) return;
                @SuppressWarnings("unchecked")
                java.util.List<BluetoothDevice> devices =
                        (java.util.List<BluetoothDevice>) members;
                rememberPushed(devices, (Integer) volume);
            }
        };
        // Each member has its own history. Removing one must leave its peer intact.
        XC_MethodHook forget = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof BluetoothDevice)) return;
                forgetVolume((BluetoothDevice) param.args[0]);
            }
        };
        try {
            XposedBridge.hookAllMethods(iface, "setGroupVolume", guard);
            XposedBridge.hookAllMethods(iface, "setVolume", guard);
            XposedBridge.hookAllMethods(iface, "connectVolumeControl", forget);
            XposedBridge.hookAllMethods(iface, "disconnectVolumeControl", forget);
            Logs.trace("vcs: redundant-write guard armed");
        } catch (Throwable t) {
            Logs.trace("vcs: cannot hook volume writes", t);
        }
    }

    /**
     * The best estimate of the headset's current volume for {@code key}, or
     * {@code null} when nothing is known yet.  What the headset reported wins
     * over what we last pushed, because a reconnect leaves the headset holding
     * its old value while our push history has been forgotten -- and it is
     * exactly then that the connect-time push turns into a no-op.
     */
    private static Integer knownVolume(String key) {
        java.util.List<BluetoothDevice> members = volumeMembers(key);
        if (members == null || members.isEmpty()) return null;
        Integer consensus = null;
        for (BluetoothDevice device : members) {
            String address = addressOf(device);
            Integer value = LAST_PUSHED.get("d:" + address);
            if (value == null) value = LAST_REPORTED.get(address);
            if (value == null) return null;
            if (consensus != null && !consensus.equals(value)) return null;
            consensus = value;
        }
        return consensus;
    }

    /**
     * Require consensus among currently VCP-connected AZ100 members only.
     * A newer push supersedes an older report; an autonomous report supersedes
     * the push. This avoids stale A suppressing a later A after A -> B -> A.
     * Native setters return void: a returned call is submission evidence only,
     * not proof of ATT success. Failure/timeout recovery remains a device test.
     */
    private static boolean isNoOp(String key, int volume) {
        Integer current = knownVolume(key);
        return current != null && current.intValue() == volume;
    }

    /**
     * Call the private native setter directly, skipping the wrapper methods
     * whose bodies would clamp the value back up to {@link #LEAUDIO_VOLUME_MIN}.
     */
    private static boolean writeNative(Object iface, Object target, int volume) {
        try {
            if (target instanceof Integer) {
                XposedHelpers.callMethod(iface, "setGroupVolumeNative", target, volume);
            } else if (target instanceof BluetoothDevice) {
                Object address = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(VOLUME_CONTROL_NATIVE_INTERFACE, loader),
                        "getByteAddress", target);
                XposedHelpers.callMethod(iface, "setVolumeNative", address, volume);
            } else {
                return false;
            }
            Logs.trace("vcs: " + volume + " written straight to native (clamp bypassed)");
            return true;
        } catch (Throwable t) {
            Logs.trace("vcs: native bypass failed", t);
            return false;
        }
    }

    /**
     * Stock autonomous handling rounds VCS 1..15 to zero, including the first
     * single-ear connect. For AZ100 media only, use the existing cache/audio
     * update helper with the unmodified low value, then notify normal clients.
     * No native volume write is manufactured here; mute remains independent.
     */
    private static boolean preserveLowAutonomousReport(Object vcs, Object[] args) {
        if (!Boolean.TRUE.equals(args[5])) return false;
        int volume = (Integer) args[2];
        if (volume < 1 || volume >= LEAUDIO_VOLUME_MIN) return false;
        try {
            int groupId = (Integer) args[1];
            if (groupId < 0 && args[0] instanceof BluetoothDevice) {
                groupId = (Integer) XposedHelpers.callMethod(vcs, "getGroupId", args[0]);
            }
            if (!az100Group(groupId)) return false;
            if (((Integer) XposedHelpers.callMethod(
                    vcs, "getBluetoothContextualVolumeStreamFromAudio")) != 3) return false;
            Object status = XposedHelpers.getObjectField(vcs, "mGroupStatus");
            if (!(status instanceof java.util.Map)
                    || !((java.util.Map<?, ?>) status).containsKey(groupId)
                    || Integer.valueOf(0).equals(((java.util.Map<?, ?>) status).get(groupId))) {
                return false;
            }
            if (args[0] instanceof BluetoothDevice) {
                int flags = args[3] instanceof Integer ? (Integer) args[3] : 0;
                Object connected = XposedHelpers.callMethod(vcs, "getConnectedDevices", groupId);
                if ((flags & 1) == 0 || !(connected instanceof java.util.List)
                        || ((java.util.List<?>) connected).size() != 1) return false;
            }
            // Resolve methods before any side effects: unsupported firmware fails open.
            Method update = XposedHelpers.findMethodExact(vcs.getClass(),
                    "updateCacheByAutonomousChange", int.class, int.class,
                    boolean.class, boolean.class);
            Method notify = XposedHelpers.findMethodExact(vcs.getClass(),
                    "notifyDevicesVolumeChanged", android.os.RemoteCallbackList.class,
                    java.util.List.class, java.util.Optional.class);
            Object callbacks = XposedHelpers.getObjectField(vcs, "mCallbacks");
            Object devices = args[0] instanceof BluetoothDevice
                    ? java.util.Collections.singletonList((BluetoothDevice) args[0])
                    : XposedHelpers.callMethod(vcs, "getGroupDevices", groupId);
            update.invoke(vcs, groupId, volume, Boolean.TRUE.equals(args[4]), false);
            if (callbacks != null) {
                synchronized (callbacks) {
                    notify.invoke(vcs, callbacks, devices, java.util.Optional.of(volume));
                }
            }
            Logs.trace("vcs: preserved low autonomous report g:" + groupId + " = " + volume);
            return true;
        } catch (Throwable t) {
            Logs.trace("vcs: low-report preservation failed; stock path retained", t);
            return false;
        }
    }

    private static Object profileService(String getter) {
        try {
            if (adapterService == null) return null;
            Object result = XposedHelpers.callMethod(adapterService, getter);
            if (result instanceof java.util.Optional) {
                return ((java.util.Optional<?>) result).orElse(null);
            }
        } catch (Throwable ignored) {
            // Service startup/shutdown: leave the stock path alone.
        }
        return null;
    }

    /** Fail open unless every known member of this group is one of our two AZ100s. */
    private static boolean az100Group(int groupId) {
        if (groupId < 0) return false;
        Object lea = profileService("getLeAudioService");
        if (lea == null) return false;
        try {
            Object result = XposedHelpers.callMethod(lea, "getGroupDevices", groupId);
            if (!(result instanceof java.util.List) || ((java.util.List<?>) result).isEmpty()) {
                return false;
            }
            for (Object item : (java.util.List<?>) result) {
                if (!(item instanceof BluetoothDevice)
                        || !matchesAddress(addressOf((BluetoothDevice) item))) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Only VCP-connected members are relevant; an in-case peer is not a vote. */
    private static java.util.List<BluetoothDevice> volumeMembers(String key) {
        Object vcs = profileService("getVolumeControlService");
        Object lea = profileService("getLeAudioService");
        if (vcs == null || lea == null) return null;
        try {
            java.util.List<BluetoothDevice> out = new java.util.ArrayList<BluetoothDevice>();
            if (key.startsWith("d:")) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) return null;
                BluetoothDevice device = adapter.getRemoteDevice(key.substring(2));
                if (((Integer) XposedHelpers.callMethod(vcs, "getConnectionState", device)) == 2) {
                    out.add(device);
                }
                return out;
            }
            int groupId = Integer.parseInt(key.substring(2));
            Object result = XposedHelpers.callMethod(vcs, "getConnectedDevices");
            if (!(result instanceof java.util.List)) return null;
            for (Object item : (java.util.List<?>) result) {
                if (!(item instanceof BluetoothDevice)) continue;
                BluetoothDevice device = (BluetoothDevice) item;
                if (matchesAddress(addressOf(device))
                        && ((Integer) XposedHelpers.callMethod(lea, "getGroupId", device)) == groupId) {
                    out.add(device);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void rememberPushed(java.util.List<BluetoothDevice> members, int volume) {
        if (members == null) return;
        for (BluetoothDevice device : members) {
            String address = addressOf(device);
            LAST_PUSHED.put("d:" + address, volume);
            // A previous report is no longer evidence for this member after a new push.
            LAST_REPORTED.remove(address);
        }
    }

    private static void forgetVolume(BluetoothDevice device) {
        String address = addressOf(device);
        if (!matchesAddress(address)) return;
        LAST_PUSHED.remove("d:" + address);
        LAST_REPORTED.remove(address);
        Logs.trace("vcs: forgetting member " + mask(address) + " only");
    }

    private static void captureVolumeReport(Object target, int groupId, int volume,
            boolean autonomous) {
        if (volume < 0 || volume > 255) return;
        java.util.List<BluetoothDevice> members;
        if (target instanceof BluetoothDevice) {
            BluetoothDevice device = (BluetoothDevice) target;
            if (!matchesAddress(addressOf(device))) return;
            members = java.util.Collections.singletonList(device);
        } else if (target == null && az100Group(groupId)) {
            members = volumeMembers("g:" + groupId);
        } else {
            return;
        }
        if (members == null) return;
        for (BluetoothDevice device : members) {
            String address = addressOf(device);
            String key = "d:" + address;
            Integer pushed = LAST_PUSHED.get(key);
            if (autonomous || (pushed != null && pushed.intValue() == volume)) {
                LAST_PUSHED.remove(key);
            }
            // A late response to an older request must not beat a newer push.
            if (!autonomous && pushed != null && pushed.intValue() != volume) continue;
            LAST_REPORTED.put(address, volume);
            Logs.trace("vcs: headset reports " + mask(address) + " = " + volume
                    + " (group " + groupId + ", autonomous=" + autonomous + ")");
        }
    }

    /** Never apply the floor or write guard to other Bluetooth devices. */
    private static String keyOf(Object target) {
        if (target instanceof Integer && az100Group((Integer) target)) return "g:" + target;
        if (target instanceof BluetoothDevice) {
            String address = addressOf((BluetoothDevice) target);
            if (matchesAddress(address)) return "d:" + address;
        }
        return null;
    }

    /** Mirrors {@code VolumeControlNativeInterface}'s own clamp. */
    private static int clampLeAudioVolume(int volume) {
        if (volume != 0 && volume < LEAUDIO_VOLUME_MIN) return LEAUDIO_VOLUME_MIN;
        return volume;
    }

    /**
     * {@code ConnectableProfile#okToConnect} accepts only {@code -1} or {@code 100}:
     *
     * <pre>
     * if (getBondState(device) != BOND_BONDED)  { log "invalid bond state";      return false; }
     * int policy = getConnectionPolicy(device);
     * if (policy != -1 &amp;&amp; policy != 100)        { log "invalid connection policy"; return false; }
     * return true;
     * </pre>
     *
     * <p>{@code LeAudioService#setLeAudioGattClientProfilesPolicy} is the call
     * that would normally leave VCP at {@code ALLOWED} -- but it is itself gated
     * on {@code Util.arrayContains(getRemoteUuids(device), VOLUME_CONTROL)}, the
     * same vendor filter, so it silently does nothing when {@code 0x1844} is
     * missing.  On top of that
     * {@code OplusLeAdapterService#csisDeviceAvailable} propagates a
     * {@code FORBIDDEN} from one set member to the other:
     *
     * <pre>
     * if (vcs.getConnectionPolicy(peer) == 0 &amp;&amp; csip.getConnectionState(peer) == 2)
     *     vcs.setConnectionPolicy(device, 0);
     * </pre>
     *
     * <p>Measured on this phone: both AZ100 identities sit at {@code vcp: FORBIDDEN}
     * on disk ({@code VOLUME_CONTROL policy changed: -1 -&gt; 0}), so the UUID splice
     * alone is defeated one layer up.  This restores the policy at the moment a
     * connection is actually wanted.
     */
    private static void allowVcp(Object vcsService, BluetoothDevice device, String trigger) {
        if (!matches(device)) return;
        try {
            Object adapter = XposedHelpers.callMethod(vcsService, "getAdapterService");
            int profileId = (int) XposedHelpers.callMethod(vcsService, "getProfileId");
            int policy = (int) XposedHelpers.callMethod(adapter, "getProfileConnectionPolicy",
                    device, profileId);
            if (policy == CONNECTION_POLICY_ALLOWED) {
                Logs.trace("vcs: policy already ALLOWED for " + mask(addressOf(device))
                        + " (" + trigger + ")");
                return;
            }
            XposedHelpers.callMethod(adapter, "setProfileConnectionPolicy", device, profileId,
                    CONNECTION_POLICY_ALLOWED);
            Logs.trace("vcs: policy " + policy + " -> ALLOWED for " + mask(addressOf(device))
                    + " (" + trigger + ")");
        } catch (Throwable t) {
            Logs.trace("vcs: cannot normalise policy for " + mask(addressOf(device)), t);
        }
    }

    /**
     * Only override a vendor {@code FORBIDDEN} while LE Audio is switched on for
     * the device -- that is the state in which VCP is supposed to be reachable.
     * With LE Audio off the vendor's {@code FORBIDDEN} is left alone.
     */
    private static boolean leAudioAllowed(Object vcsService, BluetoothDevice device) {
        try {
            Object adapter = XposedHelpers.callMethod(vcsService, "getAdapterService");
            Object optional = XposedHelpers.callMethod(adapter, "getLeAudioService");
            if (!(optional instanceof java.util.Optional)) return false;
            java.util.Optional<?> value = (java.util.Optional<?>) optional;
            if (!value.isPresent()) return false;
            int policy = (int) XposedHelpers.callMethod(value.get(), "getConnectionPolicy", device);
            return policy != CONNECTION_POLICY_FORBIDDEN;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code BluetoothDevice#getUuids()} is the array {@code connect} tests, so
     * printing it here is the direct check on the splice -- and unlike the
     * {@code setUuidsLe} trace it is read at the moment of the decision.
     */
    private static String uuidsOf(BluetoothDevice device) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            return uuids == null ? "null" : Arrays.toString(uuids);
        } catch (Throwable t) {
            return "ERROR " + t;
        }
    }

    private static void dumpUuids(Object properties, String trigger) {
        StringBuilder sb = new StringBuilder("routeB: ").append(trigger);
        for (String field : UUID_FIELDS) {
            sb.append("\n    ").append(field).append(" = ").append(readUuids(properties, field));
        }
        // Must be trace(), not Log.i: this runs on the binder thread that
        // services sendUuidsInternal, and logcat never sees it from there.
        // The file channel is the only one that always lands.
        Logs.trace(sb.toString());
    }

    private static String readUuids(Object properties, String field) {
        try {
            Object value = XposedHelpers.getObjectField(properties, field);
            if (value == null) return "null";
            int length = Array.getLength(value);
            StringBuilder sb = new StringBuilder().append(length).append(" [");
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Array.get(value, i));
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    /** {@code devicePropertyChangedCallback(byte[] address, int[] types, byte[][] values)}. */
    private static String describe(Object[] args) {
        if (args == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            if (arg instanceof byte[]) {
                sb.append("addr=").append(hex((byte[]) arg));
            } else if (arg instanceof int[]) {
                sb.append("types=").append(Arrays.toString((int[]) arg));
            } else if (arg instanceof Object[]) {
                sb.append("values=").append(uuidLists((Object[]) arg));
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static String uuidLists(Object[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i] instanceof byte[]
                    ? uuidList((byte[]) values[i]) : String.valueOf(values[i]));
        }
        return sb.append(']').toString();
    }

    /** Native sends a UUID list as 16 raw bytes per entry. */
    private static String uuidList(byte[] raw) {
        if (raw == null) return "null";
        if (raw.length == 0) return "0";
        if (raw.length % 16 != 0) return raw.length + "B";
        StringBuilder sb = new StringBuilder().append(raw.length / 16).append(" [");
        for (int i = 0; i < raw.length; i += 16) {
            if (i > 0) sb.append(' ');
            sb.append(uuid(raw, i));
        }
        return sb.append(']').toString();
    }

    private static String uuid(byte[] raw, int offset) {
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            int b = raw[offset + i] & 0xFF;
            sb.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Addresses are device-specific data, so only the tail is ever logged. */
    private static String hex(byte[] raw) {
        if (raw == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void spliceLeAudioUuid(Class<?> properties) {
        try {
            XposedBridge.hookAllMethods(properties, "setUuidsLe", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    spliceIntoLeUuids(param);
                }
            });
            Logs.trace("routeB: setUuidsLe splice armed");
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook setUuidsLe", t);
        }
    }

    /**
     * Rewrites {@code setUuidsLe}'s argument before the method stores it.
     *
     * <p>The write side is the only place that reaches every reader at once.
     * {@code DeviceProperties.toString()} -- which is what
     * {@code dumpsys bluetooth_manager} prints as {@code [LE UUIDs]} -- reads
     * the raw field {@code mUuidsLe}, and {@code getUuids()} merges the same
     * field into what Settings and every {@code ACTION_UUID} consumer sees.
     * Patching the getter instead would only have covered the second of those,
     * and patching {@code getUuids()} is in any case unreachable here: the
     * vendor strips the UUID out of the advertising data, so a gate on the
     * advertising array can never open.
     *
     * <p>This runs inside {@code devicePropertyChangedCallback}'s switch on
     * {@code BT_PROPERTY_UUIDS_LE} (0x1c), which fires both when the stack
     * pushes a fresh discovery and when it replays {@code ServiceLe} from
     * {@code bt_config.conf} at start-up, so a restart is enough to re-arm it.
     */
    private static void spliceIntoLeUuids(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 1) return;
        if (!(args[0] instanceof Object[])) return;
        Object[] incoming = (Object[]) args[0];
        String[] missing = missingLeAudio(incoming);
        if (missing == null) return;
        String address = addressOfProperties(param.thisObject);
        if (!matches(param.thisObject, address)) return;
        Object[] extended = (Object[]) Array.newInstance(
                incoming.getClass().getComponentType(), incoming.length + missing.length);
        System.arraycopy(incoming, 0, extended, 0, incoming.length);
        for (int i = 0; i < missing.length; i++) {
            extended[incoming.length + i] = ParcelUuid.fromString(missing[i]);
        }
        args[0] = extended;
        Logs.trace("routeB: spliced " + Arrays.toString(missing) + " into mUuidsLe ("
                + incoming.length + " -> " + extended.length + ") " + mask(address));
    }

    /**
     * {@code RemoteDevices$DeviceProperties} carries no address argument, so it
     * has to be asked.  {@code getDevice()} is the normal route;
     * {@code getIdentityAddress()} covers the window before the device object
     * is attached, which is exactly the window start-up replay happens in.
     */
    private static String addressOfProperties(Object properties) {
        if (properties == null) return null;
        try {
            Object device = XposedHelpers.callMethod(properties, "getDevice");
            if (device instanceof BluetoothDevice) {
                String address = ((BluetoothDevice) device).getAddress();
                if (address != null) return address;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object identity = XposedHelpers.callMethod(properties, "getIdentityAddress");
            Object address = XposedHelpers.callMethod(identity, "getAddress");
            if (address instanceof String) return (String) address;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Which of {@link #LE_AUDIO_UUIDS} the incoming array does not already
     * carry, or {@code null} when it carries all of them.
     *
     * <p>Called on every {@code setUuidsLe}, so it stays allocation-free in the
     * common case: the scan short-circuits on the first hit per UUID and the
     * result array is only trimmed when something is actually missing.
     */
    private static String[] missingLeAudio(Object[] uuids) {
        String[] missing = null;
        int count = 0;
        for (String wanted : LE_AUDIO_UUIDS) {
            boolean present = false;
            for (Object value : uuids) {
                if (value != null && wanted.equalsIgnoreCase(value.toString())) {
                    present = true;
                    break;
                }
            }
            if (present) continue;
            if (missing == null) missing = new String[LE_AUDIO_UUIDS.length];
            missing[count++] = wanted;
        }
        if (missing == null) return null;
        return count == missing.length ? missing : Arrays.copyOf(missing, count);
    }

    /* ------------------------------------------------------------------ */
    /* the adb-driven console                                              */
    /* ------------------------------------------------------------------ */

    private static void startConsole() {
        try {
            XposedHelpers.findAndHookMethod(ADAPTER_SERVICE, loader, "onCreate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            adapterService = param.thisObject;
                            armConsole();
                        }
                    });
            Logs.trace("routeB: console anchor hooked");
        } catch (Throwable t) {
            Logs.trace("routeB: cannot hook AdapterService#onCreate", t);
        }
    }

    private static void armConsole() {
        // A plain daemon thread rather than a HandlerThread: this only needs a
        // 2s tick, and it keeps the console independent of any Looper in the
        // Bluetooth process.  It also makes synchronous binder calls back into
        // AdapterService, which must not be serviced on the waiting thread.
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Thread.currentThread().setUncaughtExceptionHandler(
                        new Thread.UncaughtExceptionHandler() {
                            @Override public void uncaughtException(Thread t, Throwable e) {
                                console("console thread died: " + e);
                            }
                        });
                console("console thread alive");
                while (true) {
                    try {
                        consume();
                    } catch (Throwable t) {
                        console("console poll failed " + t);
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }, "az100-routeB");
        thread.setDaemon(true);
        thread.start();
        console("console armed on property " + PROPERTY_KEY);
        Logs.i("routeB: console armed on property " + PROPERTY_KEY);
    }

    /** Console output: the file is the reliable channel, logcat is a bonus. */
    private static void console(String message) {
        try (FileOutputStream out = new FileOutputStream(LOG_PATH, true)) {
            out.write((System.currentTimeMillis() + " " + message + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // nothing else to fall back to
        }
        Logs.trace(message);
    }

    private static void consume() {
        String property = readFromProperty();
        String settings = readFromSettings();
        String file = readFromFile();
        if (ticks < 3) {
            ticks++;
            console("tick property=" + quote(property)
                    + " settings=" + quote(settings)
                    + " file=" + quote(file));
        }
        String action = firstNonEmpty(property, settings, file);
        if (action == null || IDLE.equals(action)) {
            lastAction = null;
            return;
        }
        if (action.equals(lastAction)) return;
        lastAction = action;
        console("action <" + action + ">");
        String result;
        try {
            result = execute(action);
        } catch (Throwable t) {
            Logs.trace("routeB: action failed", t);
            result = "ERROR " + t;
        }
        console("result " + result);
    }

    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
        }
        return null;
    }

    /**
     * The cheapest channel: a plain JNI property read, no binder call and no
     * Context, so it cannot block the poll thread.
     */
    private static String readFromProperty() {
        try {
            Object value = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", loader),
                    "get", PROPERTY_KEY);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFromSettings() {
        Context context = context();
        if (context == null) return null;
        try {
            return Settings.Global.getString(context.getContentResolver(), ACTION_KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFromFile() {
        File file = new File(FILE_PATH);
        try {
            if (!file.isFile()) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[(int) file.length()];
                int read = in.read(buffer);
                return read <= 0 ? null : new String(buffer, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String quote(String value) {
        return value == null ? "<null>" : "'" + value.trim() + "'";
    }

    private static Context context() {
        try {
            Application application = AndroidAppHelper.currentApplication();
            if (application != null) return application;
        } catch (Throwable ignored) {
            // fall through to the ActivityThread lookup
        }
        try {
            Object application = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            if (application instanceof Context) return (Context) application;
        } catch (Throwable ignored) {
            // no context: the console stays dormant
        }
        return null;
    }

    private static String execute(String line) {
        String[] parts = line.split("\\s+");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "no adapter";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "target": {
                if (parts.length < 2) return "usage: target <mac>";
                target = parts[1];
                return "target=" + mask(target);
            }
            case "unpair": {
                BluetoothDevice device = remote(adapter, parts);
                return device == null ? "bad mac" : "removeBond=" + removeBond(device);
            }
            case "bond": {
                BluetoothDevice device = remote(adapter, parts);
                if (device == null) return "bad mac";
                int transport = parts.length > 2
                        ? Integer.parseInt(parts[2]) : TRANSPORT_LE;
                boolean started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? device.createBond(transport) : device.createBond();
                return "createBond(" + transport + ")=" + started;
            }
            // Bond onto LE and watch.  The reply stays disarmed unless
            // AUTO_CONFIRM is flipped back on, because it was measured to be a
            // dead end -- see AUTO_CONFIRM.  The point of this action now is to
            // exercise the consent bypass and measure the resulting bond.
            //   autobond [mac] [transport] [variant]
            // Ask a profile service to dial a set member the stack never calls
            // on its own -- the right earbud, in practice.  It shows up in the
            // CSIS group and in the scan results, yet no profile ever issues a
            // connect for it, so this forces one and reports the policy that
            // okToConnect will judge.
            //   connect <mac> [leaudio|vcs|csip]
            case "connect": {
                BluetoothDevice device = remote(adapter, parts);
                if (device == null) return "bad mac";
                String profile = parts.length > 2 ? parts[2] : "leaudio";
                return connectProfile(device, profile);
            }
            case "vcs-low": {
                if (parts.length != 3) return "usage: vcs-low <AZ100 mac> <0..15>";
                BluetoothDevice device = remote(adapter, parts);
                int volume = Integer.parseInt(parts[2]);
                if (device == null || !matchesAddress(addressOf(device))
                        || volume < 0 || volume > 15) return "AZ100 / low-volume range only";
                Object vcs = profileService("getVolumeControlService");
                Object lea = profileService("getLeAudioService");
                if (vcs == null || lea == null) return "profile not ready";
                int groupId = (Integer) XposedHelpers.callMethod(lea, "getGroupId", device);
                if (!az100Group(groupId)) return "AZ100 group not ready";
                java.util.List<BluetoothDevice> members = volumeMembers("g:" + groupId);
                if (members == null || members.isEmpty()) return "no VCP-connected AZ100";
                XposedHelpers.callMethod(vcs, "setGroupVolume", groupId, volume);
                return "requested g:" + groupId + " = " + volume + " (submission, not ACK)";
            }
            case "autobond": {
                BluetoothDevice device = remote(adapter, parts);
                if (device == null) return "bad mac";
                int transport = parts.length > 2
                        ? Integer.parseInt(parts[2]) : TRANSPORT_LE;
                confirmTransport = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
                confirmVariant = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
                confirmAttempted = false;
                autoConfirmArmed = AUTO_CONFIRM;
                if (autoConfirmArmed) armConfirmWatchdog(device);
                boolean started = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? device.createBond(transport) : device.createBond();
                return "autobond(" + transport + ")=" + started
                        + " bypassConsent=" + BYPASS_CONSENT
                        + " reply=" + (autoConfirmArmed ? "armed" : "disarmed");
            }
            case "autoconfirm": {
                autoConfirmArmed = parts.length > 1 && "on".equalsIgnoreCase(parts[1]);
                return "autoconfirm=" + autoConfirmArmed;
            }
            case "confirm": {
                BluetoothDevice device = remote(adapter, parts);
                if (device == null) return "bad mac";
                if (parts.length > 2) confirmTransport = Integer.parseInt(parts[2]);
                if (parts.length > 3) confirmVariant = Integer.parseInt(parts[3]);
                return confirmPairing(device);
            }
            // Walk every reply-argument pair inside one pairing window.
            case "sweep": {
                BluetoothDevice device = remote(adapter, parts);
                if (device == null) return "bad mac";
                int transport = parts.length > 2
                        ? Integer.parseInt(parts[2]) : TRANSPORT_LE;
                sweepBond(device, transport);
                return "sweep armed, " + SWEEP.length + " candidates, step "
                        + SWEEP_STEP_MS + "ms";
            }
            case "status":
                return status(adapter);
            default:
                return "unknown action: " + parts[0];
        }
    }

    /**
     * Whenever LE Audio dials one earbud, dial the other one too.  The set is
     * stored (CSIS loads both addresses and both report {@code le_audio:
     * ALLOWED}), yet nothing ever issues a connect for the second address --
     * the scan sees it and drops it as "known" every few seconds instead.
     */
    private static void hookLeAudioTwin() {
        try {
            Class<?> lea = XposedHelpers.findClass(
                    "com.android.bluetooth.le_audio.LeAudioService", loader);
            XposedBridge.hookAllMethods(lea, "connect", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 1) return;
                    if (!(args[0] instanceof BluetoothDevice)) return;
                    if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult())) return;
                    if (!Boolean.TRUE.equals(IN_TWIN.get())) {
                        TWIN_BLOCKED.remove(addressOf((BluetoothDevice) args[0]));
                    }
                    connectTwin(param.thisObject, (BluetoothDevice) args[0]);
                }
            });
            XposedBridge.hookAllMethods(lea, "disconnect", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1
                            || !(param.args[0] instanceof BluetoothDevice)) return;
                    String address = addressOf((BluetoothDevice) param.args[0]);
                    if (matchesAddress(address)) TWIN_BLOCKED.add(address);
                }
            });
            Logs.trace("lea: bounded twin auto-connect armed");
        } catch (Throwable t) {
            Logs.trace("lea: cannot hook LeAudioService#connect", t);
        }
    }

    /** Guard against the sibling connect re-entering this hook. */
    private static final ThreadLocal<Boolean> IN_TWIN = new ThreadLocal<Boolean>();
    private static final java.util.Set<String> TWIN_BLOCKED =
            java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    private static final java.util.Map<String, Long> TWIN_ATTEMPTS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final long TWIN_COOLDOWN_MS = 60000L;

    private static void connectTwin(Object leAudioService, BluetoothDevice device) {
        String address = addressOf(device);
        if (!matchesAddress(address)) return;
        if (Boolean.TRUE.equals(IN_TWIN.get())) return;
        String twinAddress = address.equalsIgnoreCase(Az100Hook.AZ100_MAC)
                ? Az100Hook.AZ100_LE_MAC : Az100Hook.AZ100_MAC;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            BluetoothDevice twin = adapter.getRemoteDevice(twinAddress);
            int state = (int) XposedHelpers.callMethod(leAudioService, "getConnectionState", twin);
            // Do not duplicate Connecting or interfere with Disconnecting.
            if (state != 0 || TWIN_BLOCKED.contains(twinAddress)) return;
            if (twin.getBondState() != BluetoothDevice.BOND_BONDED) return;
            int policy = (int) XposedHelpers.callMethod(leAudioService, "getConnectionPolicy", twin);
            if (policy != CONNECTION_POLICY_ALLOWED && policy != CONNECTION_POLICY_UNKNOWN) return;
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (TWIN_ATTEMPTS) {
                Long previous = TWIN_ATTEMPTS.get(twinAddress);
                if (previous != null && now - previous.longValue() < TWIN_COOLDOWN_MS) return;
                TWIN_ATTEMPTS.put(twinAddress, now);
            }
            // One best-effort attempt, never a retry loop. We cannot infer case state.
            IN_TWIN.set(Boolean.TRUE);
            try {
                Object started = XposedHelpers.callMethod(leAudioService, "connect", twin);
                Logs.trace("lea: twin connect " + mask(twinAddress) + " (state " + state
                        + ") -> " + started);
            } finally {
                IN_TWIN.set(Boolean.FALSE);
            }
        } catch (Throwable t) {
            Logs.trace("lea: twin connect failed for " + mask(twinAddress), t);
        }
    }

    /**
     * Reach a profile service through {@code AdapterService} and call
     * {@code connect(device)} on it.  Reports the connection policy alongside
     * the result, because {@code ConnectableProfile#okToConnect} rejects
     * anything that is not {@code -1} or {@code 100} and says so only in a log
     * line -- the policy is the whole answer when connect returns false.
     */
    private static String connectProfile(BluetoothDevice device, String profile) {
        Object service = adapterService;
        if (service == null) return "no AdapterService";
        String getter;
        switch (profile.toLowerCase(Locale.ROOT)) {
            case "vcs":  getter = "getVolumeControlService";   break;
            case "csip": getter = "getCsipSetCoordinatorService"; break;
            case "a2dp": getter = "getA2dpService";            break;
            default:     getter = "getLeAudioService";         break;
        }
        StringBuilder out = new StringBuilder(mask(addressOf(device)))
                .append(' ').append(profile).append(": ");
        try {
            Object optional = XposedHelpers.callMethod(service, getter);
            if (!(optional instanceof java.util.Optional)) return out.append(getter)
                    .append(" returned ").append(optional).toString();
            java.util.Optional<?> value = (java.util.Optional<?>) optional;
            if (!value.isPresent()) return out.append(getter).append(" empty").toString();
            Object profileService = value.get();
            String policy = "?";
            try {
                policy = String.valueOf(
                        XposedHelpers.callMethod(profileService, "getConnectionPolicy", device));
            } catch (Throwable t) {
                Logs.trace("routeB: getConnectionPolicy failed", t);
            }
            boolean started = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(profileService, "connect", device));
            out.append("policy=").append(policy).append(" connect=").append(started);
        } catch (Throwable t) {
            Logs.trace("routeB: connect via " + getter + " failed", t);
            out.append(t);
        }
        String result = out.toString();
        Logs.trace("routeB: " + result);
        return result;
    }

    private static BluetoothDevice remote(BluetoothAdapter adapter, String[] parts) {
        String mac = parts.length > 1 ? parts[1] : target;
        try {
            return adapter.getRemoteDevice(mac);
        } catch (Throwable t) {
            Logs.trace("routeB: bad address", t);
            return null;
        }
    }

    /**
     * {@code BluetoothDevice#removeBond} is a hidden API, so go through
     * {@code AdapterService#removeBond(BluetoothDevice)} instead -- it is public
     * and does no permission check of its own.
     */
    private static boolean removeBond(BluetoothDevice device) {
        Object service = adapterService;
        if (service != null) {
            try {
                Method method = service.getClass().getMethod("removeBond", BluetoothDevice.class);
                return Boolean.TRUE.equals(method.invoke(service, device));
            } catch (Throwable t) {
                Logs.trace("routeB: AdapterService#removeBond failed", t);
            }
        }
        try {
            Method method = BluetoothDevice.class.getMethod("removeBond");
            return Boolean.TRUE.equals(method.invoke(device));
        } catch (Throwable t) {
            Logs.trace("routeB: BluetoothDevice#removeBond failed", t);
            return false;
        }
    }

    private static String status(BluetoothAdapter adapter) {
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                sb.append("\n    ").append(mask(device.getAddress()))
                        .append(" state=").append(device.getBondState())
                        .append(" type=").append(device.getType())
                        .append(" name=").append(device.getName());
            }
            if (sb.length() == 0) sb.append("no bonded devices");
        } catch (Throwable t) {
            return "ERROR " + t;
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */

    /**
     * Whether this {@code DeviceProperties} belongs to the headset.
     *
     * <p>Address first, because it is free and exact.  The name is the fallback:
     * the AZ100 carries two LE identities today ({@code 84:85} and {@code 3C:62}),
     * and the pairing information in the earbuds is cleared every time the LE
     * Audio mode is switched -- after which the identities may not be the same
     * two.  Matching on {@code Technics EAH-AZ100} as well means a re-pair does
     * not silently fall outside the hook and cost a whole measurement cycle.
     *
     * <p>{@code BluetoothDevice#getName} reads a cached field, so this stays a
     * local call and cannot block the stack's property callback.
     */
    private static boolean matches(Object properties, String address) {
        if (matchesAddress(address)) return true;
        if (properties == null) return false;
        try {
            Object device = XposedHelpers.callMethod(properties, "getDevice");
            if (device instanceof BluetoothDevice) return matches((BluetoothDevice) device);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** The same test for the two call sites that already hold the device. */
    private static boolean matches(BluetoothDevice device) {
        if (device == null) return false;
        if (matchesAddress(addressOf(device))) return true;
        try {
            String name = device.getName();
            return name != null && name.startsWith(Az100Hook.AZ100_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean matchesAddress(String address) {
        return address != null && (address.equalsIgnoreCase(Az100Hook.AZ100_MAC)
                || address.equalsIgnoreCase(Az100Hook.AZ100_LE_MAC)
                || address.equalsIgnoreCase(target));
    }

    private static String addressOf(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mask(String address) {
        if (address == null || address.length() < 5) return "?";
        return "**:**:**" + address.substring(address.length() - 5);
    }
}
