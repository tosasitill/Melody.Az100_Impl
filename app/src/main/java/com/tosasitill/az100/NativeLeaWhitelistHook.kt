package com.tosasitill.az100

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam

/**
 * Registers the AZ100 into OPPO's native LE Audio whitelist, so the Bluetooth
 * stack treats it like one of OPPO's own earbuds instead of a foreign LE Audio
 * device.
 *
 * ## Why
 *
 * `com.oplus.bluetooth.leaudio.OplusLeAudioRemoteDevice` strips the four LE Audio
 * UUIDs off every device that looks LE-Audio-capable but is not in the
 * `OplusLeAudioWhiteList` interop list:
 *
 * ```
 *   public ParcelUuid[] handleOplusDevicePropertyChangedUuids(ParcelUuid[] newUuids, BluetoothDevice bdDevice) {
 *       if (mAdapterService != null && !isLeAudioDevice(bdDevice.getAddress())) return newUuids;
 *       boolean deviceInLeaWhiteList =
 *               nativeChannel.interopDatabaseMatch("OplusLeAudioWhiteList", bdDevice);
 *       if (arrayContains(newUuids, BluetoothUuid.LE_AUDIO) && !deviceInLeaWhiteList) {
 *           // drops 184E (LE_AUDIO), 180F (BATTERY), 1846 (CSIS), 1844 (VCS)
 *       }
 *       return newUuids;
 *   }
 * ```
 *
 * This runs for both `type == 3` (BR/EDR UUIDs) and `type == 28` (LE UUIDs)
 * inside `com.oplus.bluetooth.btservice.OplusRemoteDevicesExtImpl
 * #oplusHandleDevicePropertyChangedCallback`, and its output lands in
 * `DeviceProperties.setUuidsBrEdr()` / `setUuidsLe()`.
 *
 * So any attempt to give the AZ100 its LE Audio UUIDs from the outside --
 * [LeAudioTransportHook]'s `SPLICE_LE_AUDIO_UUIDS` -- is undone a moment later by
 * this filter.  The two fight each other.
 *
 * ## What the native whitelist actually is
 *
 * Two independent keys, both read by `libbluetooth_jni.so`:
 *
 * 1. **address key** -- an entry in the interop database for the feature name
 *    `OplusLeAudioWhiteList`, persisted by the stack itself into
 *    `/data/misc/bluedroid/bt_iot_update_list.conf`.  Read by
 *    `interop_oplus_match_addr(feature, addr)` (`interopDatabaseMatchNative`'s
 *    target).
 * 2. **vendor/product key** -- `broadcastVendorId` + `broadcastProductId` in
 *    `bt_config.conf`, compared against eight hard-coded OPPO product ids
 *    (company id `0x079A`) by `interop_match_vendor_and_3bytes_product_id`.  Used
 *    by the native EIR / LEA-bit / encrypt-retry paths.  Not settable from Java.
 *
 * This hook only writes the **address key**, using OPPO's own public API
 * (`IOplusBluetoothInteropUtil#interopDatabaseAdd`) -- the same call the stock
 * code makes in `OplusPhonePolicyExtImpl#modifyReduceConnTimeoutList` and
 * `OplusBtIotCmd#addInteropDevice`.  Verified on device: the AZ100 already
 * carries an `[OplusDelayConnectProfile1S]` entry written exactly this way, so
 * the API works, persists, and is not reverted.
 */
object NativeLeaWhitelistHook {

    /** Flip to false and reinstall to leave the interop database alone. */
    private const val ENABLED = true

    /**
     * The native feature name.  `OplusAbstractionLayer` maps the public constant
     * `INTEROP_LE_AUDIO_CONNECT_WHITELIST` to this literal.
     */
    private const val FEATURE = "OplusLeAudioWhiteList"

    /** `OplusBtIotCmd.ENTIRE_ADDRESS_LENGTH` -- match the full 6 bytes. */
    private const val ENTIRE_ADDRESS_LENGTH = 6

    /** Both halves of the set must be in the list, or the group cannot form. */
    private val TARGETS = arrayOf(Az100Hook.AZ100_MAC, Az100Hook.AZ100_LE_MAC)

    private const val INTEROP_UTIL = "com.oplus.bluetooth.feature.interop.OplusBtInteropUtil"
    private const val INTEROP_IFACE =
        "com.oplus.bluetooth.common.interfaces.IOplusBluetoothInteropUtil"
    private const val BT_FACTORY = "com.oplus.bluetooth.common.OplusBTFactory"
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"

