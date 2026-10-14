package com.tosasitill.az100

import android.content.Context
import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge

/**
 * The module's user-facing switches.
 *
 * Read from two processes: the settings page runs in `com.tosasitill.az100` and
 * writes, the hooks run in `com.android.bluetooth` and `com.oplus.melody` and
 * read.  The bridge is [YukiHookPrefsBridge], which in a hook process reads the
 * module's own prefs file straight off disk through `XSharedPreferences`
 * instead of going through a binder -- the Bluetooth process cannot bind to this
 * app, and this app must not need to be running for a hook to see its
 * configuration.
 *
 * The bridge is handed in by the hook installers ([attach]), which are the only
 * place with a `PackageParam` to build one from.  Until then every getter falls
 * back to the built-in default.  That matters more than it looks: the first run
 * has no file at all, and a module that silently disabled itself because it could
 * not read its own config would look exactly like a broken module.
 *
 * The file name is [FILE] and it is opened `MODE_WORLD_READABLE`.  LSPosed lifts
 * the API 24 restriction for module processes and redirects the file into
 * `/data/misc/apexdata/<uuid>/prefs/<pkg>/`, mode `0664` -- measured on device,
 * and it is *not* under the app's own data dir, which has no `shared_prefs` at
 * all.  If a ROM refuses both, the settings page says so rather than pretending
 * the switch worked.
 */
object Prefs {

    /** SharedPreferences file name, and the XSharedPreferences second argument. */
    const val FILE = "az100"

    /**
     * Let the module upgrade a classic connection to LE Audio on its own.
     *
     * Off means "connect, and leave whatever profile the stack picked alone".
     * It does not stop LE Audio from being used -- the system's own LE Audio
     * switch still does that -- it only stops this module from forcing the
     * upgrade during a reconnect.
     */
    const val KEY_AUTO_LEA = "auto_lea"

    /** Volume floor and redundant-write suppression for the AZ100. */
    const val KEY_VOLUME_FIX = "volume_fix"

    /** Connect the other earbud of the set once one side is up. */
    const val KEY_TWIN = "twin_auto_connect"

    /** Verbose logging into `az100.log`.  Off by default. */
    const val KEY_DIAGNOSE = "diagnose"

    const val DEFAULT_AUTO_LEA = true
    const val DEFAULT_VOLUME_FIX = true
    const val DEFAULT_TWIN = true
    const val DEFAULT_DIAGNOSE = false

    @Volatile
    private var shared: YukiHookPrefsBridge? = null

    @Volatile
    private var sharedFailed = false

    /* ------------------------------------------------------------------ */
    /* settings process                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The handle the settings page writes through.
     *
     * `MODE_WORLD_READABLE` is required: the hooks run as a different UID and
     * read the file directly.  LSPosed lifts the API 24 restriction for module
     * processes.  A [SecurityException] here is reported to the UI so the user is
     * told the switches will not take effect, instead of the page appearing to
     * work and the Bluetooth side never seeing the change.
     */
    fun open(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)

    /* ------------------------------------------------------------------ */
    /* hook process                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Hand the hook process its read bridge.  Called from every hook installer
     * before it can need a switch; safe to call more than once.
     *
     * The bridge is renamed to [FILE] first: `PackageParam.prefs` defaults to
     * `<module package>_preferences`, while the settings page writes [FILE]
     * (`"az100"`).  Without the rename every switch would silently read its
     * built-in default and look like a module that does nothing.  `name()` only
     * stores the string; the file is opened lazily on first access.
     */
    fun attach(bridge: YukiHookPrefsBridge) {
        shared = bridge.name(FILE)
        sharedFailed = false
    }

    private fun shared(): YukiHookPrefsBridge? {
        if (sharedFailed) return null
        // The file is written by another process, so the cached copy goes stale.
        // The bridge reloads (stat() plus a reparse only when the mtime moved) on
        // every access, which is cheap enough here -- these are all called from
        // connection callbacks, not from a loop.
        return shared ?: run { sharedFailed = true; null }
    }

    val autoLea: Boolean get() = getBoolean(KEY_AUTO_LEA, DEFAULT_AUTO_LEA)

    val volumeFix: Boolean get() = getBoolean(KEY_VOLUME_FIX, DEFAULT_VOLUME_FIX)

    val twin: Boolean get() = getBoolean(KEY_TWIN, DEFAULT_TWIN)

    val diagnose: Boolean get() = getBoolean(KEY_DIAGNOSE, DEFAULT_DIAGNOSE)

    private fun getBoolean(key: String, fallback: Boolean): Boolean {
        val prefs = shared() ?: return fallback
        return try {
            prefs.getBoolean(key, fallback)
        } catch (t: Throwable) {
            fallback
        }
    }

    /**
     * True for a switch this module actually defines.
     *
     * Guards [Az100ConfigProvider]'s write path.  The provider is reachable by
     * another process, so without this a caller could put arbitrary keys into the
     * file -- harmless on its own, but it would silently collide with a future
     * switch of the same name.
     */
    fun isKnownKey(key: String?): Boolean =
        key == KEY_AUTO_LEA || key == KEY_VOLUME_FIX
                || key == KEY_TWIN || key == KEY_DIAGNOSE
}
