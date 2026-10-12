package com.tosasitill.az100;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Registers the AZ100 into OPPO's native LE Audio whitelist, so the Bluetooth
 * stack treats it like one of OPPO's own earbuds instead of a foreign LE Audio
 * device.
 *
 * <h2>Why</h2>
 *
 * {@code com.oplus.bluetooth.leaudio.OplusLeAudioRemoteDevice} strips the four
 * LE Audio UUIDs off every device that looks LE-Audio-capable but is not in the
 * {@code OplusLeAudioWhiteList} interop list:
 *
 * <pre>
 *   public ParcelUuid[] handleOplusDevicePropertyChangedUuids(ParcelUuid[] newUuids, BluetoothDevice bdDevice) {
 *       if (mAdapterService != null &amp;&amp; !isLeAudioDevice(bdDevice.getAddress())) return newUuids;
 *       boolean deviceInLeaWhiteList =
 *               nativeChannel.interopDatabaseMatch("OplusLeAudioWhiteList", bdDevice);
 *       if (arrayContains(newUuids, BluetoothUuid.LE_AUDIO) &amp;&amp; !deviceInLeaWhiteList) {
 *           // drops 184E (LE_AUDIO), 180F (BATTERY), 1846 (CSIS), 1844 (VCS)
 *       }
 *       return newUuids;
 *   }
 * </pre>
 *
 * <p>This runs for both {@code type == 3} (BR/EDR UUIDs) and {@code type == 28}
 * (LE UUIDs) inside
 * {@code com.oplus.bluetooth.btservice.OplusRemoteDevicesExtImpl
 * #oplusHandleDevicePropertyChangedCallback}, and its output lands in
 * {@code DeviceProperties.setUuidsBrEdr()} / {@code setUuidsLe()}.
 *
 * <p>So any attempt to give the AZ100 its LE Audio UUIDs from the outside --
 * {@link LeAudioTransportHook}'s {@code SPLICE_LE_AUDIO_UUIDS} -- is undone a
 * moment later by this filter. The two fight each other.
 *
 * <h2>What the native whitelist actually is</h2>
 *
 * Two independent keys, both read by {@code libbluetooth_jni.so}:
 *
 * <ol>
 *   <li><b>address key</b> -- an entry in the interop database for the feature
 *       name {@code OplusLeAudioWhiteList}, persisted by the stack itself into
 *       {@code /data/misc/bluedroid/bt_iot_update_list.conf}. Read by
 *       {@code interop_oplus_match_addr(feature, addr)}
 *       ({@code interopDatabaseMatchNative}'s target).</li>
 *   <li><b>vendor/product key</b> -- {@code broadcastVendorId} +
 *       {@code broadcastProductId} in {@code bt_config.conf}, compared against
 *       eight hard-coded OPPO product ids (company id {@code 0x079A}) by
 *       {@code interop_match_vendor_and_3bytes_product_id}. Used by the native
 *       EIR / LEA-bit / encrypt-retry paths. Not settable from Java; see the
 *       report for the one-time conf edit.</li>
 * </ol>
 *
 * <p>This hook only writes the <b>address key</b>, using OPPO's own public API
 * ({@code IOplusBluetoothInteropUtil#interopDatabaseAdd}) -- the same call the
 * stock code makes in {@code OplusPhonePolicyExtImpl#modifyReduceConnTimeoutList}
 * and {@code OplusBtIotCmd#addInteropDevice}. Verified on device: the AZ100
 * already carries an {@code [OplusDelayConnectProfile1S]} entry written exactly
 * this way, so the API works, persists, and is not reverted.
 *
 * <h2>What it should change</h2>
 *
 * Once {@code interopDatabaseMatch("OplusLeAudioWhiteList", az100)} is true,
 * the filter above stops firing and {@code 184E}/{@code 180F}/{@code 1846}/
 * {@code 1844} survive on their own. That is the precondition for deleting
 * {@code SPLICE_LE_AUDIO_UUIDS} -- see the report.
 *
 * <p>Diagnostics: {@code adb logcat -s AZ100:V} (or
 * {@code /data/misc/bluetooth/az100.log}).
 */
final class NativeLeaWhitelistHook {

    /** Flip to false and reinstall to leave the interop database alone. */
    private static final boolean ENABLED = true;

    /**
     * The native feature name. {@code OplusAbstractionLayer} maps the public
     * constant {@code INTEROP_LE_AUDIO_CONNECT_WHITELIST} to this literal.
     */
    private static final String FEATURE = "OplusLeAudioWhiteList";

    /** {@code OplusBtIotCmd.ENTIRE_ADDRESS_LENGTH} -- match the full 6 bytes. */
    private static final int ENTIRE_ADDRESS_LENGTH = 6;

    /** Both halves of the set must be in the list, or the group cannot form. */
    private static final String[] TARGETS = {
            Az100Hook.AZ100_MAC,
            Az100Hook.AZ100_LE_MAC,
    };

    private static final String INTEROP_UTIL =
            "com.oplus.bluetooth.feature.interop.OplusBtInteropUtil";
    private static final String INTEROP_IFACE =
            "com.oplus.bluetooth.common.interfaces.IOplusBluetoothInteropUtil";
    private static final String BT_FACTORY =
            "com.oplus.bluetooth.common.OplusBTFactory";
    private static final String ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";

    /** Delays (ms) after AdapterService#onCreate at which the write is retried. */
    private static final long[] RETRIES = {1500L, 4000L, 10000L};

    private static volatile boolean installed;
    private static volatile ClassLoader loader;
    private static volatile Object interopUtil;

    private NativeLeaWhitelistHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        synchronized (NativeLeaWhitelistHook.class) {
            if (installed) return;
            installed = true;
            loader = classLoader;
        }
        hookAdapterServiceCreate();
    }

    /* ------------------------------------------------------------------ */
    /* timing                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * {@code AdapterService#onCreate} is the earliest anchor that exists in
     * every build, but the JNI side ({@code AdapterNativeInterface#init}, which
     * builds the global interop module object the native methods dispatch
     * through) runs later and asynchronously.  Rather than hooking a second,
     * jadx-renamed method, retry a few times; the write is idempotent.
     */
    private static void hookAdapterServiceCreate() {
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(ADAPTER_SERVICE, loader),
                    "onCreate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            scheduleRetries();
                        }
                    });
            Logs.trace("lea-whitelist: AdapterService#onCreate hooked");
        } catch (Throwable t) {
            Logs.e("lea-whitelist: cannot hook AdapterService#onCreate", t);
        }
    }

    private static void scheduleRetries() {
        Handler handler = new Handler(Looper.getMainLooper());
        for (final long delay : RETRIES) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    registerAll("+" + delay + "ms");
                }
            }, delay);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the write                                                           */
    /* ------------------------------------------------------------------ */

    /** Idempotent. Safe to call at any time after the Bluetooth process is up. */
    static void registerAll(String why) {
        if (!ENABLED) return;
        Object util = interopUtil();
        if (util == null) {
            Logs.trace("lea-whitelist: no interop util yet (" + why + ")");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Logs.trace("lea-whitelist: no adapter (" + why + ")");
            return;
        }
        for (String address : TARGETS) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                Object added = XposedHelpers.callMethod(
                        util, "interopDatabaseAdd", device, FEATURE, ENTIRE_ADDRESS_LENGTH);
                Object matched = XposedHelpers.callMethod(
                        util, "interopDatabaseMatch", FEATURE, device);
                Logs.trace("lea-whitelist: " + mask(address)
                        + " add=" + added + " match=" + matched + " (" + why + ")");
            } catch (Throwable t) {
                Logs.trace("lea-whitelist: " + mask(address) + " failed (" + why + ")", t);
            }
        }
    }

    /**
     * {@code OplusBtInteropUtil.getInstance()} is a plain static singleton, so
     * it needs no feature cache.  The factory path is kept as a fallback for
     * builds where the singleton is not reachable from this class loader.
     */
    private static Object interopUtil() {
        Object cached = interopUtil;
        if (cached != null) return cached;
        try {
            Class<?> util = XposedHelpers.findClass(INTEROP_UTIL, loader);
            Object instance = XposedHelpers.callStaticMethod(util, "getInstance");
            if (instance != null) {
                interopUtil = instance;
                Logs.trace("lea-whitelist: interop util via singleton");
                return instance;
            }
        } catch (Throwable t) {
            Logs.d("lea-whitelist: singleton unavailable", t);
        }
        try {
            Class<?> iface = XposedHelpers.findClass(INTEROP_IFACE, loader);
            Object def = XposedHelpers.getStaticObjectField(iface, "DEFAULT");
            Class<?> factory = XposedHelpers.findClass(BT_FACTORY, loader);
            Object instance = XposedHelpers.callStaticMethod(factory, "getInstance");
            Object feature = XposedHelpers.callMethod(
                    instance, "getFeature", new Object[]{def, new Object[0]});
            if (feature != null) {
                interopUtil = feature;
                Logs.trace("lea-whitelist: interop util via factory");
                return feature;
            }
        } catch (Throwable t) {
            Logs.d("lea-whitelist: factory unavailable", t);
        }
        return null;
    }

    private static String mask(String address) {
        if (address == null || address.length() < 5) return "?";
        return "**:**:**" + address.substring(address.length() - 5);
    }
}
