package com.tosasitill.az100

import android.bluetooth.BluetoothDevice
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.lang.reflect.Method

/**
 * Repairs OPPO's dead `mOplusBassClientService` field in
 * `com.android.bluetooth.bass_client.BassClientService`.
 *
 * ## The bug
 *
 * `BassClientService` declares
 *
 * ```
 *   private OplusBassClientService mOplusBassClientService;
 * ```
 *
 * and reads it in nine places, but **nothing ever writes it**.  Verified on the
 * shipping ROM (OnePlus PLJ110, Android 17 / SDK 37,
 * `/system/app/Bluetooth/Bluetooth.apk`) by dumping smali:
 *
 * ```
 *   .field private mOplusBassClientService:...OplusBassClientService;   x1
 *   iget-object ..., ...->mOplusBassClientService:...                   x8
 *   iput-object ..., ...->mOplusBassClientService:...                   x0
 * ```
 *
 * and `OplusBassClientService.getInstance(...)` has no caller anywhere in the APK.
 * So the field is permanently `null`, and the vendor's own author clearly knew it --
 * five of the eight reads are guarded with `if (x != null)`.  Three are not:
 *
 * ```
 *   935   checkDuplicateSourceAdditionAndGetSourceId()  isDeviceDuringRemoval()
 *   2036  (inside the mLocalBroadcastMetadata != null branch)  setDeviceDisconnect()
 *   3545  connect(BluetoothDevice)                     checkDeviceVcsReconnect()
 * ```
 *
 * ## Why it started crashing only now
 *
 * `connect()` bails out early unless the peer advertises BASS:
 *
 * ```
 *   if (!Util.arrayContains(getRemoteUuids(device), BluetoothUuid.BASS)) {
 *       Log.w(TAG, "connect: ... Remote does not have BASS UUID");
 *       return false;                       // <- used to always return here
 *   }
 *   ...
 *   this.mOplusBassClientService.checkDeviceVcsReconnect(device);   // <- 3545
 * ```
 *
 * `BluetoothUuid.BASS` is `0000184F`.  On a stock ROM `oplus_handle_filter_uuid()`
 * (native, `libbluetooth_jni.so`) silently drops `1844/1846/184E/184F` for any
 * device that is not in OPPO's hard-coded `OplusLeAudioWhiteList`, so `connect()`
 * never got past that guard and line 3545 was unreachable.
 *
 * [NativeLeaDeviceHook] opens that gate (it makes `oplus_btif_is_lea_device()`
 * answer true), `184F` survives, and the latent NPE becomes reachable.  The
 * resulting exception is thrown inside the Bluetooth process and marshalled back
 * over binder, so it escapes `AdapterService.connectAllEnabledProfiles()`
 * entirely -- every profile connect in that batch is aborted, and the *caller* app
 * dies with `FATAL EXCEPTION`.  On device this showed up as 15 identical NPEs
 * across six processes (`com.oplus.wirelesssettings` and friends) and a headset
 * that would never connect again.
 *
 * ## The fix
 *
 * Give the field the value it was always meant to have.  `mBcsWrapper` *is*
 * assigned (in the constructor, line 793), so the singleton can be built exactly
 * the way the vendor intended:
 *
 * ```
 *   OplusBassClientService.getInstance(this, getAdapterService(), mBcsWrapper)
 * ```
 *
 * Injected right after construction, so all three unguarded reads become safe
 * *and* the vendor's VCS-reconnect feature starts working instead of being skipped.
 *
 * [hookConnect] is a second line of defence: if the field is still null by the time
 * `connect()` runs (different build, different field name, injection refused), that
 * call is short-circuited to `false` rather than allowed to throw.  BASS is only the
 * Auracast broadcast receiver, so skipping it costs nothing for ordinary unicast
 * music.
 *
 * ## How to tell whether it did anything
 *
 * ```
 * adb logcat -s AZ100:V | grep bass-npe
 * ```
 *
 * Expected on a healthy boot:
 *
 * ```
 * bass-npe: injected OplusBassClientService into BassClientService
 * ```
 */
object BassClientFixHook {

    /** Flip to false and reinstall to leave the vendor code exactly as it was. */
    private const val ENABLED = true

    private const val BASS_CLIENT_SERVICE = "com.android.bluetooth.bass_client.BassClientService"
    private const val OPLUS_BASS_CLIENT_SERVICE =
        "com.android.bluetooth.bass_client.OplusBassClientService"
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
    private const val SCAN_CONTROLLER = "com.android.bluetooth.le_scan.ScanController"
    private const val WRAPPER_INTERFACE =
        "com.oplus.bluetooth.common.interfaces.IBassClientServiceWrapper"

