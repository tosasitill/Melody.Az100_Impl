package com.tosasitill.az100

import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Forces OPPO's native LE-Audio gate open, in-process, at the instruction level.
 *
 * ## Why a native patch and not configuration
 *
 * Every native LE-Audio decision in `libbluetooth_jni.so` funnels through one
 * function:
 *
 * ```
 *   bool oplus_btif_is_lea_device(RawAddress& addr)      // +0x643180
 * ```
 *
 * which returns true only when **four** conditions line up:
 *
 * 1. `bt_config.conf` has `broadcastVendorId` -- writable
 * 2. `bt_config.conf` has `broadcastProductId` -- writable
 * 3. `bt_config.conf` has `customLeaBitWritten == 1` -- **no writer exists
 *    anywhere in the stack**, so it can only be set by hand
 * 4. `(vendor, product)` appears in the hard-coded `OplusLeAudioWhiteList` -- a
 *    `.rodata` table holding only OPPO's own product ids, and there is **no JNI
 *    entry point that can add a vendor/product pair** to the interop database
 *
 * Condition 4 alone makes the configuration route impossible: the address-key
 * entry this module used to write lands in `interop_oplus_match_addr()`, which
 * matches **by address**, while the gate asks
 * `interop_match_vendor_and_3bytes_product_id()`, which matches **by
 * vendor/product**.  Two different lookups, two different key spaces.
 *
 * ## What this does instead
 *
 * Rewrites the first two instructions after the prologue's `bti c`:
 *
 * ```
 *   +0x04  52800020   mov  w0, #1
 *   +0x08  d65f03c0   ret
 * ```
 *
 * The write goes through `/proc/self/mem`, so the kernel does a copy-on-write of
 * the text page -- the change is private to this process and the file on disk is
 * never touched.  It is re-applied on every Bluetooth process start (see
 * [install]).
 *
 * Because the gate is shared, opening it also un-blocks
 * `oplus_handle_filter_uuid()`, which strips `184E`/`184F`/`1846`/`1844` from
 * anything it does not consider an OPPO LE Audio device.
 */
object NativeLeaDeviceHook {

    /** Flip to false and reinstall to leave the process untouched. */
    private const val ENABLED = true

    private const val LIB = "az100patch"
    private const val LIB_FILE = "libaz100patch.so"
    private const val ABI = "arm64-v8a"
    private const val APK_ENTRY = "lib/$ABI/$LIB_FILE"

    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"

    /**
     * `AdapterService#onCreate` is the earliest reliable anchor.  The JNI library
     * is mapped by then, but keep a few retries: the first attempt can race the
     * dynamic loader on a slow boot.
     */
    private val RETRIES = longArrayOf(800L, 2000L, 5000L, 12000L)

    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var loaded = false

    @Volatile
    private var done = false

    private var loader: ClassLoader? = null

    fun install(param: PackageParam) {
        if (!ENABLED) return
        val classLoader = param.appClassLoader ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
            loader = classLoader
        }
        param.hookAdapterServiceCreate()
    }

    private fun PackageParam.hookAdapterServiceCreate() {
        try {
            Reflect.findClass(ADAPTER_SERVICE, appClassLoader).resolve()
                .firstMethod {
                    name = "onCreate"
                    emptyParameters()
                }
                .hook {
                    after { scheduleRetries() }
                }
            Logs.trace("native: AdapterService#onCreate hooked")
        } catch (t: Throwable) {
            Logs.e("native: cannot hook AdapterService#onCreate", t)
        }
    }

    private fun scheduleRetries() {
        val handler = Handler(Looper.getMainLooper())
        for (delay in RETRIES) {
            handler.postDelayed({ apply("+${delay}ms") }, delay)
        }
    }

    /** Idempotent.  Safe to call at any time after the Bluetooth process is up. */
    fun apply(why: String) {
        if (!ENABLED || done) return
        if (!ensureLoaded()) {
            Logs.trace("native: library unavailable ($why)")
            return
        }
        try {
            val rc = nativePatch()
            Logs.trace("native: patch rc=$rc ($why)")
            if (rc == 0 || rc == 1) done = true
        } catch (t: Throwable) {
            Logs.e("native: patch failed ($why)", t)
        }
    }

    /* ------------------------------------------------------------------ */
    /* library loading                                                     */
    /* ------------------------------------------------------------------ */

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true

        // 1. the normal route -- works when the module apk exposes its lib dir
        try {
            System.loadLibrary(LIB)
            loaded = true
            Logs.trace("native: loaded via loadLibrary")
            return true
        } catch (t: Throwable) {
            Logs.d("native: loadLibrary failed", t)
        }

        val apk = moduleApkPath() ?: run {
            Logs.trace("native: module apk path unknown")
            return false
        }

        // 2. straight out of the apk (page-aligned .so, Android 10+)
        try {
            System.load("$apk!/$APK_ENTRY")
            loaded = true
            Logs.trace("native: loaded from apk $apk")
            return true
        } catch (t: Throwable) {
            Logs.d("native: apk-embedded load failed", t)
        }

        // 3. extract to our own cache dir, then load that
        try {
            val out = extract(apk)
            if (out != null) {
                System.load(out.absolutePath)
                loaded = true
                Logs.trace("native: loaded from $out")
                return true
            }
        } catch (t: Throwable) {
            Logs.d("native: extract+load failed", t)
        }

        return false
    }

    /** The module apk is loaded by LSPosed; its CodeSource points at the apk. */
    private fun moduleApkPath(): String? = try {
        val cs = NativeLeaDeviceHook::class.java.protectionDomain?.codeSource
        val p = cs?.location?.path
        if (p != null && p.endsWith(".apk")) p else null
    } catch (t: Throwable) {
        null
    }

    private fun extract(apk: String): File? {
        val dir = cacheDir() ?: return null
        val out = File(dir, LIB_FILE)

        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(APK_ENTRY) ?: return null
            zip.getInputStream(entry).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    /**
     * Somewhere the Bluetooth process may both write and `dlopen` from.
     * `codeCacheDir` is the sanctioned spot; `/data/local/tmp` is kept as a
     * fallback for builds where the cache dir is not reachable yet.
     */
    private fun cacheDir(): File? {
        val classLoader = loader
        if (classLoader != null) {
            try {
                val app = Reflect.callStaticMethod(
                    Reflect.findClass("android.app.AndroidAppHelper", classLoader),
                    "currentApplication"
                )
                if (app != null) {
                    val dir = Reflect.callMethod(app, "getCodeCacheDir") as? File
                    if (dir != null && dir.isDirectory) return dir
                }
            } catch (t: Throwable) {
                // Fall through to the shared path below.
            }
        }
        val fallback = File("/data/local/tmp")
        return if (fallback.isDirectory) fallback else null
    }

    /**
     * Backed by `libaz100patch.so`, whose export is
     * `Java_com_tosasitill_az100_NativeLeaDeviceHook_nativePatch`.  The Kotlin
     * `object` keeps the class name the symbol encodes, and the native side
     * ignores both its arguments, so the receiver type does not matter.
     */
    private external fun nativePatch(): Int
}
