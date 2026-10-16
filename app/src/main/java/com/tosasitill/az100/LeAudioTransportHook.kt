package com.tosasitill.az100

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Route B: make the AZ100 bond over LE instead of BR/EDR.
 *
 * ## The gap this closes
 *
 * The headset side is already fine -- since 13:20 the AZ100 advertises
 * `0000184e 184f 1850 1844 184d` in its LE payload, and the phone's own verbose log
 * shows it parsing them.  What is missing is the *bond*:
 *
 * ```
 * 13:36:29.776  btif_dm_create_bond: transport=0            <- AUTO
 * 13:36:39.789  bond_state_changed [BT_TRANSPORT_BR_EDR] -> BONDED, bond_type: 1
 * 13:36:39.812  oplus_delay_ble_smp_over_br: start smp      <- CTKD
 * 13:36:39.904  bond_state_changed [BT_TRANSPORT_LE] -> BONDED
 * ```
 *
 * The LE keys exist, but only because CTKD derived them from the BR/EDR link.
 * `RemoteDevices$DeviceProperties` ends up with `mUuidsBrEdr` populated and
 * `mUuidsLe` left `null`, so `getUuids() = mUuidsBrEdr ∪ mUuidsLe` never contains
 * `0x184E`, so `LocalBluetoothProfileManager#updateProfiles` never adds
 * `LeAudioProfile` to the device's profile list, so
 * `BluetoothDetailsProfilesController` has nothing to render a switch from.  Every
 * OPPO vendor path in `OplusLeAudioPhonePolicyExt` is gated on the same
 * `containsUuid(uuids, LE_AUDIO)` test, which is why
 * `com.flyfish233.coloros.leaudiofx` (which hooks exactly those methods)
 * necessarily no-ops.
 *
 * ## What this hook changes
 *
 * `AdapterService#createBond` receives the transport as its second argument and
 * passes it straight to `AdapterService#sendCreateBondMessage`, which is the single
 * point that posts `mBondStateMachine.obtainMessage(1)` with `arg1 = transport`.
 * The vendor layer does not interfere: `OplusAdapterServiceExtImpl#createBond` only
 * forces `transport = 1` when `sharingDeviceType` is `OplusWatch` / `ebadge` /
 * `3rdEarBuds`, and otherwise forwards `transport2 = transport` untouched.
 *
 * So both methods are hooked and the argument is rewritten to [TRANSPORT_LE] when
 * the device is the AZ100.  Everything else is left alone; the rewrite is a no-op
 * for any other address.
 *
 * Both are hooked rather than just the chokepoint because `createBond` itself
 * rewrites `i` to 1 or 2 in the re-bond case (`bondState == 12 && deviceType == 3`);
 * hooking the chokepoint as well means the value that actually reaches the state
 * machine is the LE one.
 *
 * ## Scope
 *
 * This is the one deliberate exception to the "hooks run in `com.oplus.melody`
 * only" rule in `AGENTS.md`: the code that has to change lives in
 * `com.android.bluetooth`, so the module has to be scoped there as well.
 * [ENABLED] turns the whole thing off without touching the Melody hooks.
 *
 * ## Driving the experiment without touching the UI
 *
 * Re-pairing cannot be done from `adb` through `cmd bluetooth_manager` (it only
 * exposes enable/disable/factoryReset).  Instead this class polls a system property
 * from inside the Bluetooth process:
 *
 * ```
 * adb shell "setprop debug.az100.action 'status'"
 * adb shell "setprop debug.az100.action 'unpair <AZ100 mac>'"
 * adb shell "setprop debug.az100.action 'autobond <AZ100 mac> 2'"
 * adb shell "setprop debug.az100.action 'none'"
 * adb shell su -c "tail /data/misc/bluetooth/az100_console.log"
 * ```
 *
 * A property rather than a file because the root shell is `u:r:ksu:s0` under
 * enforcing SELinux and cannot write into `/data/misc/bluetooth`
 * (`bluetooth_data_file`), while a JNI property read costs the Bluetooth process
 * nothing and needs no [Context].  `Settings.Global` was tried first and abandoned:
 * `AndroidAppHelper.currentApplication()` returned null in that implementation,
 * so the content resolver was unreachable. `macos/az100-routeb.sh` wraps the quoting,
 * which matters -- `adb shell setprop` splits on spaces and silently does nothing.
 *
 * The action runs through the ordinary [BluetoothDevice] API from inside the
 * Bluetooth process, which already holds `BLUETOOTH_CONNECT`, so `AdapterService`'s
 * `Util.isPackageNameAccurate(this, str, Binder.getCallingUid())` check passes
 * without a helper app and without a single simulated tap.
 */
object LeAudioTransportHook {

    /** Master switch: false leaves the Bluetooth process completely untouched. */
    private const val ENABLED = true

    /** Route B itself: rewrite the bond transport to LE for the AZ100. */
    private const val FORCE_LE_TRANSPORT = true

    /**
     * Log the whole UUID pipeline, so the outcome is measurable either way.
     *
     * Read from the settings page rather than fixed here -- see [Prefs.diagnose].
     * Only logging is behind it; every behaviour hook is installed unconditionally.
     */
    private fun diagnose(): Boolean = Prefs.diagnose

    /**
     * Answer the LE "Just Works" consent from inside the Bluetooth process.
     *
     * **Measured dead end -- left off.**  This was the first idea, and it does not
     * work on this build, for a reason that also rules out a manual tap.  Forcing
     * the bond onto LE makes the stack pick `SMP_MODEL_SEC_CONN_JUSTWORKS`, which
     * this build answers with `SMP_CONSENT_REQ_EVT` instead of pairing silently.
     * The reply the dialog's own "pair" button sends is
     * `AdapterServiceBinder#setPairingConfirmation` ->
     * `getNative().sspReply(addr, 0, true, 0)`, which reaches `btif_dm_ssp_reply`
     * and branches on a single global:
     *
     * ```
     * if (pairing_cb.is_le_only) {
     *   if (accept) BTA_DmBleSecurityGrant(bd_addr, BTA_DM_SEC_GRANTED);
     * } else {
     *   BTA_DmConfirm(bd_addr, accept);   // <-- BR/EDR handler, what we hit
     * }
     * ```
     *
     * `is_le_only` is written only by `btif_dm_ble_sec_req_evt`, and only *after* it
     * calls `bond_state_changed(BONDING)`, which early-returns because
     * `btif_dm_cb_create_bond` already set `pairing_cb.state = BONDING`.  So the
     * flag is never written and the reply always lands on the BR/EDR path:
     *
     * ```
     * btif_dm_ssp_reply: accept=1
     * btm_confirm_req_reply() State: IDLE  Res: 0
     * btm_confirm_req_reply: Unexpected pairing confirm ... pairing_state: IDLE
     * ```
     *
     * A 12-candidate sweep over every `(transport, variant)` pair produced that
     * identical path, confirming the arguments are irrelevant.  Because the
     * Settings dialog routes through this exact call, **tapping the dialog by hand
     * fails identically** -- so the consent has to be avoided, not answered.  See
     * [BYPASS_CONSENT].
     */
    private const val AUTO_CONFIRM = false

    /**
     * Stop the LE "Just Works" consent dialog from being raised for the AZ100.
     *
     * Both places that raise the consent -- `smp_process_peer_nonce`'s JUSTWORKS
     * branch and `smp_decide_association_model`'s ENCRYPTION_ONLY branch -- share
     * one guard:
     *
     * ```
     * if (!GetInterfaceToProfiles()->config->isAndroidTVDevice() &&
     *     (p_cb->local_io_capability == SMP_IO_CAP_IO ||
     *      p_cb->local_io_capability == SMP_IO_CAP_KBDISP)) {
     *   p_cb->cb_evt = SMP_CONSENT_REQ_EVT;      // the dialog
     * } else {
     *   smp_sm_event(p_cb, SMP_SC_PHASE1_CMPLT_EVT, NULL);   // straight to phase 2
     * }
     * ```
     *
     * The phone advertises `KeyboardDisplay`, so the first half is true.  The other
     * half, `isAndroidTVDevice()`, is computed in Java and handed to the stack once
     * at start-up: `AdapterService.init` does
     * `getPackageManager().hasSystemFeature("android.software.leanback_only")` and
     * passes the result as the 6th argument of `AdapterNativeInterface#init`, whose
     * JNI forwards it verbatim as `isAtvDevice`.
     *
     * This module used to fake that one Java call.  It worked, but the flag is read
     * **once**, at process start, and then lives in native as a global for the whole
     * process lifetime -- so the spoof could not be scoped to the AZ100 even in
     * principle: at process start nothing is pairing yet, and every other device
     * paired later inherited it.  The whole Bluetooth process believed it was an
     * Android TV.
     *
     * The suppression is therefore done per device, in native, by
     * [NativeLeaDeviceHook]: `oplus_handle_le_audio_ssp_auto_consent(RawAddress const&)`
     * is the vendor's own per-address consent decision, and the patch makes it answer
     * the AZ100 exactly the way it already answers OPPO's own earbuds
     * (`btif_dm_ssp_reply(addr, CONSENT, 1)`, then return false so no dialog is
     * raised).  Every other address still reaches the original body and keeps stock
     * behaviour.
     *
     * Scope and blast radius: this only suppresses the *consent prompt* for LE Just
     * Works / encryption-only pairing of the AZ100, which is exactly the behaviour a
     * TV build already has.  It does not weaken or skip pairing, and it is one switch
     * away from being reverted -- set this to false and the consent patch is not
     * installed.
     */
    private const val BYPASS_CONSENT = true

    /**
     * Whether the per-device consent suppression should be installed.  Read by
     * [NativeLeaDeviceHook], which owns the actual patch; kept here so the switch
     * that documents the behaviour is also the one that controls it.
     */
    internal val consentBypassEnabled: Boolean get() = BYPASS_CONSENT

    /**
     * How long to wait after the pairing request before answering it.  Long enough
     * for the dialog to be drawn (so the run is still observable on screen), far
     * short of the SMP timer.
     */
    private const val CONFIRM_DELAY_MS = 800L

    /**
     * Fallback for the case where `sendPairingRequestIntent` never runs.  Waits this
     * long, then answers if the device is still bonding.
     */
    private const val WATCHDOG_DELAY_MS = 4000L