    /** Delays (ms) after AdapterService#onCreate at which the write is retried. */
    private val RETRIES = longArrayOf(1500L, 4000L, 10000L)

    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var loader: ClassLoader? = null

    @Volatile
    private var interopUtil: Any? = null

    fun install(param: PackageParam) {
        if (!ENABLED) return
        val classLoader = param.appClassLoader ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
            loader = classLoader
        }
        hookAdapterServiceCreate(param)
    }

    /* ------------------------------------------------------------------ */
    /* timing                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * `AdapterService#onCreate` is the earliest anchor that exists in every
     * build, but the JNI side (`AdapterNativeInterface#init`, which builds the
     * global interop module object the native methods dispatch through) runs
     * later and asynchronously.  Rather than hooking a second, jadx-renamed
     * method, retry a few times; the write is idempotent.
     */
    private fun hookAdapterServiceCreate(param: PackageParam) {
        val classLoader = loader ?: return
        try {
            with(param) {
                Reflect.findClass(ADAPTER_SERVICE, classLoader).resolve()
                    .firstMethod {
                        name = "onCreate"
                        emptyParameters()
                    }
                    .hook {
                        after { scheduleRetries() }
                    }
            }
            Logs.trace("lea-whitelist: AdapterService#onCreate hooked")
        } catch (t: Throwable) {
            Logs.e("lea-whitelist: cannot hook AdapterService#onCreate", t)
        }
    }

    private fun scheduleRetries() {
        val handler = Handler(Looper.getMainLooper())
        for (delay in RETRIES) {
            handler.postDelayed({ registerAll("+${delay}ms") }, delay)
        }
    }

    /* ------------------------------------------------------------------ */
    /* the write                                                           */
    /* ------------------------------------------------------------------ */

    /** Idempotent.  Safe to call at any time after the Bluetooth process is up. */
    fun registerAll(why: String) {
        if (!ENABLED) return
        val util = interopUtil() ?: run {
            Logs.trace("lea-whitelist: no interop util yet ($why)")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Logs.trace("lea-whitelist: no adapter ($why)")
            return
        }
        for (address in TARGETS) {
            try {
                val device = adapter.getRemoteDevice(address)
                val added = Reflect.callMethod(
                    util, "interopDatabaseAdd", device, FEATURE, ENTIRE_ADDRESS_LENGTH
                )
                val matched = Reflect.callMethod(util, "interopDatabaseMatch", FEATURE, device)
                Logs.trace("lea-whitelist: ${mask(address)} add=$added match=$matched ($why)")
            } catch (t: Throwable) {
                Logs.trace("lea-whitelist: ${mask(address)} failed ($why)", t)
            }
        }
    }

    /**
     * `OplusBtInteropUtil.getInstance()` is a plain static singleton, so it needs
     * no feature cache.  The factory path is kept as a fallback for builds where
     * the singleton is not reachable from this class loader.
     */
    private fun interopUtil(): Any? {
        interopUtil?.let { return it }
        val classLoader = loader ?: return null
        try {
            val util = Reflect.findClass(INTEROP_UTIL, classLoader)
            val instance = Reflect.callStaticMethod(util, "getInstance")
            if (instance != null) {
                interopUtil = instance
                Logs.trace("lea-whitelist: interop util via singleton")
                return instance
            }
        } catch (t: Throwable) {
            Logs.d("lea-whitelist: singleton unavailable", t)
        }
        try {
            val iface = Reflect.findClass(INTEROP_IFACE, classLoader)
            val default = Reflect.getStaticObjectField(iface, "DEFAULT")
            val factory = Reflect.findClass(BT_FACTORY, classLoader)
            val instance = Reflect.callStaticMethod(factory, "getInstance")
            // `getFeature(default, new Object[0])`: the empty array is one argument,
            // not a spread -- the vararg collects it as a single element.
            val feature = Reflect.callMethod(instance, "getFeature", default, emptyArray<Any>())
            if (feature != null) {
                interopUtil = feature
                Logs.trace("lea-whitelist: interop util via factory")
                return feature
            }
        } catch (t: Throwable) {
            Logs.d("lea-whitelist: factory unavailable", t)
        }
        return null
    }

    private fun mask(address: String?): String {
        if (address == null || address.length < 5) return "?"
        return "**:**:**" + address.substring(address.length - 5)
    }
}