    /** The dead field. */
    private const val FIELD = "mOplusBassClientService"

    /** The wrapper the vendor's own constructor does assign (line 793). */
    private const val WRAPPER_FIELD = "mBcsWrapper"

    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var injected = false

    private var bassClientService: Class<*>? = null
    private var adapterService: Class<*>? = null
    private var scanController: Class<*>? = null
    private var wrapperInterface: Class<*>? = null
    private var oplusBassClientService: Class<*>? = null
    private var getInstance: Method? = null

    fun install(param: HookRuntime) {
        if (!ENABLED) return
        val classLoader = param.appClassLoader ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
        }

        try {
            val bass = Reflect.findClass(BASS_CLIENT_SERVICE, classLoader)
            val adapter = Reflect.findClass(ADAPTER_SERVICE, classLoader)
            val wrapper = Reflect.findClass(WRAPPER_INTERFACE, classLoader)
            bassClientService = bass
            adapterService = adapter
            scanController = Reflect.findClass(SCAN_CONTROLLER, classLoader)
            wrapperInterface = wrapper
            val oplus = Reflect.findClass(OPLUS_BASS_CLIENT_SERVICE, classLoader)
            oplusBassClientService = oplus
            getInstance = Reflect.findMethodExact(
                oplus, "getInstance",
                bass, adapter, wrapper
            )
        } catch (t: Throwable) {
            Logs.e("bass-npe: cannot resolve classes; falling back to connect guard", t)
        }

        hookConstructors(param)
        hookConnect(param)
    }

    /* ------------------------------------------------------------------ */
    /* primary fix: give the field its intended value                      */
    /* ------------------------------------------------------------------ */

    /**
     * Both overloads are hooked because which one the framework calls is a build
     * detail; the 2-argument form is what `AdapterService` constructs profile
     * services with.
     */
    private fun hookConstructors(param: HookRuntime) {
        val service = bassClientService
        val adapter = adapterService
        val scan = scanController
        if (service == null || adapter == null || scan == null) {
            Logs.i("bass-npe: constructor hook skipped, classes unresolved")
            return
        }
        try {
            with(param) {
                service.resolve().optional(silent = true)
                    .firstConstructor { parameters(adapter, scan) }
                    .hook { after { ensure(instanceOrNull) } }
            }
        } catch (t: Throwable) {
            Logs.d("bass-npe: 2-arg constructor hook failed", t)
        }
        try {
            with(param) {
                service.resolve().optional(silent = true)
                    .firstConstructor { parameters(adapter, scan, Looper::class.java) }
                    .hook { after { ensure(instanceOrNull) } }
            }
        } catch (t: Throwable) {
            Logs.d("bass-npe: 3-arg constructor hook failed", t)
        }
    }

    /** @return true when `mOplusBassClientService` is non-null afterwards. */
    private fun ensure(service: Any?): Boolean {
        if (service == null) return false
        try {
            if (Reflect.getObjectField(service, FIELD) != null) return true
        } catch (t: Throwable) {
            // Field renamed/absent in this build -- nothing to repair.
            return false
        }
        val factory = getInstance ?: return false

        return try {
            val wrapper = Reflect.getObjectField(service, WRAPPER_FIELD) ?: return false
            val adapter = Reflect.callMethod(service, "getAdapterService") ?: return false

            val instance = factory.invoke(null, service, adapter, wrapper) ?: return false
            Reflect.setObjectField(service, FIELD, instance)
            if (!injected) {
                injected = true
                Logs.trace("bass-npe: injected OplusBassClientService into BassClientService")
            }
            true
        } catch (t: Throwable) {
            Logs.d("bass-npe: injection failed", t)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /* second line of defence                                              */
    /* ------------------------------------------------------------------ */

    /**
     * `BassClientService#connect(BluetoothDevice)` is the only one of the three
     * unguarded reads reachable from a plain `connectAllEnabledProfiles()`, and it
     * is the one that took the whole process down on device.  If injection did not
     * take, refuse the call instead of letting it throw.
     */
    private fun hookConnect(param: HookRuntime) {
        val service = bassClientService ?: return
        try {
            with(param) {
                service.resolve().optional(silent = true)
                    .firstMethod {
                        name = "connect"
                        parameters(BluetoothDevice::class.java)
                    }
                    .hook {
                        before {
                            if (ensure(instanceOrNull)) return@before
                            Logs.trace(
                                "bass-npe: mOplusBassClientService still null, "
                                        + "skipping BASS connect to avoid vendor NPE"
                            )
                            result = false
                        }
                    }
            }
        } catch (t: Throwable) {
            Logs.e("bass-npe: connect hook failed", t)
        }
    }
}