    /**
     * Put `0x184E` into the LE UUID array the framework stores for the AZ100.
     *
     * This is the one lever in the whole route-B experiment that turns out to be
     * load-bearing.  The vendor allow-list is *already* open on this device:
     *
     * ```
     * persist.bluetooth.leaudio.bypass_allow_list   = true
     * persist.bluetooth.enable_dual_mode_audio      = true
     * ```
     *
     * so `PhonePolicy`'s gate collapses to the UUID test alone:
     *
     * ```
     * if (leAudioService.isPresent()
     *     && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)   // <-- 0x184E
     *     && leAudioService.getConnectionPolicy(device) != 0
     *     && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())
     *     && (isBypassLeAudioAllowlist || shouldEnableHapByDefault
     *         || isLeAudioAllowed(device) || isLeAudioOnlyDevice(device))) {
     *   allowed = true;
     * }
     * ```
     *
     * Every other conjunct already holds.  The UUID array is the one that does not,
     * and the reason is a vendor filter: LE Audio UUIDs are stripped out of the
     * advertising data before it reaches Java
     * (`oplus_btm_ble_process_adv_pkt_cont: adv data modified: removal of lea uuids`),
     * the allow-list lookup that would have put them back reports
     * `isLEAudioDeviceInWhiteList: LE Audio device is not in Whitelist`, and so
     * `mUuidsLe` ends up holding only `0x180F` while `LeAudioService` reports
     * `No valid descriptor for device`.
     *
     * The device is nonetheless a genuine LE Audio device.  Its own GATT database,
     * dumped out of the native cache, carries the complete service set -- and the
     * dump names `84:85` as the owner right below it:
     *
     * ```
     * bta_gattc_display_explore_record: Service: handle=0x1103, end_handle=0x110f, uuid=0000184e  (ASCS)
     * bta_gattc_display_explore_record: Service: handle=0x1200, end_handle=0x1212, uuid=00001850  (PACS)
     * bta_gattc_display_explore_record: Service: handle=0x1301, end_handle=0x130b, uuid=00001844  (VCS)
     * bta_gattc_display_explore_record: Service: handle=0x6001, end_handle=0x600a, uuid=00001846  (CSIS)
     * bta_gattc_display_explore_record: Service: handle=0x7001, end_handle=0x7002, uuid=00001853  (TBS)
     * bta_gattc_display_explore_record: Service: handle=0xa201, end_handle=0xa206, uuid=0000184f  (BASS)
     * gatt_get_ch_state: xx:xx:xx:xx:84:85, transport BT_TRANSPORT_LE, ch_state=GATT_CH_OPEN
     * ```
     *
     * So this restores UUIDs the device really does expose, for exactly two
     * addresses, rather than inventing a capability.  [spliceIntoLeUuids] is where
     * that happens, and it is gated on [matches].
     *
     * ## Why CSIS and not just ASCS
     *
     * `0x184E` alone got the headset talking, but only to its left half:
     * `Audio Locations: Sink= left`, `Group 1 size 1`, and
     * `GetGroupSinkStrategy: Group strategy set to: MONO_ONE_CIS_PER_DEVICE`.  AOSP's
     * chooser (`device_groups.cc`) explains exactly why:
     *
     * ```
     * int expected_group_size = Size();
     * if (expected_group_size > 1) return MONO_ONE_CIS_PER_DEVICE;   // two buds: one mono CIS each
     * auto locations = audio_locations_.get(direction).value();
     * if (!(locations & AnyLeft) || !(locations & AnyRight) || locations.none())
     *   return MONO_ONE_CIS_PER_DEVICE;                              // one bud declaring one side
     * if (channel_count_bitmap == 1) return STEREO_TWO_CISES_PER_DEVICE;
     * return STEREO_ONE_CIS_PER_DEVICE;
     * ```
     *
     * One earbud that declares `0x0001` can therefore never be stereo, and the only
     * route left is the first branch: get *both* earbuds into the group.  That is
     * CSIS's job -- and CSIS is the UUID the vendor strips hardest.
     * `CsipSetCoordinatorService` reads it straight off `BluetoothDevice.getUuids()`
     * and, finding nothing, refuses before it ever reaches the headset:
     *
     * ```
     * CsipSetCoordinatorService: Cannot connect to xx:xx:xx:xx:84:85 : Remote does not have CSIS UUID
     * dumpsys bluetooth_manager / Profile: CsipSetCoordinatorService
     *   mFoundSetMemberToGroupId:            <-- empty: not one set member found
     * ```
     *
     * The set membership machinery itself is intact underneath -- the SIRK exchange
     * already ran once, during the bond:
     *
     * ```
     * smp_act.cc: smp_sirk_verify: addr:xx:xx:xx:xx:84:85
     * csis_client.cc: VerifySetMember: Device: xx:xx:xx:xx:84:85
     * csis_client.cc: VerifySetMember: Valid - new member
     * ```
     *
     * It just never gets to look for the second member, because the Java layer in
     * front of it is missing the UUID that would let it start.
     *
     * ## Status: live again (2026-10-12)
     *
     * This splice was the first working route, but it fights the vendor filter
     * head-on.  Splicing `184E` is exactly what makes `isLeAudioDevice()` return
     * true, and that is the condition which arms `OplusLeAudioRemoteDevice`'s strip
     * in the first place -- so every spliced UUID gets deleted again a moment later,
     * and the module has to keep re-injecting on every property change.  That
     * re-entry was the main source of the crashes this module has been fighting.
     *
     * It was briefly retried via the native route ([NativeLeaDeviceHook] forcing
     * `oplus_btif_is_lea_device()` true in-process), but on the shipping ROM that
     * alone does not put `0000184E` where Settings looks, and the switch vanished.
     * The splice is therefore back on as the primary route; the native patch stays as
     * a second layer so the vendor filter stops stripping where it applies.
     */
    private const val SPLICE_LE_AUDIO_UUIDS = true

    /**
     * Register the AZ100 into OPPO's native `OplusLeAudioWhiteList` interop list.
     *
     * ## Status: retired (2026-10-12)
     *
     * This wrote the **address key**, and it did work: on device the entry lands in
     * the interop database, persists to
     * `/data/misc/bluedroid/bt_iot_update_list.conf`, and
     * `interop_oplus_match_addr("OplusLeAudioWhiteList", addr)` returns true for both
     * AZ100 addresses.
     *
     * It was not enough.  `oplus_btif_is_lea_device()` -- the gate the native LEA
     * paths *and* the UUID filter actually consult -- never looks at addresses.  It
     * asks `interop_match_vendor_and_3bytes_product_id()` against a hard-coded
     * `.rodata` table of OPPO's own product ids, and no JNI entry point can add a
     * vendor/product pair to it.  An address entry cannot reach that lookup, so
     * `is_oplus_lea` stayed false and the UUIDs stayed stripped.
     *
     * [NativeLeaDeviceHook] replaces this by opening the gate itself, in-process.
     * Kept for reference and A/B.
     */
    private const val NATIVE_LEA_WHITELIST = false

    /**
     * Repair OPPO's dead `BassClientService.mOplusBassClientService` field, which is
     * never assigned on this ROM and is dereferenced without a null check in three
     * places -- `connect()` being the one that killed the whole profile-connect batch
     * once [NativeLeaDeviceHook] let `184F` (BASS) through.  See [BassClientFixHook].
     */
    private const val BASS_NPE_FIX = true

    /**
     * Stop OPPO's dual-mode policy from forbidding A2DP/HFP once
     * [NativeLeaDeviceHook] has let `0000184E` through.  The AZ100 fails OPPO's
     * proprietary Echo coexistence handshake, so the vendor code classifies it as
     * "not lea coexistence device" and zeroes both classic profiles -- leaving the
     * headset with no usable link at all.  See [OplusDualModeCoexistHook].
     */
    private const val OPLUS_DUAL_MODE_COEXIST_FIX = true

    /**
     * Drop VCS writes that would not change the headset's volume.  A no-op
     * `SetAbsoluteVolume` produces no Volume State notification, the native
     * operation queue never marks that operation complete, and everything queued
     * behind it is stuck forever -- see [hookVolumeWriteGuard].
     */
    private fun suppressRedundantVcsWrites(): Boolean = Prefs.volumeFix

    /**
     * `VolumeControlNativeInterface` raises any VCS value of 1..15 up to
     * [LEAUDIO_VOLUME_MIN] (16), so the bottom several media steps all land on the
     * same loudness and 16 is the quietest level reachable at all.  Bypass that clamp
     * so the bottom of the slider is actually quiet.  0 still means mute -- the stock
     * code already passes 0 through untouched, which is exactly the "one step above
     * silence" the clamp otherwise eats.
     */
    private fun lowerVolumeFloor(): Boolean = Prefs.volumeFix

    /**
     * The stack only ever dials one member of the AZ100 set, so after every
     * Bluetooth restart half the pair is silent.  Verified on device: forcing
     * `LeAudioService#connect` on the sibling works, both stay connected, and the
     * group ends up with `Sink= left` and `Sink= right`.
     */
    private fun twinAutoConnect(): Boolean = Prefs.twin

    const val TRANSPORT_AUTO = 0
    const val TRANSPORT_BREDR = 1
    const val TRANSPORT_LE = 2

    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
    private const val REMOTE_DEVICES = "com.android.bluetooth.btservice.RemoteDevices"
    private const val DEVICE_PROPERTIES = "$REMOTE_DEVICES\$DeviceProperties"
    private const val BOND_STATE_MACHINE = "com.android.bluetooth.btservice.BondStateMachine"
    private const val VOLUME_CONTROL_SERVICE = "com.android.bluetooth.vc.VolumeControlService"
    private const val VOLUME_CONTROL_NATIVE_INTERFACE =
        "com.android.bluetooth.vc.VolumeControlNativeInterface"
    private const val LE_AUDIO_SERVICE = "com.android.bluetooth.le_audio.LeAudioService"

    /**
     * `VolumeControlNativeInterface#LEAUDIO_VOLUME_MIN`.  Both `setVolume` and
     * `setGroupVolume` clamp with `if (volume != 0 && volume < 16) volume = 16;`, so
     * a media index of 1..4 all collapse onto 16 while index 0 stays a true 0.
     */
    private const val LEAUDIO_VOLUME_MIN = 0x10

    /**
     * `BluetoothProfile` connection policies.  `okToConnect` accepts only
     * [CONNECTION_POLICY_UNKNOWN] and [CONNECTION_POLICY_ALLOWED]; `FORBIDDEN` is
     * rejected outright, which is what silently killed VCP even after the UUID splice
     * put `0x1844` back.
     */
    private const val CONNECTION_POLICY_FORBIDDEN = 0
    private const val CONNECTION_POLICY_ALLOWED = 100
    private const val CONNECTION_POLICY_UNKNOWN = -1

    /** The four UUID arrays, in the order `getUuids()` combines them. */
    private val UUID_FIELDS = arrayOf(
        "mUuidsBrEdr",
        "mUuidsLe",
        "mUuidsFromExtendedInquiryResponse",
        "mUuidsFromLeAdvertisingData",
    )

    /**
     * The slice of the LE Audio family that the Java stack actually keys off, and
     * therefore the slice [spliceIntoLeUuids] puts back.
     *
     * - `0x184E` ASCS -- `PhonePolicy`'s transport gate, and what makes the Settings
     *   toggle appear.
     * - `0x1846` CSIS -- what `CsipSetCoordinatorService` needs before it will
     *   connect and look for the other earbud.
     * - `0x1844` VCS -- what `VolumeControlService` needs before it will connect and
     *   take over the volume at all.
     *
     * ## Why VCS is here
     *
     * Absolute volume over LE Audio *is* VCP, and VCP starts at the same UUID test as
     * everything else in this family.  When the LE Audio device connects,
     * `LeAudioService` reaches for the volume controller on its own:
     *
     * ```
     * // LeAudioService.smali:13001-13021
     * Optional<VolumeControlService> vcs = getAdapterService().getVolumeControlService();
     * if (vcs != null && vcs.getConnectionState(device) == STATE_DISCONNECTED) {
     *   vcs.connect(device);
     * }
     * ```
     *
     * and `VolumeControlService#connect` refuses before it sends a single packet
     * (`VolumeControlService.smali:2706-2724`):
     *
     * ```
     * ParcelUuid[] uuids = getAdapterService().getRemoteUuids(device);
     * if (!Util.arrayContains(uuids, BluetoothUuid.VOLUME_CONTROL)) {   // 0x1844
     *   Log.e(TAG, "Cannot connect to " + device + " : Remote does not have Volume Control UUID");
     *   return false;
     * }
     * ```
     *
     * So the phone never becomes the volume controller, and the headset's own volume
     * is left wherever it happens to sit -- which is heard as "the slider does not
     * really control this thing, and the floor is too loud".  Restoring `0x1844` is
     * the whole fix; the phone then drives the headset's 0..255 VCS volume from its
     * own stream index.
     *
     * Deliberately not the whole family.  The headset also exposes VOCS `0x1845`,
     * MCS `0x184D`, TBS `0x1853` and BASS `0x184F`, but each of those switches on
     * another controller in the phone and none of them is needed for stereo or for
     * volume.  Three UUIDs is the smallest change that can be measured.
     */
    private val LE_AUDIO_UUIDS = arrayOf(
        "0000184e-0000-1000-8000-00805f9b34fb",
        "00001846-0000-1000-8000-00805f9b34fb",
        "00001844-0000-1000-8000-00805f9b34fb",
    )

    /**
     * The feature string `AdapterService.init` reads once to decide whether this
     * device is a TV.  Kept for reference only: faking it was the old consent
     * bypass and is deliberately no longer done, because the answer becomes a
     * process-wide native global that cannot be scoped to the AZ100.  See
     * [BYPASS_CONSENT].
     */
    private const val LEANBACK_ONLY = "android.software.leanback_only"

    /** Disabled in production; behavior hooks remain enabled. */
    private const val CONSOLE = false

    private const val ACTION_KEY = "az100_action"
    private const val PROPERTY_KEY = "debug.az100.action"
    private const val FILE_PATH = "/data/misc/bluetooth/az100_action"

    /**
     * Console output.  The Bluetooth process owns this directory, so it can always
     * append here, and a root shell can always read it back -- unlike logcat, whose
     * tag survives a process as chatty as this one only by luck.
     */
    private const val LOG_PATH = "/data/misc/bluetooth/az100_console.log"

    /**
     * The poll tick.
     *
     * Deliberately left at 2 s.  A longer idle tick was tried and reverted: it breaks
     * the documented adb workflow in `macos/az100-routeb.sh`, which sets the property
     * and reads the log back after a fixed wait, and it makes the first action after
     * a quiet period take up to a full tick to be noticed.  The cost that mattered
     * was never the tick itself -- a `SystemProperties` read is a JNI call with no
     * binder and no Context, and a sleeping thread does not hold the SoC awake -- it
     * was the `Settings.Global` query and the `stat()` that used to run on every
     * tick.  Those now only happen while an action is pending; see [consume].
     */
    private const val POLL_MS = 2000L

    /** Resolved once: the `SystemProperties.get(String)` method. */
    @Volatile
    private var systemPropertiesGet: Method? = null

    @Volatile
    private var installed = false

    @Volatile
    private var loader: ClassLoader? = null

    /** The live `AdapterService`, captured in its `onCreate`. */
    @Volatile
    private var adapterService: Any? = null

    /** Extra address to treat as the headset, settable at runtime. */
    @Volatile
    private var target = Az100Hook.AZ100_MAC

