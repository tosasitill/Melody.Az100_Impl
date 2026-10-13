package com.tosasitill.az100;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Volume-panel adapter for the Technics EAH-AZ100.
 *
 * The panel lives in SystemUI but reads everything from Melody's
 * EarphoneControlProvider.  This module is therefore scoped to
 * {@code com.oplus.melody} only: it answers that provider and drives the
 * headset through {@link DirectAirohaController}.  SystemUI and Device Space
 * are not injected.
 *
 * <p>The one exception is {@code com.android.bluetooth}, which
 * {@link LeAudioTransportHook} needs in order to force the AZ100's bond over LE
 * -- see that class for why.  It is a self-contained experiment: turning its
 * {@code ENABLED} flag off leaves the Bluetooth process untouched and this
 * class back to Melody-only.
 */
public final class Az100Hook implements IXposedHookLoadPackage {

    /** This module's own package, used by {@link Prefs} to find its prefs file. */
    static final String MODULE_PACKAGE = "com.tosasitill.az100";

    private static final String MELODY = "com.oplus.melody";
    private static final String BLUETOOTH = "com.android.bluetooth";

    static final String AZ100_MAC = "B8:20:8E:EE:84:85";
    /**
     * The other half of the set.  The AZ100 presents two LE identities, both
     * named {@code Technics EAH-AZ100}, both with CoD {@code 0x240404}, both
     * under the same OUI:
     *
     * <pre>
     * B8:20:8E:EE:84:85   [ DUAL ]  bonded, exposes the full LE Audio GATT family
     * B8:20:8E:EE:3C:62   [ LE   ]  the second earbud, never bonded here
     * </pre>
     *
     * <p>{@code 84:85} declares {@code Audio Locations: Sink= left} only, so the
     * group it forms on its own can only ever be mono -- see
     * {@link LeAudioTransportHook}.  The second earbud has to be treated the
     * same way, or the pair can never form a two-device group.
     */
    static final String AZ100_LE_MAC = "B8:20:8E:EE:3C:62";
    static final String AZ100_NAME = "Technics EAH-AZ100";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (BLUETOOTH.equals(param.packageName)) {
            try {
                LeAudioTransportHook.install(param.classLoader);
            } catch (Throwable t) {
                Logs.e("Bluetooth hook failed", t);
            }
            return;
        }
        if (!MELODY.equals(param.packageName)) return;
        final ClassLoader loader = param.classLoader;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            Context app = ctx == null ? null : ctx.getApplicationContext();
                            MelodyProviderHook.install(loader, app != null ? app : ctx);
                        }
                    });
        } catch (Throwable t) {
            Logs.e("Melody hook failed", t);
        }
    }

    static boolean isAz100Mac(String address) {
        return address != null && AZ100_MAC.equalsIgnoreCase(address);
    }

    /** Headset reported a mode.  Only the volume tile consumes it. */
    static void onAirohaMode(String address, int status) {
        if (!isAz100Mac(address)) return;
        MelodyProviderHook.onHeadsetMode(status);
    }

    /** Battery samples are cached by the controller; the tile does not show them. */
    static void onAirohaBattery(String address, int[] values) {
        if (!isAz100Mac(address) || values == null) return;
        MelodyProviderHook.onHeadsetBattery();
    }
}
