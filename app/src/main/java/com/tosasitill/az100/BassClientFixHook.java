package com.tosasitill.az100;

import android.bluetooth.BluetoothDevice;
import android.os.Looper;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Repairs OPPO's dead {@code mOplusBassClientService} field in
 * {@code com.android.bluetooth.bass_client.BassClientService}.
 *
 * <h2>The bug</h2>
 *
 * {@code BassClientService} declares
 *
 * <pre>
 *   private OplusBassClientService mOplusBassClientService;
 * </pre>
 *
 * and reads it in nine places, but <b>nothing ever writes it</b>.  Verified on
 * the shipping ROM (OnePlus PLJ110, Android 17 / SDK 37,
 * {@code /system/app/Bluetooth/Bluetooth.apk}) by dumping smali:
 *
 * <pre>
 *   .field private mOplusBassClientService:...OplusBassClientService;   x1
 *   iget-object ..., ...->mOplusBassClientService:...                   x8
 *   iput-object ..., ...->mOplusBassClientService:...                   x0
 * </pre>
 *
 * and {@code OplusBassClientService.getInstance(...)} has no caller anywhere in
 * the APK.  So the field is permanently {@code null}, and the vendor's own
 * author clearly knew it -- five of the eight reads are guarded with
 * {@code if (x != null)}.  Three are not:
 *
 * <pre>
 *   935   checkDuplicateSourceAdditionAndGetSourceId()  isDeviceDuringRemoval()
 *   2036  (inside the mLocalBroadcastMetadata != null branch)  setDeviceDisconnect()
 *   3545  connect(BluetoothDevice)                     checkDeviceVcsReconnect()
 * </pre>
 *
 * <h2>Why it started crashing only now</h2>
 *
 * {@code connect()} bails out early unless the peer advertises BASS:
 *
 * <pre>
 *   if (!Util.arrayContains(getRemoteUuids(device), BluetoothUuid.BASS)) {
 *       Log.w(TAG, "connect: ... Remote does not have BASS UUID");
 *       return false;                       // <- used to always return here
 *   }
 *   ...
 *   this.mOplusBassClientService.checkDeviceVcsReconnect(device);   // <- 3545
 * </pre>
 *
 * {@code BluetoothUuid.BASS} is {@code 0000184F}.  On a stock ROM
 * {@code oplus_handle_filter_uuid()} (native, {@code libbluetooth_jni.so})
 * silently drops {@code 1844/1846/184E/184F} for any device that is not in
 * OPPO's hard-coded {@code OplusLeAudioWhiteList}, so {@code connect()} never
 * got past that guard and line 3545 was unreachable.
 *
 * <p>{@link NativeLeaDeviceHook} opens that gate (it makes
 * {@code oplus_btif_is_lea_device()} answer true), {@code 184F} survives, and
 * the latent NPE becomes reachable.  The resulting exception is thrown inside
 * the Bluetooth process and marshalled back over binder, so it escapes
 * {@code AdapterService.connectAllEnabledProfiles()} entirely -- every profile
 * connect in that batch is aborted, and the <em>caller</em> app dies with
 * {@code FATAL EXCEPTION}.  On device this showed up as 15 identical NPEs
 * across six processes ({@code com.oplus.wirelesssettings} and friends) and a
 * headset that would never connect again.
 *
 * <h2>The fix</h2>
 *
 * Give the field the value it was always meant to have.  {@code mBcsWrapper}
 * <i>is</i> assigned (in the constructor, line 793), so the singleton can be
 * built exactly the way the vendor intended:
 *
 * <pre>
 *   OplusBassClientService.getInstance(this, getAdapterService(), mBcsWrapper)
 * </pre>
 *
 * <p>Injected right after construction, so all three unguarded reads become
 * safe <em>and</em> the vendor's VCS-reconnect feature starts working instead
 * of being skipped.
 *
 * <p>{@link #hookConnect()} is a second line of defence: if the field is still
 * null by the time {@code connect()} runs (different build, different field
 * name, injection refused), that call is short-circuited to {@code false}
 * rather than allowed to throw.  BASS is only the Auracast broadcast receiver,
 * so skipping it costs nothing for ordinary unicast music.
 *
 * <h2>How to tell whether it did anything</h2>
 *
 * <pre>adb logcat -s AZ100:V | grep bass-npe</pre>
 *
 * Expected on a healthy boot:
 * <pre>
 * bass-npe: injected OplusBassClientService into BassClientService
 * </pre>
 */
final class BassClientFixHook {

    /** Flip to false and reinstall to leave the vendor code exactly as it was. */
    private static final boolean ENABLED = true;

    private static final String BASS_CLIENT_SERVICE =
            "com.android.bluetooth.bass_client.BassClientService";
    private static final String OPLUS_BASS_CLIENT_SERVICE =
            "com.android.bluetooth.bass_client.OplusBassClientService";
    private static final String ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";
    private static final String SCAN_CONTROLLER =
            "com.android.bluetooth.le_scan.ScanController";
    private static final String WRAPPER_INTERFACE =
            "com.oplus.bluetooth.common.interfaces.IBassClientServiceWrapper";

    /** The dead field. */
    private static final String FIELD = "mOplusBassClientService";

    /** The wrapper the vendor's own constructor does assign (line 793). */
    private static final String WRAPPER_FIELD = "mBcsWrapper";

    private static volatile boolean installed;
    private static volatile boolean injected;

    private static Class<?> bassClientService;
    private static Class<?> adapterService;
    private static Class<?> scanController;
    private static Class<?> wrapperInterface;
    private static Class<?> oplusBassClientService;
    private static Method getInstance;

    private BassClientFixHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        synchronized (BassClientFixHook.class) {
            if (installed) return;
            installed = true;
        }

        try {
            bassClientService = XposedHelpers.findClass(BASS_CLIENT_SERVICE, classLoader);
            adapterService = XposedHelpers.findClass(ADAPTER_SERVICE, classLoader);
            scanController = XposedHelpers.findClass(SCAN_CONTROLLER, classLoader);
            wrapperInterface = XposedHelpers.findClass(WRAPPER_INTERFACE, classLoader);
            oplusBassClientService =
                    XposedHelpers.findClass(OPLUS_BASS_CLIENT_SERVICE, classLoader);
            getInstance = XposedHelpers.findMethodExact(oplusBassClientService, "getInstance",
                    bassClientService, adapterService, wrapperInterface);
        } catch (Throwable t) {
            Logs.e("bass-npe: cannot resolve classes; falling back to connect guard", t);
        }

        hookConstructors();
        hookConnect();
    }

    /* ------------------------------------------------------------------ */
    /* primary fix: give the field its intended value                      */
    /* ------------------------------------------------------------------ */

    /**
     * Both overloads are hooked because which one the framework calls is a
     * build detail; the 2-argument form is what {@code AdapterService}
     * constructs profile services with.
     */
    private static void hookConstructors() {
        if (bassClientService == null || adapterService == null || scanController == null) {
            Logs.e("bass-npe: constructor hook skipped, classes unresolved", null);
            return;
        }
        XC_MethodHook after = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                ensure(param.thisObject);
            }
        };
        try {
            XposedHelpers.findAndHookConstructor(bassClientService,
                    adapterService, scanController, after);
        } catch (Throwable t) {
            Logs.d("bass-npe: 2-arg constructor hook failed", t);
        }
        try {
            XposedHelpers.findAndHookConstructor(bassClientService,
                    adapterService, scanController, Looper.class, after);
        } catch (Throwable t) {
            Logs.d("bass-npe: 3-arg constructor hook failed", t);
        }
    }

    /**
     * @return true when {@code mOplusBassClientService} is non-null afterwards.
     */
    private static boolean ensure(Object service) {
        try {
            if (XposedHelpers.getObjectField(service, FIELD) != null) return true;
        } catch (Throwable t) {
            // Field renamed/absent in this build -- nothing to repair.
            return false;
        }
        if (getInstance == null) return false;

        try {
            Object wrapper = XposedHelpers.getObjectField(service, WRAPPER_FIELD);
            if (wrapper == null) return false;
            Object adapter = XposedHelpers.callMethod(service, "getAdapterService");
            if (adapter == null) return false;

            Object instance = getInstance.invoke(null, service, adapter, wrapper);
            if (instance == null) return false;

            XposedHelpers.setObjectField(service, FIELD, instance);
            if (!injected) {
                injected = true;
                Logs.trace("bass-npe: injected OplusBassClientService into BassClientService");
            }
            return true;
        } catch (Throwable t) {
            Logs.d("bass-npe: injection failed", t);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* second line of defence                                              */
    /* ------------------------------------------------------------------ */

    /**
     * {@code BassClientService#connect(BluetoothDevice)} is the only one of the
     * three unguarded reads reachable from a plain
     * {@code connectAllEnabledProfiles()}, and it is the one that took the
     * whole process down on device.  If injection did not take, refuse the call
     * instead of letting it throw.
     */
    private static void hookConnect() {
        if (bassClientService == null) return;
        try {
            XposedHelpers.findAndHookMethod(bassClientService, "connect",
                    BluetoothDevice.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (ensure(param.thisObject)) return;
                            Logs.trace("bass-npe: mOplusBassClientService still null, "
                                    + "skipping BASS connect to avoid vendor NPE");
                            param.setResult(Boolean.FALSE);
                        }
                    });
        } catch (Throwable t) {
            Logs.e("bass-npe: connect hook failed", t);
        }
    }
}