    /** Last action seen, so each one runs once. */
    @Volatile
    private var lastAction: String? = null

    /**
     * Whether the next pairing request for the headset is answered automatically.
     * Armed by the `autobond` console action and disarmed again as soon as it has
     * been used, so it never lingers as a standing policy.
     */
    @Volatile
    private var autoConfirmArmed = false

    /** One consent answer per bond attempt, whichever path gets there first. */
    @Volatile
    private var confirmAttempted = false

    /**
     * Arguments for the consent reply.
     *
     * The Settings dialog's own answer is
     * `AdapterNativeInterface#sspReply(addr, 0, accept, 0)` -- verbatim, per the smali
     * of `AdapterServiceBinder#setPairingConfirmation`.  On this build that lands on
     * the BR/EDR numeric-comparison handler and is dropped:
     *
     * ```
     * sspReplyNative:
     * btif_dm_ssp_reply: accept=1
     * btm_confirm_req_reply() State: IDLE  Res: 0
     * btm_confirm_req_reply: Unexpected pairing confirm for ...84:85, pairing_state: IDLE
     * ```
     *
     * so the two numeric arguments are kept settable and the console exposes them:
     * `autobond <mac> <transport> <variant>`.
     */
    @Volatile
    private var confirmTransport = 0

    @Volatile
    private var confirmVariant = 0

    /**
     * The last VCS value actually handed to the native stack, keyed `"g:<groupId>"`
     * for `setGroupVolume` and `"d:<address>"` for `setVolume`.  Used by
     * [hookVolumeWriteGuard] to drop writes that would not change anything -- see the
     * comment there for why that matters.
     */
    private val LAST_PUSHED = ConcurrentHashMap<String, Int>()

    /**
     * What the headset itself last reported, per address, via
     * `handleVolumeControlChanged`.  This is the value that decides whether a write
     * is a no-op -- [LAST_PUSHED] only covers writes we made ourselves, so on a
     * reconnect, when the headset has kept its old volume, it is the only thing that
     * knows the connect-time push would change nothing.
     */
    private val LAST_REPORTED = ConcurrentHashMap<String, Int>()

    /** Sentinel written between actions; ignored rather than run. */
    private const val IDLE = "none"

    @Volatile
    private var ticks = 0

    fun install(param: HookRuntime) {
        if (!ENABLED) return
        val classLoader = param.appClassLoader ?: return
        synchronized(this) {
            if (installed) return
            installed = true
            loader = classLoader
        }
        // XposedBridge.log never lands when it is called from a binder thread, and
        // createBond/sendUuidsInternal both run on one.  Mirroring to a file the
        // Bluetooth process owns is the only channel that always works.
        Logs.traceToFile(File("/data/misc/bluetooth"))
        if (NATIVE_LEA_WHITELIST) NativeLeaWhitelistHook.install(param)
        NativeLeaDeviceHook.install(param)
        if (BASS_NPE_FIX) BassClientFixHook.install(param)
        if (OPLUS_DUAL_MODE_COEXIST_FIX) OplusDualModeCoexistHook.install(param)
        hookBondEntryPoints(param)
        // Installed even with AUTO_CONFIRM off: this is the only place that records
        // whether the consent dialog was raised at all, which is how the bypass below
        // is judged to have worked.
        hookPairingRequest(param)
        if (BYPASS_CONSENT) hookConsentBypass(param)
        if (SPLICE_LE_AUDIO_UUIDS || diagnose()) hookUuidPipeline(param)
        // Behaviour first, diagnostics second -- and never behind DIAGNOSE.
        //
        // Measured 2026-10-13: hookVolumeControl() used to be called only when
        // DIAGNOSE was on, but four of its six hooks are behaviour, not logging.  In
        // particular captureVolumeReport() is the only writer of LAST_REPORTED, which
        // is the only thing that can tell knownVolume() that a reconnect-time volume
        // push would be a no-op -- and the guard that drops those redundant writes
        // lives behind the same switch.  With DIAGNOSE off, the duplicate
        // SetAbsoluteVolume reaches native, the VCP operation queue waits for a Volume
        // State notification that never comes, and every operation queued behind it is
        // stuck.
        hookVolumeBehavior(param)
        if (diagnose()) hookVolumeDiagnostics(param)
        // Both of these read their preference at call time rather than here, so
        // the settings page is telling the truth when it says a change takes
        // effect on the next connection: registering them conditionally meant a
        // switch flipped after the Bluetooth process started did nothing at all
        // until the process was restarted, which is not something the UI asks for
        // or the user can see.
        hookVolumeWriteGuard(param)
        hookLeAudioTwin(param)
        if (CONSOLE) startConsole(param)
    }

    /* ------------------------------------------------------------------ */
    /* the behaviour change                                                */
    /* ------------------------------------------------------------------ */

    private fun hookBondEntryPoints(param: HookRuntime) {
        val classLoader = loader ?: return
        val service = try {
            Reflect.findClass(ADAPTER_SERVICE, classLoader)
        } catch (t: Throwable) {
            Logs.trace("routeB: AdapterService not found", t)
            return
        }
        // The chokepoint: the only caller of it is createBond, and it is the last
        // place the transport is still a plain argument.
        hookTransport(param, service, "sendCreateBondMessage")
        // The public entry, so the rewrite is also visible in the log for the
        // caller-facing value rather than only the dispatched one.
        hookTransport(param, service, "createBond")
    }

    /**
     * `hookAllMethods` by name: every declared overload on the class itself, which
     * is why this is `hookAll` and not a single `hook`.
     */
    private fun hookTransport(param: HookRuntime, clazz: Class<*>, method: String) {
        try {
            with(param) {
                clazz.resolve().optional(silent = true).method { name = method }
                    .hookAll {
                        before { rewriteTransport(method, this) }
                    }
            }
            Logs.trace("routeB: hooked AdapterService#$method")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook $method", t)
        }
    }

    private fun rewriteTransport(where: String, param: HookCall) {
        val args = param.args
        if (args.size < 2) return
        val device = args[0] as? BluetoothDevice ?: return
        val transport = args[1] as? Int ?: return
        val address = addressOf(device)
        if (!matches(device)) {
            Logs.trace("bond: $where transport=$transport other ${mask(address)}")
            return
        }
        if (!FORCE_LE_TRANSPORT) {
            Logs.trace("bond: $where transport=$transport (force off) ${mask(address)}")
            return
        }
        if (transport == TRANSPORT_LE) {
            Logs.trace("bond: $where transport=2 (LE) already ${mask(address)}")
            return
        }
        args[1] = TRANSPORT_LE
        Logs.trace("bond: $where transport $transport -> 2 (LE) ${mask(address)}")
    }

    /* ------------------------------------------------------------------ */
    /* the LE consent, answered without a tap                              */
    /* ------------------------------------------------------------------ */

