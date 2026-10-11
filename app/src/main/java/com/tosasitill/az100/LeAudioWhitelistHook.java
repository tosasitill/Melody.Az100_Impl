package com.tosasitill.az100;

import android.bluetooth.BluetoothDevice;
import android.content.ContentProvider;
import android.database.Cursor;
import android.net.Uri;

import java.util.Collection;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the AZ100 look LE-Audio-capable to Melody, and through Melody to
 * whatever asks Melody about the headset.
 *
 * <h2>Why this is a whitelist problem</h2>
 *
 * Melody does not own the LE Audio switch; the switch lives in the system
 * Settings app ({@code wireless.settings.DEVICE_PROFILES_SETTINGS}, highlighted
 * key {@code LE_AUDIO} -- Melody only navigates there, see
 * {@code com.oplus.melody.leaudio.b#d}).  What Melody owns is the capability
 * database every consumer reads:
 *
 * <pre>
 *   WhitelistConfigDTO            id / name / fuzzyMatchName / function{...}
 *   WhitelistConfigDTO$Function   ~135 ints, one per feature.  The one that
 *                                 matters here is {@code brLeCoexist}.
 *   r7.b  (FeatureOptions)        the two global gates, read from system props:
 *                                 persist.sys.oplus.bt.le_audio_white_list_enable
 *                                 persist.sys.bt.leacoexist
 * </pre>
 *
 * {@code J6.b#f(address)} -- "does this device do the BR/LE switch?" -- is
 * exactly {@code FeatureOptions.d() && whitelist(address).function.brLeCoexist == 1},
 * and {@code FeatureOptions.d()} is the two properties above.  So the answer for
 * any device is a whitelist lookup, and the AZ100 is simply absent from it.
 *
 * The whitelist itself is server-pushed ({@code WhitelistRepository.refreshWhitelist}
 * -> {@code model/repository/whitelist/outside_snapshot.json}), matched by device
 * name (exact, or prefix when {@code fuzzyMatchName}) and by product id, and
 * re-exported to other apps through
 * {@code content://com.oplus.melody.providers.MelodyProvider/find_whitelist?macAddress=...}
 * (see {@code MelodyAliveProvider}, codes 1/2/8/9).  A miss returns a null cursor,
 * i.e. "unknown device", and the consumer hides its LE Audio switch.
 *
 * <h2>What this hook does</h2>
 *
 * Two things, both narrow:
 * <ol>
 *   <li>forces the four {@code r7.b} flags that gate LE Audio on ({@code i} =
 *       le_audio, {@code j} = le_audio_over_spp, {@code q} = white_list_enable,
 *       {@code r} = leacoexist);</li>
 *   <li>answers the whitelist lookup with a synthetic entry for the AZ100 that
 *       has exactly one capability set: {@code function.brLeCoExist = 1}.  Every
 *       other feature stays 0, so nothing else about the device changes.</li>
 * </ol>
 *
 * Only the two static resolvers are hooked -- {@code WhitelistUtils.a(productId,
 * name)} and {@code WhitelistUtils.b(device, list)}.  Both the in-process callers
 * ({@code J6.b#f}) and the exported provider ({@code c9.a#a} -> {@code
 * WhitelistRepository.a} -> {@code c(...)} -> {@code T.a}) funnel through them,
 * so the whole app sees the same answer without touching the cached list.
 *
 * <h2>How to tell whether it did anything</h2>
 *
 * The provider hook logs every {@code find_whitelist} query together with the
 * calling package:
 *
 * <pre>adb logcat -s AZ100:V</pre>
 *
 * <h2>Measured result (2026-10-11, OnePlus PLJ110 / ColorOS)</h2>
 *
 * <b>This hook works, but it is not what gates the LE Audio switch in Settings.</b>
 *
 * <p>Root {@code content query} on
 * {@code .../find_whitelist?macAddress=B8:20:8E:EE:84:85} does return the
 * synthesized entry with {@code "brLeCoExist":1}.  However the persistent trace
 * shows the only consumers of Melody's whitelist are
 * {@code com.android.bluetooth} and {@code com.oplus.exsystemservice}, and both
 * read {@code ears_whitelist} (the 93-row outside snapshot, which has no
 * capability bits) -- never {@code find_whitelist}.  {@code com.android.settings}
 * never queries Melody at all.
 *
 * <p>The real gate lives in the Bluetooth stack: see
 * {@code PhonePolicy#processInitProfilePriorities} and
 * {@code AdapterService#isLeAudioAllowed} in {@code Bluetooth.apk}.  It needs the
 * device's SDP to carry {@code 0000184e} (LE Audio), plus the
 * {@code persist.bluetooth.enable_dual_mode_audio} /
 * {@code persist.bluetooth.leaudio.bypass_allow_list} properties.
 *
 * <p>The four {@code persist.sys.oplus.bt.le_audio*} properties this hook forces
 * were already {@code true} on the device, so {@link #hookFeatureOptions()} is
 * redundant there.  Everything is kept because it is harmless and keeps Melody's
 * own BR/LE-coexist view consistent once LE Audio actually runs.  Full write-up:
 * {@code 2026-10-11_leaudio-whitelist-report.md}.
 */
final class LeAudioWhitelistHook {

    /** Flip to false and reinstall to leave Melody exactly as it was. */
    private static final boolean ENABLED = true;

    private static final String WHITELIST_DTO = "com.oplus.melody.common.data.WhitelistConfigDTO";
    private static final String WHITELIST_FUNCTION = WHITELIST_DTO + "$Function";
    private static final String WHITELIST_UTILS = "com.oplus.melody.common.util.T";
    private static final String FEATURE_OPTIONS = "r7.b";
    private static final String ALIVE_PROVIDER =
            "com.oplus.melody.alive.provider.MelodyAliveProvider";

    /** Product id used when Melody has no DeviceInfoManager row for the device. */
    private static final String FALLBACK_PRODUCT_ID = "AZ100";

    private static volatile boolean installed;
    private static volatile ClassLoader loader;

    private LeAudioWhitelistHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        synchronized (LeAudioWhitelistHook.class) {
            if (installed) return;
            installed = true;
            loader = classLoader;
        }
        hookWhitelistUtils();
        hookFeatureOptions();
        hookAliveProvider();
    }

    /* ------------------------------------------------------------------ */
    /* the two resolvers                                                   */
    /* ------------------------------------------------------------------ */

    private static void hookWhitelistUtils() {
        try {
            Class<?> utils = XposedHelpers.findClass(WHITELIST_UTILS, loader);
            // a(Collection<WhitelistConfigDTO> all, String productId, String deviceName)
            XposedHelpers.findAndHookMethod(utils, "a",
                    Collection.class, String.class, String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null) return;
                            String productId = (String) param.args[1];
                            String name = (String) param.args[2];
                            if (!isAz100Name(name)) return;
                            param.setResult(entry(productId, name));
                            Logs.trace("le whitelist: synthesized by name=" + name
                                    + " pid=" + productId);
                        }
                    });
            // b(BluetoothDevice device, Collection<WhitelistConfigDTO> all)
            XposedHelpers.findAndHookMethod(utils, "b",
                    BluetoothDevice.class, Collection.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null) return;
                            BluetoothDevice device = (BluetoothDevice) param.args[0];
                            if (device == null) return;
                            String address = device.getAddress();
                            if (!Az100Hook.isAz100Mac(address)) return;
                            String name = device.getName();
                            param.setResult(entry(null,
                                    name == null || name.isEmpty() ? Az100Hook.AZ100_NAME : name));
                            Logs.trace("le whitelist: synthesized by mac=" + mask(address));
                        }
                    });
            Logs.trace("le whitelist: resolvers hooked");
        } catch (Throwable t) {
            Logs.e("le whitelist: resolver hook failed", t);
        }
    }

    /**
     * The synthetic entry.  Everything except {@code brLeCoexist} stays at the
     * class default (0 = unsupported) so no other Melody feature turns itself on
     * for a headset it still does not really know.
     */
    private static Object entry(String productId, String name) {
        Object dto = XposedHelpers.newInstance(XposedHelpers.findClass(WHITELIST_DTO, loader));
        Object function =
                XposedHelpers.newInstance(XposedHelpers.findClass(WHITELIST_FUNCTION, loader));
        XposedHelpers.callMethod(function, "setBrLeCoexist", 1);
        XposedHelpers.callMethod(dto, "setFunction", function);
        XposedHelpers.callMethod(dto, "setId",
                productId == null || productId.isEmpty() ? FALLBACK_PRODUCT_ID : productId);
        XposedHelpers.callMethod(dto, "setName",
                name == null || name.isEmpty() ? Az100Hook.AZ100_NAME : name);
        XposedHelpers.callMethod(dto, "setFuzzyMatchName", false);
        return dto;
    }

    /* ------------------------------------------------------------------ */
    /* the global gates                                                    */
    /* ------------------------------------------------------------------ */

    /** The four system properties that gate the whole feature, in read order. */
    private static final String[] GATES = {
            "persist.sys.oplus.bt.le_audio",              // -> field i
            "persist.sys.oplus.bt.le_audio_over_spp",     // -> field j
            "persist.sys.oplus.bt.le_audio_white_list_enable", // -> field q
            "persist.sys.bt.leacoexist",                  // -> field r
    };

    /**
     * Two hooks, because either alone leaves a hole:
     *
     * <ol>
     *   <li>{@code a(String)} is {@code getBooleanProperty}, the only reader of
     *       those four properties.  Answering {@code true} here means the flags
     *       come out right no matter <em>when</em> {@code c()} runs -- and in
     *       particular if it already ran before this module was loaded.</li>
     *   <li>{@code c(MelodyApplication)} is the initializer.  Forcing the four
     *       fields right after it covers the opposite ordering.</li>
     * </ol>
     *
     * Neither hook touches any other property or field, so nothing else about
     * Melody's feature detection changes.
     */
    private static void hookFeatureOptions() {
        try {
            Class<?> options = XposedHelpers.findClass(FEATURE_OPTIONS, loader);

            // public static boolean a(String property)
            XposedHelpers.findAndHookMethod(options, "a",
                    String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            String property = (String) param.args[0];
                            if (!isGate(property)) return;
                            param.setResult(Boolean.TRUE);
                            Logs.trace("le whitelist: gate " + property + " -> true");
                        }
                    });

            // public static void c(MelodyApplication)
            XposedHelpers.findAndHookMethod(options, "c",
                    XposedHelpers.findClass("com.oplus.melody.MelodyApplication", loader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            force(options, "i", GATES[0]);
                            force(options, "j", GATES[1]);
                            force(options, "q", GATES[2]);
                            force(options, "r", GATES[3]);
                        }
                    });

            Logs.trace("le whitelist: feature options hooked");
        } catch (Throwable t) {
            Logs.e("le whitelist: feature option hook failed", t);
        }
    }

    private static boolean isGate(String property) {
        if (property == null) return false;
        for (String gate : GATES) {
            if (gate.equals(property)) return true;
        }
        return false;
    }

    private static void force(Class<?> options, String field, String property) {
        try {
            XposedHelpers.setStaticBooleanField(options, field, true);
            Logs.trace("le whitelist: forced " + property + " = true");
        } catch (Throwable t) {
            Logs.d("le whitelist: cannot force " + field, t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the exported provider (diagnostics)                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Melody re-exports the whitelist to other apps through
     * {@code content://com.oplus.melody.providers.MelodyProvider}.  Nothing is
     * patched here -- the resolvers above already answer that path -- but every
     * query is logged with its caller, which is the only way to find out whether
     * the Settings app is really the consumer.
     */
    private static void hookAliveProvider() {
        try {
            Class<?> provider = XposedHelpers.findClass(ALIVE_PROVIDER, loader);
            XposedHelpers.findAndHookMethod(provider, "query",
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Uri uri = (Uri) param.args[0];
                                String path = uri == null ? null : uri.getPath();
                                if (path == null || !path.contains("whitelist")) return;
                                String caller = param.thisObject instanceof ContentProvider
                                        ? ((ContentProvider) param.thisObject).getCallingPackage()
                                        : null;
                                String mac = uri.getQueryParameter("macAddress");
                                Cursor result = (Cursor) param.getResult();
                                Logs.trace("le whitelist: " + path.substring(1)
                                        + " from " + caller
                                        + (mac == null ? "" : " mac=" + mask(mac))
                                        + " -> " + (result == null ? "null" : result.getCount() + " row(s)"));
                            } catch (Throwable t) {
                                Logs.d("le whitelist: provider log failed", t);
                            }
                        }
                    });
            Logs.trace("le whitelist: alive provider hooked");
        } catch (Throwable t) {
            Logs.e("le whitelist: alive provider hook failed", t);
        }
    }

    /* ------------------------------------------------------------------ */

    private static boolean isAz100Name(String name) {
        if (name == null || name.isEmpty()) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("AZ100") || upper.contains("AZ-100")
                || upper.contains("EAH-") || upper.contains("TECHNICS");
    }

    /** Never log a full address: the module's own traces stay redacted. */
    private static String mask(String address) {
        if (address == null || address.length() < 5) return "?";
        return "**:**:**" + address.substring(address.length() - 5);
    }
}
