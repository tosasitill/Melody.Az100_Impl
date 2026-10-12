package com.tosasitill.az100;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.CodeSource;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Forces OPPO's native LE-Audio gate open, in-process, at the instruction level.
 *
 * <h2>Why a native patch and not configuration</h2>
 *
 * Every native LE-Audio decision in {@code libbluetooth_jni.so} funnels through
 * one function:
 *
 * <pre>
 *   bool oplus_btif_is_lea_device(RawAddress&amp; addr)      // +0x643180
 * </pre>
 *
 * which returns true only when <b>four</b> conditions line up:
 *
 * <ol>
 *   <li>{@code bt_config.conf} has {@code broadcastVendorId} -- writable</li>
 *   <li>{@code bt_config.conf} has {@code broadcastProductId} -- writable</li>
 *   <li>{@code bt_config.conf} has {@code customLeaBitWritten == 1} -- <b>no
 *       writer exists anywhere in the stack</b>, so it can only be set by hand</li>
 *   <li>{@code (vendor, product)} appears in the hard-coded
 *       {@code OplusLeAudioWhiteList} -- a {@code .rodata} table holding only
 *       OPPO's own product ids, and there is <b>no JNI entry point that can add
 *       a vendor/product pair</b> to the interop database</li>
 * </ol>
 *
 * Condition 4 alone makes the configuration route impossible: the address-key
 * entry this module used to write lands in {@code interop_oplus_match_addr()},
 * which matches <b>by address</b>, while the gate asks
 * {@code interop_match_vendor_and_3bytes_product_id()}, which matches <b>by
 * vendor/product</b>.  Two different lookups, two different key spaces.
 *
 * <h2>What this does instead</h2>
 *
 * Rewrites the first two instructions after the prologue's {@code bti c}:
 *
 * <pre>
 *   +0x04  52800020   mov  w0, #1
 *   +0x08  d65f03c0   ret
 * </pre>
 *
 * The write goes through {@code /proc/self/mem}, so the kernel does a
 * copy-on-write of the text page -- the change is private to this process and
 * the file on disk is never touched.  It is re-applied on every Bluetooth
 * process start (see {@link #install}).
 *
 * <p>Because the gate is shared, opening it also un-blocks
 * {@code oplus_handle_filter_uuid()}, which strips {@code 184E}/{@code 184F}/
 * {@code 1846}/{@code 1844} from anything it does not consider an OPPO LE Audio
 * device.
 *
 * <p>Diagnostics: {@code adb logcat -s AZ100-Native:V} and
 * {@code /data/misc/bluetooth/az100.log}.
 */
final class NativeLeaDeviceHook {

    /** Flip to false and reinstall to leave the process untouched. */
    private static final boolean ENABLED = true;

    private static final String LIB = "az100patch";
    private static final String LIB_FILE = "libaz100patch.so";
    private static final String ABI = "arm64-v8a";
    private static final String APK_ENTRY = "lib/" + ABI + "/" + LIB_FILE;

    private static final String ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";

    /**
     * {@code AdapterService#onCreate} is the earliest reliable anchor.  The JNI
     * library is mapped by then, but keep a few retries: the first attempt can
     * race the dynamic loader on a slow boot.
     */
    private static final long[] RETRIES = {800L, 2000L, 5000L, 12000L};

    private static volatile boolean installed;
    private static volatile boolean loaded;
    private static volatile boolean done;
    private static ClassLoader loader;

    private NativeLeaDeviceHook() {
    }

    static void install(ClassLoader classLoader) {
        if (!ENABLED) return;
        synchronized (NativeLeaDeviceHook.class) {
            if (installed) return;
            installed = true;
            loader = classLoader;
        }
        hookAdapterServiceCreate();
    }

    private static void hookAdapterServiceCreate() {
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(ADAPTER_SERVICE, loader),
                    "onCreate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            scheduleRetries();
                        }
                    });
            Logs.trace("native: AdapterService#onCreate hooked");
        } catch (Throwable t) {
            Logs.e("native: cannot hook AdapterService#onCreate", t);
        }
    }

    private static void scheduleRetries() {
        Handler handler = new Handler(Looper.getMainLooper());
        for (final long delay : RETRIES) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    apply("+" + delay + "ms");
                }
            }, delay);
        }
    }

    /** Idempotent. Safe to call at any time after the Bluetooth process is up. */
    static void apply(String why) {
        if (!ENABLED || done) return;
        if (!ensureLoaded()) {
            Logs.trace("native: library unavailable (" + why + ")");
            return;
        }
        try {
            int rc = nativePatch();
            Logs.trace("native: patch rc=" + rc + " (" + why + ")");
            if (rc == 0 || rc == 1) done = true;
        } catch (Throwable t) {
            Logs.e("native: patch failed (" + why + ")", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* library loading                                                     */
    /* ------------------------------------------------------------------ */

    private static synchronized boolean ensureLoaded() {
        if (loaded) return true;

        // 1. the normal route -- works when the module apk exposes its lib dir
        try {
            System.loadLibrary(LIB);
            loaded = true;
            Logs.trace("native: loaded via loadLibrary");
            return true;
        } catch (Throwable t) {
            Logs.d("native: loadLibrary failed", t);
        }

        String apk = moduleApkPath();
        if (apk == null) {
            Logs.trace("native: module apk path unknown");
            return false;
        }

        // 2. straight out of the apk (page-aligned .so, Android 10+)
        try {
            System.load(apk + "!/" + APK_ENTRY);
            loaded = true;
            Logs.trace("native: loaded from apk " + apk);
            return true;
        } catch (Throwable t) {
            Logs.d("native: apk-embedded load failed", t);
        }

        // 3. extract to our own cache dir, then load that
        try {
            File out = extract(apk);
            if (out != null) {
                System.load(out.getAbsolutePath());
                loaded = true;
                Logs.trace("native: loaded from " + out);
                return true;
            }
        } catch (Throwable t) {
            Logs.d("native: extract+load failed", t);
        }

        return false;
    }

    /** The module apk is loaded by LSPosed; its CodeSource points at the apk. */
    private static String moduleApkPath() {
        try {
            CodeSource cs = NativeLeaDeviceHook.class
                    .getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String p = cs.getLocation().getPath();
                if (p != null && p.endsWith(".apk")) return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File extract(String apk) throws Exception {
        File dir = cacheDir();
        if (dir == null) return null;
        File out = new File(dir, LIB_FILE);

        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry entry = zip.getEntry(APK_ENTRY);
            if (entry == null) return null;

            InputStream in = zip.getInputStream(entry);
            try {
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
        return out;
    }

    /**
     * Somewhere the Bluetooth process may both write and {@code dlopen} from.
     * {@code codeCacheDir} is the sanctioned spot; {@code /data/local/tmp} is
     * kept as a fallback for builds where the cache dir is not reachable yet.
     */
    private static File cacheDir() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.AndroidAppHelper", loader),
                    "currentApplication");
            if (app != null) {
                Object dir = XposedHelpers.callMethod(app, "getCodeCacheDir");
                if (dir instanceof File && ((File) dir).isDirectory()) return (File) dir;
            }
        } catch (Throwable ignored) {
        }
        File fallback = new File("/data/local/tmp");
        return fallback.isDirectory() ? fallback : null;
    }

    private static native int nativePatch();
}