    /**
     * `BondStateMachine#sendPairingRequestIntent(BluetoothDevice, Optional<Integer>,
     * int variant, int pairingContext, int pairingAlgorithm)` is the single place that
     * turns a stack-level pairing request into the `ACTION_PAIRING_REQUEST` broadcast
     * the Settings dialog is built from.  Variant `3` is `PAIRING_VARIANT_CONSENT`,
     * which is what Just Works arrives as.
     *
     * It runs on the bond state machine's handler thread and is called *before* the
     * ordered broadcast goes out, so the hook still sees the request even if a vendor
     * branch ends up swallowing the broadcast.
     */
    private fun hookPairingRequest(param: HookRuntime) {
        val classLoader = loader ?: return
        try {
            val machine = Reflect.findClass(BOND_STATE_MACHINE, classLoader)
            with(param) {
                machine.resolve().optional(silent = true).method { name = "sendPairingRequestIntent" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.size < 3) return@before
                            val device = args[0] as? BluetoothDevice ?: return@before
                            val variant = args[2] as? Int ?: -1
                            val address = addressOf(device)
                            val headset = matches(device)
                            Logs.trace(
                                "pair: ${mask(address)} variant=$variant"
                                        + " armed=$autoConfirmArmed" + if (headset) " headset" else ""
                            )
                            if (!autoConfirmArmed || !headset) return@before
                            scheduleConfirm(device, "intent variant=$variant")
                        }
                    }
            }
            Logs.trace("routeB: hooked BondStateMachine#sendPairingRequestIntent")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook sendPairingRequestIntent", t)
        }
    }

    /**
     * The consent-dialog bypass, plus the measurement that proves it landed.
     *
     * The bypass itself is **not** here any more.  It used to answer `true` for
     * `hasSystemFeature("android.software.leanback_only")`, which is the one
     * input `AdapterService.onCreate` reads when deciding whether the stack may
     * raise the LE consent prompt.  That worked, but it made the entire
     * Bluetooth process believe it was an Android TV for its whole lifetime --
     * the flag is read once at startup (`AdapterService.init`, see
     * `AdapterService.java:3219`) and handed to native as a global, so it could
     * never be narrowed to the AZ100, and at process start no device is pairing
     * yet anyway.  Every other device paired in that process inherited the
     * spoof.
     *
     * The dialog is now suppressed per device instead, in
     * [NativeLeaDeviceHook]'s consent patch: `oplus_handle_le_audio_ssp_auto_consent()`
     * takes the peer address, and for the AZ100 it answers the request the same
     * way OPPO answers it for its own earbuds.  See [BYPASS_CONSENT].
     *
     * What remains here is the measurement: `sendPairingRequestIntent` is hooked
     * in [hookPairingRequest], and this logs the `AdapterNativeInterface#init`
     * arguments so the 6th can be seen.  Both are trace-only.
     */
    private fun hookConsentBypass(param: HookRuntime) {
        val classLoader = loader ?: return
        try {
            val nativeInterface = Reflect.findClass(
                "com.android.bluetooth.btservice.AdapterNativeInterface", classLoader
            )
            with(param) {
                nativeInterface.resolve().optional(silent = true).method { name = "init" }
                    .hookAll {
                        before {
                            val sb = StringBuilder("consent: AdapterNativeInterface#init")
                            args.forEachIndexed { i, arg ->
                                sb.append("\n    arg[").append(i).append("]=").append(describeValue(arg))
                            }
                            Logs.trace(sb.toString())
                        }
                    }
            }
            Logs.trace("consent: hooked AdapterNativeInterface#init")
        } catch (t: Throwable) {
            Logs.trace("consent: cannot hook AdapterNativeInterface#init", t)
        }
    }

    private fun describeValue(value: Any?): String = when (value) {
        null -> "<null>"
        is Array<*> -> Arrays.toString(value)
        is ByteArray -> Arrays.toString(value)
        else -> value.javaClass.simpleName + ":" + value
    }

    private fun scheduleConfirm(device: BluetoothDevice, why: String) {
        if (confirmAttempted) return
        confirmAttempted = true
        autoConfirmArmed = false
        // Off the handler thread: this thread is also the one that services the bond
        // state machine, so it must not be parked in a sleep.
        Thread({
            try {
                Thread.sleep(CONFIRM_DELAY_MS)
            } catch (interrupted: InterruptedException) {
                return@Thread
            }
            console("auto-confirm ($why) -> ${confirmPairing(device)}")
        }, "az100-confirm").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Belt and braces: if `sendPairingRequestIntent` is never reached -- because a
     * vendor branch returned early, or because the consent came from a path that does
     * not build an intent -- the device still sits in `BOND_BONDING` waiting for an
     * answer.  One late answer is enough.
     */
    private fun armConfirmWatchdog(device: BluetoothDevice) {
        Thread({
            try {
                Thread.sleep(WATCHDOG_DELAY_MS)
            } catch (interrupted: InterruptedException) {
                return@Thread
            }
            if (confirmAttempted) return@Thread
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDING) return@Thread
            } catch (t: Throwable) {
                return@Thread
            }
            confirmAttempted = true
            autoConfirmArmed = false
            console("auto-confirm (watchdog) -> ${confirmPairing(device)}")
        }, "az100-confirm-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The reply arguments are not documented anywhere reachable from the app side, and
     * the decompiled JNI only shows that both integers are forwarded verbatim:
     *
     * ```
     * 46e0b4  mov w21, w3        ; transport  = arg3
     * 46e0b0  mov w20, w4        ; accept     = arg4
     * 46e0ac  mov w19, w5        ; variant    = arg5
     * ...
     * 46e124  mov w1, w21
     * 46e128  mov w2, w20
     * 46e12c  mov w3, w19
     * 46e138  blr x8             ; *(sBtInterface + 0x88)(addr, w1, w2, w3)
     * ```
     *
     * so instead of guessing one pair, `sweep` walks all of them inside a single
     * pairing window.  A reply that does not match anything is harmless:
     * `btm_confirm_req_reply` logs `Unexpected pairing confirm` and returns, leaving
     * SMP parked in `SMP_STATE_WAIT_APP_RSP` for the next attempt.  That was measured,
     * not assumed.
     */
    private val SWEEP = arrayOf(
        intArrayOf(2, 0), intArrayOf(0, 2), intArrayOf(2, 2), intArrayOf(1, 2),
        intArrayOf(2, 1), intArrayOf(2, 3), intArrayOf(0, 0), intArrayOf(1, 0),
        intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(0, 3), intArrayOf(1, 3),
    )

    private const val SWEEP_STEP_MS = 1800L

    private fun sweepBond(device: BluetoothDevice, transport: Int) {
        // Take the automatic paths out of the race; the sweep drives every reply.
        autoConfirmArmed = false
        confirmAttempted = true
        Thread({
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.createBond(transport)
            } else {
                device.createBond()
            }
            console("sweep: createBond($transport)=$started")
            for (candidate in SWEEP) {
                // Sleep first: right after createBond() the state machine has not moved
                // off BOND_NONE yet, so checking first would abort before a single reply
                // is sent.
                try {
                    Thread.sleep(SWEEP_STEP_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                val state = try {
                    device.bondState
                } catch (t: Throwable) {
                    console("sweep: state read failed $t")
                    return@Thread
                }
                if (state == BluetoothDevice.BOND_BONDED) {
                    console(
                        "sweep: BONDED after transport=$confirmTransport"
                                + " variant=$confirmVariant"
                    )
                    return@Thread
                }
                if (state != BluetoothDevice.BOND_BONDING) {
                    console(
                        "sweep: not bonding (state=$state) before transport="
                                + candidate[0] + " variant=" + candidate[1]
                    )
                    return@Thread
                }
                confirmTransport = candidate[0]
                confirmVariant = candidate[1]
                console(
                    "sweep try transport=${candidate[0]} variant=${candidate[1]}"
                            + " -> ${confirmPairing(device)}"
                )
            }
            console("sweep: exhausted, bondState=${device.bondState}")
        }, "az100-sweep").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The exact answer the dialog's "pair" button gives.
     *
     * `AdapterServiceBinder#setPairingConfirmation(device, accept, source)` is a
     * permission check followed by
     * `service.getNative().sspReply(Util.getBytesFromAddress(addr), 0, accept, 0)`.
     * Calling the native interface directly keeps the whole thing in-process -- no
     * `AttributionSource` to forge and no binder hop -- and drops the `isBonding()`
     * guard, which is safe here because the only caller is the pairing request itself.
     */
    private fun confirmPairing(device: BluetoothDevice): String {
        val service = adapterService
        val address = addressOf(device)
        val raw = bytesOf(address)
        val transport = confirmTransport
        val variant = confirmVariant
        if (service != null && raw != null) {
            try {
                val nativeInterface = Reflect.callMethod(service, "getNative")
                if (nativeInterface != null) {
                    val result = Reflect.callMethod(
                        nativeInterface, "sspReply", raw, transport, true, variant
                    )
                    return "sspReply(${mask(address)}, transport=$transport," +
                            " variant=$variant)=$result"
                }
            } catch (t: Throwable) {
                Logs.trace("routeB: sspReply failed", t)
            }
        }
        // The public-hidden equivalent, in case the native interface is not reachable
        // from where this happens to run.
        return try {
            val method = BluetoothDevice::class.java.getMethod(
                "setPairingConfirmation", Boolean::class.javaPrimitiveType
            )
            "setPairingConfirmation(${mask(address)})=${method.invoke(device, true)}"
        } catch (t: Throwable) {
            "ERROR $t"
        }
    }

    /** `Utils.getBytesFromAddress` without depending on the vendor build. */
    private fun bytesOf(address: String?): ByteArray? {
        if (address == null) return null
        val parts = address.split(":")
        if (parts.size != 6) return null
        val raw = ByteArray(6)
        return try {
            for (i in 0 until 6) {
                raw[i] = Integer.parseInt(parts[i], 16).toByte()
            }
            raw
        } catch (malformed: NumberFormatException) {
            null
        }
    }

    /* ------------------------------------------------------------------ */
    /* diagnostics: where do the UUIDs actually come from                  */
    /* ------------------------------------------------------------------ */

    /**
     * All four UUID arrays are written from exactly one place --
     * `RemoteDevices#devicePropertyChangedCallback`, the JNI callback that delivers
     * `BT_PROPERTY_UUIDS`, `BT_PROPERTY_UUIDS_LE`,
     * `BT_PROPERTY_UUIDS_FROM_EXTENDED_INQUIRY_RESPONSE` and
     * `BT_PROPERTY_UUIDS_FROM_LE_ADVERTISING_DATA`.  Watching the four setters plus
     * `sendUuidsInternal` answers "did `0x184E` ever reach the profile manager, and by
     * which route" without guessing.
     */
    private fun hookUuidPipeline(param: HookRuntime) {
        val classLoader = loader ?: return
        var properties: Class<*>? = null
        try {
            properties = Reflect.findClass(DEVICE_PROPERTIES, classLoader)
        } catch (t: Throwable) {
            Logs.trace("routeB: DeviceProperties not found", t)
        }
        if (properties != null) {
            if (diagnose()) {
                for (setter in arrayOf(
                    "setUuidsBrEdr",
                    "setUuidsLe",
                    "setUuidsFromExtendedInquiryResponse",
                    "setUuidsFromLeAdvertisingData"
                )) {
                    try {
                        with(param) {
                            properties.resolve().optional(silent = true).method { name = setter }
                                .hookAll {
                                    after { dumpUuids(instanceOrNull, setter) }
                                }
                        }
                    } catch (t: Throwable) {
                        Logs.trace("routeB: cannot hook $setter", t)
                    }
                }
            }
            if (SPLICE_LE_AUDIO_UUIDS) spliceLeAudioUuid(param, properties)
        }
        if (!diagnose()) return

        try {
            val service = Reflect.findClass(ADAPTER_SERVICE, classLoader)
            with(param) {
                service.resolve().optional(silent = true).method { name = "sendUuidsInternal" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.size < 2) return@before
                            val address = (args[0] as? BluetoothDevice)?.let { addressOf(it) }
                            val uuids = args[1]
                            val count = if (uuids == null) {
                                "null"
                            } else {
                                java.lang.reflect.Array.getLength(uuids).toString()
                            }
                            Logs.trace("routeB: sendUuidsInternal ${mask(address)} count=$count")
                        }
                    }
            }
            Logs.trace("routeB: hooked AdapterService#sendUuidsInternal")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook sendUuidsInternal", t)
        }

        try {
            val remoteDevices = Reflect.findClass(REMOTE_DEVICES, classLoader)
            with(param) {
                remoteDevices.resolve().optional(silent = true).method { name = "devicePropertyChangedCallback" }
                    .hookAll {
                        before {
                            // This callback fires for every device the stack has ever seen,
                            // not just ours.  Measured over 15.7 h of idle time: 1166 calls,
                            // of which 1140 (97.8%) were 222 unrelated addresses -- and during
                            // an active scan it bursts to ~250 calls per 10 minutes.  Building
                            // the message first made that the single largest writer in the
                            // Bluetooth process.
                            //
                            // args[0] is the raw 6-byte address, and the whole point of the
                            // trace is the AZ100's UUID pipeline, so gate on it before
                            // formatting anything.
                            if (!isAz100RawAddress(args)) return@before
                            Logs.trace(
                                "routeB: devicePropertyChangedCallback ${describeArgs(args)}"
                            )
                        }
                    }
            }
            Logs.trace("routeB: hooked RemoteDevices#devicePropertyChangedCallback")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook devicePropertyChangedCallback", t)
        }
    }

    /**
     * `devicePropertyChangedCallback(byte[] address, ...)` -- does the raw address
     * belong to the headset?
     *
     * Fails *closed* on anything unexpected (no args, a different first argument type,
     * a short array): the only cost of dropping a line is a gap in the diagnostic
     * trace, whereas the cost of letting everything through is the noise this gate
     * exists to remove.
     */
    private fun isAz100RawAddress(args: Array<Any?>?): Boolean {
        if (args == null || args.isEmpty()) return false
        val address = args[0] as? ByteArray ?: return false
        if (address.size < 6) return false
        // Native sends the address in reverse (little-endian) order; compare the last
        // six bytes either way round so the gate does not depend on that.
        val base = address.size - 6
        val forward = StringBuilder(17)
        val reverse = StringBuilder(17)
        for (i in 0 until 6) {
            appendHexByte(forward, address[base + i])
            appendHexByte(reverse, address[address.size - 1 - i])
        }
        return matchesAddress(forward.toString()) || matchesAddress(reverse.toString())
    }

    private fun appendHexByte(out: StringBuilder, value: Byte) {
        if (out.isNotEmpty()) out.append(':')
        val v = value.toInt() and 0xFF
        out.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0xF, 16))
    }

    /**
     * The volume half of the vendor filter.
     *
     * `VolumeControlService#connect` fails silently from the outside: it logs
     * `Remote does not have Volume Control UUID` and returns `false`, and nothing
     * downstream distinguishes that from "the headset refused".
     *
     * ## Why this is two methods
     *
     * [hookVolumeBehavior] installs everything that changes behaviour and is therefore
     * unconditional; [hookVolumeDiagnostics] installs the read-only logging and is
     * gated on `diagnose()`.  Before the split the whole set sat behind the diagnostic
     * switch, which meant turning the diagnostic switch off also disabled the volume
     * fix -- measured 2026-10-13, see [hookVolumeBehavior].
     */
    private fun hookVolumeBehavior(param: HookRuntime) {
        val classLoader = loader ?: return
        val service = try {
            Reflect.findClass(VOLUME_CONTROL_SERVICE, classLoader)
        } catch (t: Throwable) {
            Logs.trace("vcs: VolumeControlService not found", t)
            return
        }
        try {
            with(param) {
                service.resolve().optional(silent = true).method { name = "connect" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.isEmpty()) return@before
                            val device = args[0] as? BluetoothDevice ?: return@before
                            Logs.trace(
                                "vcs: connect(${mask(addressOf(device))}) uuids=${uuidsOf(device)}"
                            )
                            allowVcp(instanceOrNull, device, "connect")
                        }
                        after {
                            val args = args
                            if (args.isEmpty()) return@after
                            val address = (args[0] as? BluetoothDevice)?.let { addressOf(it) }
                            Logs.trace("vcs: connect ${mask(address)} -> $result")
                        }
                    }
            }
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot hook connect", t)
        }
        try {
            with(param) {
                service.resolve().optional(silent = true).method { name = "setConnectionPolicy" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.size < 2) return@before
                            val device = args[0] as? BluetoothDevice ?: return@before
                            val policy = args[1] as? Int ?: return@before
                            if (policy != CONNECTION_POLICY_FORBIDDEN) return@before
                            if (!matches(device)) return@before
                            if (!leAudioAllowed(instanceOrNull, device)) {
                                Logs.trace(
                                    "vcs: policy FORBIDDEN kept for ${mask(addressOf(device))}"
                                            + " (LE Audio not allowed)"
                                )
                                return@before
                            }
                            args[1] = CONNECTION_POLICY_ALLOWED
                            Logs.trace(
                                "vcs: policy FORBIDDEN -> ALLOWED for ${mask(addressOf(device))}"
                            )
                        }
                    }
            }
            Logs.trace("vcs: connection-policy guard armed")
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot hook setConnectionPolicy", t)
        }
        try {
            with(param) {
                service.resolve().optional(silent = true).method { name = "handleVolumeControlChanged" }
                    .hookAll {
                        before {
                            val args = args
                            // (BluetoothDevice, groupId, volume, flags, mute, autonomous).
                            if (args.size < 6) return@before
                            val groupId = args[1] as? Int ?: return@before
                            val volume = args[2] as? Int ?: return@before
                            captureVolumeReport(args[0], groupId, volume, args[5] == true)
                            if (lowerVolumeFloor() && preserveLowAutonomousReport(
                                    instanceOrNull, args
                                )
                            ) {
                                result = null
                            }
                        }
                    }
                service.resolve().optional(silent = true).method { name = "connectionStateChanged" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.size < 3) return@before
                            val device = args[0] as? BluetoothDevice ?: return@before
                            val toState = args[2] as? Int ?: return@before
                            // Signature is (device, fromState, toState).
                            if (toState == 2) forgetVolume(device)
                        }
                    }
            }
            Logs.trace("vcs: volume-report capture and member lifecycle armed")
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot hook handleVolumeControlChanged", t)
        }
    }

    /**
     * The read-only half of the volume hooks.  Safe to drop when diagnosing is off:
     * nothing here feeds a later decision, it only records what the stack was about to
     * push.
     */
    private fun hookVolumeDiagnostics(param: HookRuntime) {
        val classLoader = loader ?: return
        val service = try {
            Reflect.findClass(VOLUME_CONTROL_SERVICE, classLoader)
        } catch (t: Throwable) {
            Logs.trace("vcs: VolumeControlService not found", t)
            return
        }
        try {
            with(param) {
                service.resolve().optional(silent = true).method { name = "setDeviceVolume" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.size < 2) return@before
                            val address = (args[0] as? BluetoothDevice)?.let { addressOf(it) }
                            Logs.trace("vcs: setDeviceVolume ${mask(address)} = ${args[1]}")
                        }
                    }
            }
            Logs.trace("vcs: volume control diagnostics armed")
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot hook setDeviceVolume", t)
        }
    }

    /**
     * Drop VCS writes that would not change the headset's volume.
     *
     * Why, as measured on this phone:
     *
     * ```
     * OnLeAudioGroupStreamStarted: group_id: 1 pending volume resync on next SetVolume
     * SetVolume: group_id: 1 cleared LE Audio volume resync flag (scheduling 2 device(s))
     * PrepareVolumeControlOperation: num of devices: 2, group_id: 1 ... opcode: 4
     * ControlPointOperation: ..84:85 (*arg)[0]=128 volume=128 opcode=0x04 change_counter=0xb5
     * ControlPointOperation: ..3c:62 (*arg)[0]=128 volume=128 opcode=0x04 change_counter=0x05
     * OnWriteControlResponse: Write response handle: 0x1308 status: 0x0
     * OnWriteControlResponse: Write response handle: 0x1308 status: 0x0
     * OperationMonitorTimeoutFired: Operation 2 is taking too long for devices:  (every 3s, forever)
     * ```
     *
     * Both ATT writes returned success, but no matching Volume State update was
     * observed.  The evidence points to the VCP operation queue waiting for completion
     * of a same-value resync, not an unresponsive ATT transport.  Operation 2 is a queue
     * id (the opcode here is 0x04).  In that log window, 136 matching SetVolume log
     * lines followed only 4 ControlPointOperation lines; these are log counts, not
     * necessarily API-call counts.  The complete vendor-native completion logic has not
     * been audited.
     *
     * The redundant write is not an accident.  The native `volumeNotChanged` guard at
     * `vcp_controller.cc:1352` would have suppressed it, but
     * `OnLeAudioGroupStreamStarted` sets a pending-resync flag that bypasses that guard,
     * and `LeAudioService#messageFromNative` feeds it on every stream start
     * ("music/game streaming", "voip/call streaming") through
     * `VolumeControlService#restoreGrouVPolume`, which calls
     * `VolumeControlNativeInterface#setGroupVolume` directly rather than going through
     * `setDeviceVolume`.
     *
     * So the guard sits on the last Java boundary before native, where it catches both
     * entry paths, and drops only writes that would be no-ops.
     * `restoreGrouVPolume`'s Java-side cache updates and AudioManager side effects still
     * run; only the redundant GATT write disappears.
     */
    private fun hookVolumeWriteGuard(param: HookRuntime) {
        val classLoader = loader ?: return
        val iface = try {
            Reflect.findClass(VOLUME_CONTROL_NATIVE_INTERFACE, classLoader)
        } catch (t: Throwable) {
            Logs.trace("vcs: VolumeControlNativeInterface not found", t)
            return
        }
        try {
            with(param) {
                iface.resolve().optional(silent = true).method { name = "setGroupVolume" }
                    .hookAll {
                        before { guardVolumeWrite(this) }
                        after { rememberStockPush(this) }
                    }
                iface.resolve().optional(silent = true).method { name = "setVolume" }
                    .hookAll {
                        before { guardVolumeWrite(this) }
                        after { rememberStockPush(this) }
                    }
                // Each member has its own history.  Removing one must leave its peer
                // intact.
                iface.resolve().optional(silent = true)
                    .method { name = "connectVolumeControl" }
                    .hookAll {
                        before {
                            val device = args.firstOrNull() as? BluetoothDevice ?: return@before
                            forgetVolume(device)
                        }
                    }
                iface.resolve().optional(silent = true)
                    .method { name = "disconnectVolumeControl" }
                    .hookAll {
                        before {
                            val device = args.firstOrNull() as? BluetoothDevice ?: return@before
                            forgetVolume(device)
                        }
                    }
            }
            Logs.trace("vcs: redundant-write guard armed")
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot hook volume writes", t)
        }
    }

    /**
     * What the `before` half of [hookVolumeWriteGuard] leaves for its `after` half.
     *
     * The old code used `MethodHookParam.setObjectExtra`, whose storage lives on the
     * `MethodHookParam` instance Xposed keeps for the duration of one call. Yuki's
     * `dataExtra` cannot stand in: `before` and `after` get *different* `paramId`s
     * (`beforeHookId` / `afterHookId`), so it would read back an empty bundle and
     * silently stop recording successful pushes.
     *
     * `before` and `after` for one call run on the same thread -- the hooked method's
     * caller -- so a `ThreadLocal` is an exact replacement: set in `before`, read and
     * cleared in `after`, and a `before` that took an early return leaves `null` for
     * the `after` to ignore.
     */
    private class PendingPush(val devices: List<BluetoothDevice>?, val stock: Int)

    private val pendingPush = ThreadLocal<PendingPush?>()

    private fun guardVolumeWrite(param: HookCall) {
        // Registered unconditionally; the switch is read here so that turning the
        // volume fix off stops it immediately instead of at the next process
        // start.  Returning without setting `result` leaves the original call
        // untouched, which is exactly the unfixed behaviour.
        if (!suppressRedundantVcsWrites()) return
        val args = param.args
        if (args.size < 2) return
        val wanted = args[1] as? Int ?: return
        val key = keyOf(args[0]) ?: return
        if (wanted < 0 || wanted > 255) return
        val stock = clampLeAudioVolume(wanted)
        // 1..15 would be raised to 16 by the method body itself, so those have
        // to go straight to native to survive.
        val volume = if (lowerVolumeFloor() && wanted > 0
            && wanted < LEAUDIO_VOLUME_MIN
        ) {
            wanted
        } else {
            stock
        }
        val last = knownVolume(key)
        if (isNoOp(key, volume)) {
            param.result = null
            Logs.trace(
                "vcs: dropped redundant write ${maskKey(key)} = $volume"
                        + " (unchanged, known $last)"
            )
            return
        }
        Logs.trace(
            "vcs: write ${maskKey(key)} = $volume"
                    + (if (volume != stock) {
                        " (floor bypass, stock would send $stock)"
                    } else {
                        ""
                    })
                    + (if (last == null) " (first)" else " (was $last)")
        )
        val members = volumeMembers(key)
        if (volume != stock && writeNative(param.instanceOrNull, args[0], volume)) {
            rememberPushed(members, volume)
            param.result = null
            return
        }
        // Record only after the original returned without an exception.  A
        // failed bypass will actually send stock=16, never wanted=1..15.
        pendingPush.set(PendingPush(members, stock))
    }

    private fun rememberStockPush(param: HookCall) {
        val pending = pendingPush.get() ?: return
        pendingPush.remove()
        if (param.hasThrowable == true) return
        val devices = pending.devices ?: return
        rememberPushed(devices, pending.stock)
    }

    /**
     * The best estimate of the headset's current volume for `key`, or `null` when
     * nothing is known yet.  What the headset reported wins over what we last pushed,
     * because a reconnect leaves the headset holding its old value while our push
     * history has been forgotten -- and it is exactly then that the connect-time push
     * turns into a no-op.
     */
    private fun knownVolume(key: String): Int? {
        val members = volumeMembers(key)
        if (members.isNullOrEmpty()) return null
        var consensus: Int? = null
        for (device in members) {
            val address = addressOf(device)
            var value = LAST_PUSHED["d:$address"]
            if (value == null) value = LAST_REPORTED[address]
            if (value == null) return null
            if (consensus != null && consensus != value) return null
            consensus = value
        }
        return consensus
    }

    /**
     * Require consensus among currently VCP-connected AZ100 members only.  A newer
     * push supersedes an older report; an autonomous report supersedes the push.  This
     * avoids stale A suppressing a later A after A -> B -> A.  Native setters return
     * void: a returned call is submission evidence only, not proof of ATT success.
     * Failure/timeout recovery remains a device test.
     */
    private fun isNoOp(key: String, volume: Int): Boolean = knownVolume(key) == volume

    /**
     * Call the private native setter directly, skipping the wrapper methods whose
     * bodies would clamp the value back up to [LEAUDIO_VOLUME_MIN].
     */
    private fun writeNative(iface: Any?, target: Any?, volume: Int): Boolean {
        val classLoader = loader ?: return false
        return try {
            if (target is Int) {
                Reflect.callMethod(iface, "setGroupVolumeNative", target, volume)
            } else if (target is BluetoothDevice) {
                val address = Reflect.callStaticMethod(
                    Reflect.findClass(VOLUME_CONTROL_NATIVE_INTERFACE, classLoader),
                    "getByteAddress", target
                )
                Reflect.callMethod(iface, "setVolumeNative", address, volume)
            } else {
                return false
            }
            Logs.trace("vcs: $volume written straight to native (clamp bypassed)")
            true
        } catch (t: Throwable) {
            Logs.trace("vcs: native bypass failed", t)
            false
        }
    }

    /**
     * Stock autonomous handling rounds VCS 1..15 to zero, including the first
     * single-ear connect.  For AZ100 media only, use the existing cache/audio update
     * helper with the unmodified low value, then notify normal clients.  No native
     * volume write is manufactured here; mute remains independent.
     */
    private fun preserveLowAutonomousReport(vcs: Any?, args: Array<Any?>): Boolean {
        if (args[5] != true) return false
        val volume = args[2] as? Int ?: return false
        if (volume < 1 || volume >= LEAUDIO_VOLUME_MIN) return false
        return try {
            var groupId = args[1] as? Int ?: -1
            if (groupId < 0) {
                val device = args[0] as? BluetoothDevice ?: return false
                groupId = Reflect.callMethod(vcs, "getGroupId", device) as Int
            }
            if (!az100Group(groupId)) return false
            val stream = Reflect.callMethod(
                vcs, "getBluetoothContextualVolumeStreamFromAudio"
            ) as? Int ?: return false
            if (stream != 3) return false
            val status = Reflect.getObjectField(vcs, "mGroupStatus") as? Map<*, *>
                ?: return false
            if (!status.containsKey(groupId) || status[groupId] == 0) return false
            val device = args[0] as? BluetoothDevice
            if (device != null) {
                val flags = args[3] as? Int ?: 0
                val connected = Reflect.callMethod(vcs, "getConnectedDevices", groupId)
                if ((flags and 1) == 0 || connected !is List<*> || connected.size != 1) return false
            }
            // Resolve methods before any side effects: unsupported firmware fails open.
            val update = Reflect.findMethodExact(
                vcs!!.javaClass,
                "updateCacheByAutonomousChange",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            val notify = Reflect.findMethodExact(
                vcs.javaClass,
                "notifyDevicesVolumeChanged",
                android.os.RemoteCallbackList::class.java, List::class.java, Optional::class.java
            )
            val callbacks = Reflect.getObjectField(vcs, "mCallbacks")
            val devices = device?.let { listOf(it) }
                ?: Reflect.callMethod(vcs, "getGroupDevices", groupId)
            update.invoke(vcs, groupId, volume, args[4] == true, false)
            if (callbacks != null) {
                synchronized(callbacks) {
                    notify.invoke(vcs, callbacks, devices, Optional.of(volume))
                }
            }
            Logs.trace("vcs: preserved low autonomous report g:$groupId = $volume")
            true
        } catch (t: Throwable) {
            Logs.trace("vcs: low-report preservation failed; stock path retained", t)
            false
        }
    }

    private fun profileService(getter: String): Any? = try {
        val service = adapterService ?: return null
        val result = Reflect.callMethod(service, getter)
        if (result is Optional<*>) result.orElse(null) else null
    } catch (t: Throwable) {
        // Service startup/shutdown: leave the stock path alone.
        null
    }

    /** Fail open unless every known member of this group is one of our two AZ100s. */
    private fun az100Group(groupId: Int): Boolean {
        if (groupId < 0) return false
        val lea = profileService("getLeAudioService") ?: return false
        return try {
            val result = Reflect.callMethod(lea, "getGroupDevices", groupId)
            if (result !is List<*> || result.isEmpty()) return false
            for (item in result) {
                if (item !is BluetoothDevice) return false
                if (!matchesAddress(addressOf(item))) return false
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Only VCP-connected members are relevant; an in-case peer is not a vote. */
    private fun volumeMembers(key: String): List<BluetoothDevice>? {
        val vcs = profileService("getVolumeControlService") ?: return null
        val lea = profileService("getLeAudioService") ?: return null
        return try {
            val out = ArrayList<BluetoothDevice>()
            if (key.startsWith("d:")) {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
                val device = adapter.getRemoteDevice(key.substring(2))
                if (Reflect.callMethod(vcs, "getConnectionState", device) as Int == 2) {
                    out.add(device)
                }
                return out
            }
            val groupId = Integer.parseInt(key.substring(2))
            val result = Reflect.callMethod(vcs, "getConnectedDevices")
            if (result !is List<*>) return null
            for (item in result) {
                if (item !is BluetoothDevice) continue
                if (matchesAddress(addressOf(item))
                    && Reflect.callMethod(lea, "getGroupId", item) as Int == groupId
                ) {
                    out.add(item)
                }
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    private fun rememberPushed(members: List<BluetoothDevice>?, volume: Int) {
        if (members == null) return
        for (device in members) {
            val address = addressOf(device)
            LAST_PUSHED["d:$address"] = volume
            // A previous report is no longer evidence for this member after a new push.
            LAST_REPORTED.remove(address)
        }
    }

    private fun forgetVolume(device: BluetoothDevice) {
        val address = addressOf(device)
        if (!matchesAddress(address)) return
        LAST_PUSHED.remove("d:$address")
        LAST_REPORTED.remove(address)
        Logs.trace("vcs: forgetting member ${mask(address)} only")
    }

    private fun captureVolumeReport(target: Any?, groupId: Int, volume: Int, autonomous: Boolean) {
        if (volume < 0 || volume > 255) return
        val members: List<BluetoothDevice> = when {
            target is BluetoothDevice -> {
                if (!matchesAddress(addressOf(target))) return
                listOf(target)
            }

            target == null && az100Group(groupId) -> volumeMembers("g:$groupId") ?: return
            else -> return
        }
        for (device in members) {
            val address = addressOf(device)
            val key = "d:$address"
            val pushed = LAST_PUSHED[key]
            if (autonomous || (pushed != null && pushed == volume)) {
                LAST_PUSHED.remove(key)
            }
            // A late response to an older request must not beat a newer push.
            if (!autonomous && pushed != null && pushed != volume) continue
            if (address != null) LAST_REPORTED[address] = volume
            Logs.trace(
                "vcs: headset reports ${mask(address)} = $volume"
                        + " (group $groupId, autonomous=$autonomous)"
            )
        }
    }

    /** Never apply the floor or write guard to other Bluetooth devices. */
    private fun keyOf(target: Any?): String? = when {
        target is Int && az100Group(target) -> "g:$target"
        target is BluetoothDevice -> {
            val address = addressOf(target)
            if (matchesAddress(address)) "d:$address" else null
        }

        else -> null
    }

    /**
     * Redact the address inside a [keyOf] key (`d:MAC` / `g:N`).  The group form
     * carries no address and is passed through unchanged.
     */
    private fun maskKey(key: String?): String {
        if (key == null) return "?"
        if (key.startsWith("d:")) return "d:" + mask(key.substring(2))
        return key
    }

    /** Mirrors `VolumeControlNativeInterface`'s own clamp. */
    private fun clampLeAudioVolume(volume: Int): Int =
        if (volume != 0 && volume < LEAUDIO_VOLUME_MIN) LEAUDIO_VOLUME_MIN else volume

    /**
     * `ConnectableProfile#okToConnect` accepts only `-1` or `100`:
     *
     * ```
     * if (getBondState(device) != BOND_BONDED)  { log "invalid bond state";      return false; }
     * int policy = getConnectionPolicy(device);
     * if (policy != -1 && policy != 100)        { log "invalid connection policy"; return false; }
     * return true;
     * ```
     *
     * `LeAudioService#setLeAudioGattClientProfilesPolicy` is the call that would
     * normally leave VCP at `ALLOWED` -- but it is itself gated on
     * `Util.arrayContains(getRemoteUuids(device), VOLUME_CONTROL)`, the same vendor
     * filter, so it silently does nothing when `0x1844` is missing.  On top of that
     * `OplusLeAdapterService#csisDeviceAvailable` propagates a `FORBIDDEN` from one set
     * member to the other:
     *
     * ```
     * if (vcs.getConnectionPolicy(peer) == 0 && csip.getConnectionState(peer) == 2)
     *     vcs.setConnectionPolicy(device, 0);
     * ```
     *
     * Measured on this phone: both AZ100 identities sit at `vcp: FORBIDDEN` on disk
     * (`VOLUME_CONTROL policy changed: -1 -> 0`), so the UUID splice alone is defeated
     * one layer up.  This restores the policy at the moment a connection is actually
     * wanted.
     */
    private fun allowVcp(vcsService: Any?, device: BluetoothDevice, trigger: String) {
        if (!matches(device)) return
        try {
            val adapter = Reflect.callMethod(vcsService, "getAdapterService")
            val profileId = Reflect.callMethod(vcsService, "getProfileId") as Int
            val policy = Reflect.callMethod(
                adapter, "getProfileConnectionPolicy", device, profileId
            ) as Int
            if (policy == CONNECTION_POLICY_ALLOWED) {
                Logs.trace(
                    "vcs: policy already ALLOWED for ${mask(addressOf(device))} ($trigger)"
                )
                return
            }
            Reflect.callMethod(
                adapter, "setProfileConnectionPolicy", device, profileId,
                CONNECTION_POLICY_ALLOWED
            )
            Logs.trace(
                "vcs: policy $policy -> ALLOWED for ${mask(addressOf(device))} ($trigger)"
            )
        } catch (t: Throwable) {
            Logs.trace("vcs: cannot normalise policy for ${mask(addressOf(device))}", t)
        }
    }

    /**
     * Only override a vendor `FORBIDDEN` while LE Audio is switched on for the device
     * -- that is the state in which VCP is supposed to be reachable.  With LE Audio off
     * the vendor's `FORBIDDEN` is left alone.
     */
    private fun leAudioAllowed(vcsService: Any?, device: BluetoothDevice): Boolean {
        return try {
        val adapter = Reflect.callMethod(vcsService, "getAdapterService")
        val optional = Reflect.callMethod(adapter, "getLeAudioService")
        if (optional !is Optional<*>) return false
        val service = optional.orElse(null) ?: return false
        val policy = Reflect.callMethod(service, "getConnectionPolicy", device) as Int
        policy != CONNECTION_POLICY_FORBIDDEN
    } catch (t: Throwable) {
        false
    }
    }

    /**
     * `BluetoothDevice#getUuids()` is the array `connect` tests, so printing it here is
     * the direct check on the splice -- and unlike the `setUuidsLe` trace it is read at
     * the moment of the decision.
     */
    private fun uuidsOf(device: BluetoothDevice): String = try {
        val uuids = device.uuids
        if (uuids == null) "null" else Arrays.toString(uuids)
    } catch (t: Throwable) {
        "ERROR $t"
    }

    private fun dumpUuids(properties: Any?, trigger: String) {
        val sb = StringBuilder("routeB: ").append(trigger)
        for (field in UUID_FIELDS) {
            sb.append("\n    ").append(field).append(" = ").append(readUuids(properties, field))
        }
        // Must be trace(), not Log.i: this runs on the binder thread that services
        // sendUuidsInternal, and logcat never sees it from there.  The file channel is
        // the only one that always lands.
        Logs.trace(sb.toString())
    }

    private fun readUuids(properties: Any?, field: String): String = try {
        val value = Reflect.getObjectField(properties, field)
        if (value == null) {
            "null"
        } else {
            val length = java.lang.reflect.Array.getLength(value)
            val sb = StringBuilder().append(length).append(" [")
            for (i in 0 until length) {
                if (i > 0) sb.append(' ')
                sb.append(java.lang.reflect.Array.get(value, i))
            }
            sb.append(']').toString()
        }
    } catch (t: Throwable) {
        "<$t>"
    }

    /** `devicePropertyChangedCallback(byte[] address, int[] types, byte[][] values)`. */
    private fun describeArgs(args: Array<Any?>?): String {
        if (args == null) return "null"
        val sb = StringBuilder()
        args.forEachIndexed { i, arg ->
            if (i > 0) sb.append(", ")
            when (arg) {
                is ByteArray -> sb.append("addr=").append(hex(arg))
                is IntArray -> sb.append("types=").append(Arrays.toString(arg))
                is Array<*> -> sb.append("values=").append(uuidLists(arg))
                else -> sb.append(arg)
            }
        }
        return sb.toString()
    }

    private fun uuidLists(values: Array<*>): String {
        val sb = StringBuilder("[")
        values.forEachIndexed { i, value ->
            if (i > 0) sb.append(", ")
            sb.append(if (value is ByteArray) uuidList(value) else value.toString())
        }
        return sb.append(']').toString()
    }

    /** Native sends a UUID list as 16 raw bytes per entry. */
    private fun uuidList(raw: ByteArray?): String {
        if (raw == null) return "null"
        if (raw.isEmpty()) return "0"
        if (raw.size % 16 != 0) return "${raw.size}B"
        val sb = StringBuilder().append(raw.size / 16).append(" [")
        var i = 0
        while (i < raw.size) {
            if (i > 0) sb.append(' ')
            sb.append(uuid(raw, i))
            i += 16
        }
        return sb.append(']').toString()
    }

    private fun uuid(raw: ByteArray, offset: Int): String {
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val b = raw[offset + i].toInt() and 0xFF
            sb.append(Character.forDigit(b shr 4, 16)).append(Character.forDigit(b and 0xF, 16))
        }
        return sb.toString()
    }

    /**
     * A raw address, redacted the same way [mask] redacts a string one.
     *
     * Addresses are device-specific data, so only the tail is logged -- the
     * previous version printed all six octets despite this comment claiming
     * otherwise, which put a full Bluetooth address into the trace file on every
     * `devicePropertyChangedCallback`.
     *
     * Which two octets survive depends on the wire order, which this file cannot
     * pin down -- [isAz100RawAddress] deliberately accepts either direction --
     * so the last two are kept and four of six are always redacted.  That is the
     * property that matters here; it need not agree octet-for-octet with [mask].
     *
     * Anything that is not an address-sized array is reported by length instead:
     * this helper is only ever called on the address argument, but guessing at a
     * longer buffer would be worse than saying so.
     */
    private fun hex(raw: ByteArray?): String {
        if (raw == null) return "null"
        if (raw.size != 6) return "${raw.size}B"
        val tail = StringBuilder()
        for (i in 4 until 6) {
            if (tail.isNotEmpty()) tail.append(':')
            val b = raw[i].toInt() and 0xFF
            tail.append(Character.forDigit(b shr 4, 16)).append(Character.forDigit(b and 0xF, 16))
        }
        return "**:**:$tail"
    }

    private fun spliceLeAudioUuid(param: HookRuntime, properties: Class<*>) {
        try {
            with(param) {
                properties.resolve().optional(silent = true).method { name = "setUuidsLe" }
                    .hookAll {
                        before { spliceIntoLeUuids(this) }
                    }
            }
            Logs.trace("routeB: setUuidsLe splice armed")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook setUuidsLe", t)
        }
    }

    /**
     * Rewrites `setUuidsLe`'s argument before the method stores it.
     *
     * The write side is the only place that reaches every reader at once.
     * `DeviceProperties.toString()` -- which is what `dumpsys bluetooth_manager` prints
     * as `[LE UUIDs]` -- reads the raw field `mUuidsLe`, and `getUuids()` merges the
     * same field into what Settings and every `ACTION_UUID` consumer sees.  Patching
     * the getter instead would only have covered the second of those, and patching
     * `getUuids()` is in any case unreachable here: the vendor strips the UUID out of
     * the advertising data, so a gate on the advertising array can never open.
     *
     * This runs inside `devicePropertyChangedCallback`'s switch on
     * `BT_PROPERTY_UUIDS_LE` (0x1c), which fires both when the stack pushes a fresh
     * discovery and when it replays `ServiceLe` from `bt_config.conf` at start-up, so a
     * restart is enough to re-arm it.
     */
    private fun spliceIntoLeUuids(param: HookCall) {
        val args = param.args
        if (args.isEmpty()) return
        val incoming = args[0] as? Array<*> ?: return
        // Address first.  This hook runs for every device's mUuidsLe, and
        // missingLeAudio() scans the whole incoming array; checking ownership first
        // keeps that scan off every other device -- the same class of waste that made
        // devicePropertyChangedCallback the top log writer.
        val address = addressOfProperties(param.instanceOrNull)
        if (!matches(param.instanceOrNull, address)) return
        val missing = missingLeAudio(incoming) ?: return
        val component = incoming.javaClass.componentType ?: return
        @Suppress("UNCHECKED_CAST")
        val extended = java.lang.reflect.Array.newInstance(component, incoming.size + missing.size) as Array<Any?>
        incoming.copyInto(extended)
        missing.forEachIndexed { i, uuid ->
            extended[incoming.size + i] = ParcelUuid.fromString(uuid)
        }
        args[0] = extended
        Logs.trace(
            "routeB: spliced ${Arrays.toString(missing)} into mUuidsLe"
                    + " (${incoming.size} -> ${extended.size}) ${mask(address)}"
        )
    }

    /**
     * `RemoteDevices$DeviceProperties` carries no address argument, so it has to be
     * asked.  `getDevice()` is the normal route; `getIdentityAddress()` covers the
     * window before the device object is attached, which is exactly the window
     * start-up replay happens in.
     */
    private fun addressOfProperties(properties: Any?): String? {
        if (properties == null) return null
        try {
            val device = Reflect.callMethod(properties, "getDevice")
            if (device is BluetoothDevice) {
                device.address?.let { return it }
            }
        } catch (t: Throwable) {
            // Fall through to the identity address.
        }
        return try {
            val identity = Reflect.callMethod(properties, "getIdentityAddress")
            Reflect.callMethod(identity, "getAddress") as? String
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Which of [LE_AUDIO_UUIDS] the incoming array does not already carry, or `null`
     * when it carries all of them.
     *
     * Called on every `setUuidsLe`, so it stays allocation-free in the common case:
     * the scan short-circuits on the first hit per UUID and the result array is only
     * trimmed when something is actually missing.
     */
    private fun missingLeAudio(uuids: Array<*>): Array<String>? {
        var missing: Array<String?>? = null
        var count = 0
        for (wanted in LE_AUDIO_UUIDS) {
            var present = false
            for (value in uuids) {
                if (value != null && wanted.equals(value.toString(), ignoreCase = true)) {
                    present = true
                    break
                }
            }
            if (present) continue
            if (missing == null) missing = arrayOfNulls(LE_AUDIO_UUIDS.size)
            missing[count++] = wanted
        }
        val found = missing ?: return null
        @Suppress("UNCHECKED_CAST")
        return (if (count == found.size) found else found.copyOf(count)) as Array<String>
    }

    /* ------------------------------------------------------------------ */
    /* the adb-driven console                                              */
    /* ------------------------------------------------------------------ */

    private fun startConsole(param: HookRuntime) {
        val classLoader = loader ?: return
        try {
            with(param) {
                Reflect.findClass(ADAPTER_SERVICE, classLoader).resolve()
                    .firstMethod {
                        name = "onCreate"
                        emptyParameters()
                    }
                    .hook {
                        after {
                            adapterService = instanceOrNull
                            armConsole()
                        }
                    }
            }
            Logs.trace("routeB: console anchor hooked")
        } catch (t: Throwable) {
            Logs.trace("routeB: cannot hook AdapterService#onCreate", t)
        }
    }

    private fun armConsole() {
        // A plain daemon thread rather than a HandlerThread: this keeps the console
        // independent of any Looper in the Bluetooth process.  It also makes
        // synchronous binder calls back into AdapterService, which must not be serviced
        // on the waiting thread.
        //
        // The tick is adaptive.  Measured 2026-10-13: a fixed 2 s tick meant 43200
        // wakeups/day, each one a property read, a Settings.Global query and a stat() on
        // the action file, just to discover the user is not running an experiment.  The
        // property read is what arms the console, so it stays cheap (see
        // readFromProperty), and the rest of the probe is only paid while an action is
        // actually present.
        Thread({
            Thread.currentThread().setUncaughtExceptionHandler { _, e ->
                console("console thread died: $e")
            }
            console("console thread alive")
            while (true) {
                try {
                    consume()
                } catch (t: Throwable) {
                    console("console poll failed $t")
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
            }
        }, "az100-routeB").apply {
            isDaemon = true
            start()
        }
        console("console armed on property $PROPERTY_KEY")
        Logs.i("routeB: console armed on property $PROPERTY_KEY")
    }

    /** Console output: the file is the reliable channel, logcat is a bonus. */
    private fun console(message: String) {
        try {
            FileOutputStream(LOG_PATH, true).use { out ->
                out.write("${System.currentTimeMillis()} $message\n".toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            // nothing else to fall back to
        }
        Logs.trace(message)
    }

    /**
     * One poll pass.
     *
     * The cheap channel -- a single cached reflective property read -- runs every tick.
     * The two expensive ones only run once it says an action is waiting:
     * `Settings.Global` goes through the content resolver and so does a binder round
     * trip, and the file probe is a `stat()` on FUSE.  Measured 2026-10-13, those two
     * used to run 43200 times a day to discover that nobody was running an experiment.
     */
    private fun consume() {
        val property = readFromProperty()
        val pending = !property.isNullOrBlank() && IDLE != property.trim()
        var settings: String? = null
        var file: String? = null
        // The first few ticks probe every channel regardless, so the startup
        // diagnostics in az100_console.log still show what each one answers.
        if (pending || ticks < 3) {
            settings = readFromSettings()
            file = readFromFile()
        }
        if (ticks < 3) {
            ticks++
            console(
                "tick property=${quote(property)}"
                        + " settings=${quote(settings)} file=${quote(file)}"
            )
        }
        val action = firstNonEmpty(property, settings, file)
        if (action == null || IDLE == action) {
            lastAction = null
            return
        }
        if (action == lastAction) return
        lastAction = action
        console("action <$action>")
        val result = try {
            execute(action)
        } catch (t: Throwable) {
            Logs.trace("routeB: action failed", t)
            "ERROR $t"
        }
        console("result $result")
    }

    private fun firstNonEmpty(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (!candidate.isNullOrBlank()) return candidate.trim()
        }
        return null
    }

    /**
     * The cheapest channel: a plain JNI property read, no binder call and no [Context],
     * so it cannot block the poll thread.
     *
     * The [Method] and the class are resolved once.  They used to be looked up on every
     * tick, which meant a classloader search and a reflective argument-type match 43200
     * times a day for an answer that never changes.
     */
    private fun readFromProperty(): String? = try {
        val classLoader = loader
        var getter = systemPropertiesGet
        if (getter == null && classLoader != null) {
            getter = Reflect.findClass("android.os.SystemProperties", classLoader)
                .getMethod("get", String::class.java)
            systemPropertiesGet = getter
        }
        getter?.invoke(null, PROPERTY_KEY) as? String
    } catch (t: Throwable) {
        null
    }

    private fun readFromSettings(): String? {
        val ctx = context() ?: return null
        return try {
            Settings.Global.getString(ctx.contentResolver, ACTION_KEY)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readFromFile(): String? {
        val file = File(FILE_PATH)
        return try {
            if (!file.isFile) return null
            FileInputStream(file).use { input ->
                val buffer = ByteArray(file.length().toInt())
                val read = input.read(buffer)
                if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun quote(value: String?): String =
        if (value == null) "<null>" else "'${value.trim()}'"

    private fun context(): Context? {
        try {
            val application = RuntimeEnvironment.application
            if (application != null) return application
        } catch (t: Throwable) {
            // fall through to the ActivityThread lookup
        }
        val classLoader = loader ?: return null
        return try {
            val application = Reflect.callStaticMethod(
                Reflect.findClass("android.app.ActivityThread", classLoader),
                "currentApplication"
            )
            application as? Context
        } catch (t: Throwable) {
            // no context: the console stays dormant
            null
        }
    }

    private fun execute(line: String): String {
        val parts = line.split("\\s+".toRegex()).toTypedArray()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "no adapter"
        return when (parts[0].lowercase(Locale.ROOT)) {
            "target" -> {
                if (parts.size < 2) return "usage: target <mac>"
                target = parts[1]
                "target=${mask(target)}"
            }

            "unpair" -> {
                val device = remote(adapter, parts)
                if (device == null) "bad mac" else "removeBond=${removeBond(device)}"
            }

            "bond" -> {
                val device = remote(adapter, parts) ?: return "bad mac"
                val transport = if (parts.size > 2) parts[2].toInt() else TRANSPORT_LE
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.createBond(transport)
                } else {
                    device.createBond()
                }
                "createBond($transport)=$started"
            }

            // Bond onto LE and watch.  The reply stays disarmed unless AUTO_CONFIRM is
            // flipped back on, because it was measured to be a dead end -- see
            // AUTO_CONFIRM.  The point of this action now is to exercise the consent
            // bypass and measure the resulting bond.
            //   autobond [mac] [transport] [variant]
            "autobond" -> {
                val device = remote(adapter, parts) ?: return "bad mac"
                val transport = if (parts.size > 2) parts[2].toInt() else TRANSPORT_LE
                confirmTransport = if (parts.size > 3) parts[3].toInt() else 0
                confirmVariant = if (parts.size > 4) parts[4].toInt() else 0
                confirmAttempted = false
                autoConfirmArmed = AUTO_CONFIRM
                if (autoConfirmArmed) armConfirmWatchdog(device)
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.createBond(transport)
                } else {
                    device.createBond()
                }
                "autobond($transport)=$started bypassConsent=$BYPASS_CONSENT" +
                        " reply=" + (if (autoConfirmArmed) "armed" else "disarmed")
            }

            "autoconfirm" -> {
                autoConfirmArmed = parts.size > 1 && "on".equals(parts[1], ignoreCase = true)
                "autoconfirm=$autoConfirmArmed"
            }

            "confirm" -> {
                val device = remote(adapter, parts) ?: return "bad mac"
                if (parts.size > 2) confirmTransport = parts[2].toInt()
                if (parts.size > 3) confirmVariant = parts[3].toInt()
                confirmPairing(device)
            }

            // Walk every reply-argument pair inside one pairing window.
            "sweep" -> {
                val device = remote(adapter, parts) ?: return "bad mac"
                val transport = if (parts.size > 2) parts[2].toInt() else TRANSPORT_LE
                sweepBond(device, transport)
                "sweep armed, ${SWEEP.size} candidates, step ${SWEEP_STEP_MS}ms"
            }

            // Ask a profile service to dial a set member the stack never calls on its
            // own -- the right earbud, in practice.  It shows up in the CSIS group and
            // in the scan results, yet no profile ever issues a connect for it, so this
            // forces one and reports the policy that okToConnect will judge.
            //   connect <mac> [leaudio|vcs|csip]
            "connect" -> {
                val device = remote(adapter, parts) ?: return "bad mac"
                connectProfile(device, if (parts.size > 2) parts[2] else "leaudio")
            }

            "vcs-low" -> {
                if (parts.size != 3) return "usage: vcs-low <AZ100 mac> <0..15>"
                val device = remote(adapter, parts) ?: return "bad mac"
                val volume = parts[2].toInt()
                if (!matchesAddress(addressOf(device)) || volume < 0 || volume > 15) {
                    return "AZ100 / low-volume range only"
                }
                val vcs = profileService("getVolumeControlService") ?: return "profile not ready"
                val lea = profileService("getLeAudioService") ?: return "profile not ready"
                val groupId = Reflect.callMethod(lea, "getGroupId", device) as Int
                if (!az100Group(groupId)) return "AZ100 group not ready"
                val members = volumeMembers("g:$groupId")
                if (members.isNullOrEmpty()) return "no VCP-connected AZ100"
                Reflect.callMethod(vcs, "setGroupVolume", groupId, volume)
                "requested g:$groupId = $volume (submission, not ACK)"
            }

            "status" -> status(adapter)
            "prefs" -> "auto_lea=${Prefs.autoLea}, twin_auto_connect=${Prefs.twin}, " +
                    "volume_fix=${Prefs.volumeFix}, diagnose=${Prefs.diagnose}"

            // Which ROM methods tolerate a null device?  Measured, not guessed.
            "nullprobe" -> nullProbe()

            else -> "unknown action: ${parts[0]}"
        }
    }

    /**
     * Calls the ROM's device-taking methods with `null` and reports which ones throw.
     *
     * The vendor extension in `ActiveDeviceManager` hands a null device straight to
     * `AdapterService.isProfileSupported()`, which crashed the whole Bluetooth process
     * 145 times -- see [OplusDualModeCoexistHook.hookNullDeviceProfileQuery].  That fix
     * covers the one method measured to crash.  This probe asks the same question of the
     * rest of the surface so the guards can be placed on evidence instead of on the
     * decompiler's say-so.
     *
     * Every call is wrapped: a [NullPointerException] here is a result, not a fault.
     * Nothing is called that writes state -- the setters are deliberately left out,
     * because a probe must not be able to corrupt the config it is inspecting.
     */
    private fun nullProbe(): String {
        val service = adapterService ?: return "no adapter service"
        val report = StringBuilder()
        val deviceSignature = arrayOf<Class<*>>(BluetoothDevice::class.java)
        val deviceProfileSignature =
            arrayOf<Class<*>>(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)

        // AdapterService: the layer the vendor extension calls into.  These are the
        // same signatures the ROM passes a device to internally.
        probe(report, "adapter.isProfileSupported(null,1)", service,
            "isProfileSupported", deviceProfileSignature, null, 1)
        probe(report, "adapter.getProfileConnectionPolicy(null,1)", service,
            "getProfileConnectionPolicy", deviceProfileSignature, null, 1)
        for (name in arrayOf(
            "getConnectionState", "getRemoteUuids", "getPreferredAudioProfiles",
            "getRemoteClass", "getRemoteName", "getBondState", "getDeviceType"
        )) {
            probe(report, "adapter.$name(null)", service, name, deviceSignature, null)
        }

        probeService(report, "lea", profileService("getLeAudioService"),
            arrayOf("getConnectionState", "getGroupId", "getGroupDevices", "getLeadDevice"))
        probeService(report, "vcs", profileService("getVolumeControlService"),
            arrayOf("getConnectionState", "getGroupId"))
        probeService(report, "a2dp", profileService("getA2dpService"),
            arrayOf("getConnectionState"))
        probeService(report, "hfp", profileService("getHeadsetService"),
            arrayOf("getConnectionState"))
        probeService(report, "bass", profileService("getBassClientService"),
            arrayOf("getConnectionState"))

        // ConnectableProfile is the shared base for every connectable profile, so one
        // unguarded dereference here is every profile's problem.  Declared on the base,
        // which is why findMethod() walks the hierarchy.
        val a2dp = profileService("getA2dpService")
        probe(report, "profile.getConnectionPolicy(null)", a2dp,
            "getConnectionPolicy", deviceSignature, null)
        probe(report, "profile.okToConnect(null)", a2dp, "okToConnect", deviceSignature, null)
        probe(report, "profile.handleBondStateChanged(null,0,0)", a2dp,
            "handleBondStateChanged",
            arrayOf(
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            null, 0, 0)

        return report.toString()
    }

    /** Every one-arg device method on a profile service, with null. */
    private fun probeService(
        report: StringBuilder,
        label: String,
        service: Any?,
        methods: Array<String>
    ) {
        if (service == null) {
            report.append(label).append(": not running\n")
            return
        }
        for (name in methods) {
            probe(report, "$label.$name(null)", service, name,
                arrayOf<Class<*>>(BluetoothDevice::class.java), null)
        }
    }

    /**
     * Resolves the method by its declared signature and invokes it with null.
     *
     * Explicit parameter types rather than [XposedHelpers.callMethod]: that helper
     * derives the signature from the argument classes, and a null argument has no class
     * -- it cannot find the method at all.
     */
    private fun probe(
        report: StringBuilder,
        label: String,
        target: Any?,
        name: String,
        signature: Array<Class<*>>,
        vararg args: Any?
    ) {
        val outcome = try {
            val method = findMethod(target!!.javaClass, name, signature)
            method.isAccessible = true
            val result = method.invoke(target, *args)
            if (result == null) "ok(null)" else "ok(${shortValue(result)})"
        } catch (t: Throwable) {
            var cause = t
            if (t is java.lang.reflect.InvocationTargetException && t.cause != null) {
                cause = t.cause!!
            }
            cause.javaClass.simpleName +
                    (cause.message?.let { ": " + it.split("\n")[0] } ?: "")
        }
        report.append(label).append(" -> ").append(outcome).append('\n')
        Logs.trace("nullprobe: $label -> $outcome")
    }

    /**
     * Finds a method by name and parameter types, walking up the class hierarchy.
     *
     * [XposedHelpers.findMethodExact] only looks at the named class, and several of the
     * methods that matter here -- `okToConnect`, `getConnectionPolicy` -- are declared
     * once on the shared `ConnectableProfile` base rather than on each profile service.
     */
    @Throws(NoSuchMethodException::class)
    private fun findMethod(
        clazz: Class<*>,
        name: String,
        signature: Array<Class<*>>
    ): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *signature)
            } catch (ignored: NoSuchMethodException) {
                // keep walking
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$name")
    }

    /** Keep one line per probe: some of these return arrays or lists. */
    private fun shortValue(value: Any): String {
        var text = value.toString()
        if (text.length > 60) text = text.substring(0, 60) + "..."
        return text.replace('\n', ' ')
    }

    /**
     * Whenever LE Audio dials one earbud, dial the other one too.  The set is stored
     * (CSIS loads both addresses and both report `le_audio: ALLOWED`), yet nothing ever
     * issues a connect for the second address -- the scan sees it and drops it as
     * "known" every few seconds instead.
     */
    private fun hookLeAudioTwin(param: HookRuntime) {
        val classLoader = loader ?: return
        try {
            val lea = Reflect.findClass(LE_AUDIO_SERVICE, classLoader)
            with(param) {
                lea.resolve().optional(silent = true).method { name = "connect" }
                    .hookAll {
                        after {
                            val args = args
                            if (args.isEmpty()) return@after
                            val device = args[0] as? BluetoothDevice ?: return@after
                            if (hasThrowable == true || result != true) return@after
                            // Read here, not at install time: see install().
                            if (!twinAutoConnect()) return@after
                            // Only an explicit local selection clears TWIN_BLOCKED.
                            // Policy retries reach this same hook too.
                            connectTwin(instanceOrNull, device)
                        }
                    }
                lea.resolve().optional(silent = true).method { name = "disconnect" }
                    .hookAll {
                        before {
                            val args = args
                            if (args.isEmpty()) return@before
                            val device = args[0] as? BluetoothDevice ?: return@before
                            val address = addressOf(device)
                            if (address != null && matchesAddress(address)) {
                                TWIN_BLOCKED.add(address)
                            }
                        }
                    }
            }
            Logs.trace("lea: bounded twin auto-connect armed")
        } catch (t: Throwable) {
            Logs.trace("lea: cannot hook LeAudioService#connect", t)
        }
    }

    /** Guard against the sibling connect re-entering this hook. */
    private val IN_TWIN = ThreadLocal<Boolean>()
    private val TWIN_BLOCKED = ConcurrentHashMap.newKeySet<String>()
    private val TWIN_ATTEMPTS = ConcurrentHashMap<String, Long>()
    private const val TWIN_COOLDOWN_MS = 60000L

    /** A user selection of either identity permits the whole pair to connect. */
    fun onLocalLeaSelection() {
        TWIN_BLOCKED.remove(Az100Hook.AZ100_MAC)
        TWIN_BLOCKED.remove(Az100Hook.AZ100_LE_MAC)
        TWIN_ATTEMPTS.remove(Az100Hook.AZ100_MAC)
        TWIN_ATTEMPTS.remove(Az100Hook.AZ100_LE_MAC)
    }

    private fun connectTwin(leAudioService: Any?, device: BluetoothDevice) {
        if (!twinAutoConnect()) return
        if (OplusDualModeCoexistHook.shouldYieldLeAudioAutoConnect()) {
            Logs.trace("lea: twin connect yielded during host hand-over")
            return
        }
        val address = addressOf(device)
        if (!matchesAddress(address)) return
        if (IN_TWIN.get() == true) return
        val twinAddress = if (Az100Hook.AZ100_MAC.equals(address, ignoreCase = true)) {
            Az100Hook.AZ100_LE_MAC
        } else {
            Az100Hook.AZ100_MAC
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val twin = adapter.getRemoteDevice(twinAddress)
            val state = Reflect.callMethod(leAudioService, "getConnectionState", twin) as Int
            // Do not duplicate Connecting or interfere with Disconnecting.
            if (state != 0 || TWIN_BLOCKED.contains(twinAddress)) return
            if (twin.bondState != BluetoothDevice.BOND_BONDED) return
            val policy = Reflect.callMethod(
                leAudioService, "getConnectionPolicy", twin
            ) as Int
            if (policy != CONNECTION_POLICY_ALLOWED && policy != CONNECTION_POLICY_UNKNOWN) return
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(TWIN_ATTEMPTS) {
                val previous = TWIN_ATTEMPTS[twinAddress]
                if (previous != null && now - previous < TWIN_COOLDOWN_MS) return
                TWIN_ATTEMPTS[twinAddress] = now
            }
            // One best-effort attempt, never a retry loop.  We cannot infer case state.
            IN_TWIN.set(true)
            try {
                val started = Reflect.callMethod(leAudioService, "connect", twin)
                Logs.trace(
                    "lea: twin connect ${mask(twinAddress)} (state $state) -> $started"
                )
            } finally {
                IN_TWIN.set(false)
            }
        } catch (t: Throwable) {
            Logs.trace("lea: twin connect failed for ${mask(twinAddress)}", t)
        }
    }

    /**
     * Reach a profile service through `AdapterService` and call `connect(device)` on it.
     * Reports the connection policy alongside the result, because
     * `ConnectableProfile#okToConnect` rejects anything that is not `-1` or `100` and
     * says so only in a log line -- the policy is the whole answer when connect returns
     * false.
     */
    private fun connectProfile(device: BluetoothDevice, profile: String): String {
        val service = adapterService ?: return "no AdapterService"
        val getter = when (profile.lowercase(Locale.ROOT)) {
            "vcs" -> "getVolumeControlService"
            "csip" -> "getCsipSetCoordinatorService"
            "a2dp" -> "getA2dpService"
            else -> "getLeAudioService"
        }
        val out = StringBuilder(mask(addressOf(device))).append(' ').append(profile).append(": ")
        try {
            val optional = Reflect.callMethod(service, getter)
            if (optional !is Optional<*>) {
                return out.append(getter).append(" returned ").append(optional).toString()
            }
            val profileService = optional.orElse(null)
                ?: return out.append(getter).append(" empty").toString()
            var policy = "?"
            try {
                policy = Reflect.callMethod(
                    profileService, "getConnectionPolicy", device
                ).toString()
            } catch (t: Throwable) {
                Logs.trace("routeB: getConnectionPolicy failed", t)
            }
            val started = Reflect.callMethod(profileService, "connect", device) == true
            out.append("policy=").append(policy).append(" connect=").append(started)
        } catch (t: Throwable) {
            Logs.trace("routeB: connect via $getter failed", t)
            out.append(t)
        }
        val result = out.toString()
        Logs.trace("routeB: $result")
        return result
    }

    private fun remote(adapter: BluetoothAdapter, parts: Array<String>): BluetoothDevice? {
        val mac = if (parts.size > 1) parts[1] else target
        return try {
            adapter.getRemoteDevice(mac)
        } catch (t: Throwable) {
            Logs.trace("routeB: bad address", t)
            null
        }
    }

    /**
     * `BluetoothDevice#removeBond` is a hidden API, so go through
     * `AdapterService#removeBond(BluetoothDevice)` instead -- it is public and does no
     * permission check of its own.
     */
    private fun removeBond(device: BluetoothDevice): Boolean {
        val service = adapterService
        if (service != null) {
            try {
                val method = service.javaClass.getMethod("removeBond", BluetoothDevice::class.java)
                return method.invoke(service, device) == true
            } catch (t: Throwable) {
                Logs.trace("routeB: AdapterService#removeBond failed", t)
            }
        }
        return try {
            val method = BluetoothDevice::class.java.getMethod("removeBond")
            method.invoke(device) == true
        } catch (t: Throwable) {
            Logs.trace("routeB: BluetoothDevice#removeBond failed", t)
            false
        }
    }

    private fun status(adapter: BluetoothAdapter): String {
        val sb = StringBuilder()
        try {
            for (device in adapter.bondedDevices) {
                sb.append("\n    ").append(mask(device.address))
                    .append(" state=").append(device.bondState)
                    .append(" type=").append(device.type)
                    .append(" name=").append(device.name)
            }
            if (sb.isEmpty()) sb.append("no bonded devices")
        } catch (t: Throwable) {
            return "ERROR $t"
        }
        return sb.toString()
    }

    /* ------------------------------------------------------------------ */

    /**
     * Whether this `DeviceProperties` belongs to the headset.
     *
     * Address first, because it is free and exact.  The name is the fallback: the AZ100
     * carries two LE identities today (`84:85` and `3C:62`), and the pairing information
     * in the earbuds is cleared every time the LE Audio mode is switched -- after which
     * the identities may not be the same two.  Matching on `Technics EAH-AZ100` as well
     * means a re-pair does not silently fall outside the hook and cost a whole
     * measurement cycle.
     *
     * `BluetoothDevice#getName` reads a cached field, so this stays a local call and
     * cannot block the stack's property callback.
     */
    private fun matches(properties: Any?, address: String?): Boolean {
        if (matchesAddress(address)) return true
        if (properties == null) return false
        return try {
            val device = Reflect.callMethod(properties, "getDevice")
            device is BluetoothDevice && matches(device)
        } catch (t: Throwable) {
            false
        }
    }

    /** The same test for the two call sites that already hold the device. */
    private fun matches(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        if (matchesAddress(addressOf(device))) return true
        return try {
            device.name?.startsWith(Az100Hook.AZ100_NAME) == true
        } catch (t: Throwable) {
            false
        }
    }

    private fun matchesAddress(address: String?): Boolean =
        address != null && (address.equals(Az100Hook.AZ100_MAC, ignoreCase = true)
                || address.equals(Az100Hook.AZ100_LE_MAC, ignoreCase = true)
                || address.equals(target, ignoreCase = true))

    private fun addressOf(device: BluetoothDevice?): String? = try {
        device?.address
    } catch (t: Throwable) {
        null
    }

    private fun mask(address: String?): String {
        if (address == null || address.length < 5) return "?"
        return "**:**:**" + address.substring(address.length - 5)
    }
}
