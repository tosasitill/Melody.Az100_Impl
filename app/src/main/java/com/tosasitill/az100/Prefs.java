package com.tosasitill.az100;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * The module's user-facing switches.
 *
 * <p>Read from two processes: the settings page runs in {@code
 * com.tosasitill.az100} and writes, the hooks run in {@code
 * com.android.bluetooth} and {@code com.oplus.melody} and read.  The bridge is
 * {@link XSharedPreferences}, which reads the module's own prefs file straight
 * off disk instead of going through a binder -- the Bluetooth process cannot
 * bind to this app, and this app must not need to be running for a hook to see
 * its configuration.
 *
 * <p>Every getter falls back to the built-in default when the file is missing
 * or unreadable.  That matters more than it looks: the first run has no file at
 * all, and a module that silently disabled itself because it could not read its
 * own config would look exactly like a broken module.
 *
 * <p>The file name is {@link #FILE} and it is opened {@code MODE_WORLD_READABLE}.
 * LSPosed lifts the API 24 restriction for module processes and redirects the
 * file into {@code /data/misc/apexdata/<uuid>/prefs/<pkg>/}, mode {@code 0664}
 * -- measured on device, and it is <em>not</em> under the app's own data dir,
 * which has no {@code shared_prefs} at all.  If a ROM refuses both, the
 * settings page says so rather than pretending the switch worked.
 */
final class Prefs {

    /** SharedPreferences file name, and the XSharedPreferences second argument. */
    static final String FILE = "az100";

    /**
     * Let the module upgrade a classic connection to LE Audio on its own.
     *
     * <p>Off means "connect, and leave whatever profile the stack picked alone".
     * It does not stop LE Audio from being used -- the system's own LE Audio
     * switch still does that -- it only stops this module from forcing the
     * upgrade during a reconnect.
     */
    static final String KEY_AUTO_LEA = "auto_lea";

    /** Volume floor and redundant-write suppression for the AZ100. */
    static final String KEY_VOLUME_FIX = "volume_fix";

    /** Connect the other earbud of the set once one side is up. */
    static final String KEY_TWIN = "twin_auto_connect";

    /** Verbose logging into {@code az100.log}.  Off by default. */
    static final String KEY_DIAGNOSE = "diagnose";

    static final boolean DEFAULT_AUTO_LEA = true;
    static final boolean DEFAULT_VOLUME_FIX = true;
    static final boolean DEFAULT_TWIN = true;
    static final boolean DEFAULT_DIAGNOSE = false;

    private static volatile XSharedPreferences shared;
    private static volatile boolean sharedFailed;

    private Prefs() {
    }

    /* ------------------------------------------------------------------ */
    /* settings process                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The handle the settings page writes through.
     *
     * <p>{@code MODE_WORLD_READABLE} is required: the hooks run as a different
     * UID and read the file directly.  LSPosed lifts the API 24 restriction for
     * module processes.  A {@link SecurityException} here is reported to the UI
     * so the user is told the switches will not take effect, instead of the page
     * appearing to work and the Bluetooth side never seeing the change.
     */
    static SharedPreferences open(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE);
    }

    /* ------------------------------------------------------------------ */
    /* hook process                                                        */
    /* ------------------------------------------------------------------ */

    private static XSharedPreferences shared() {
        if (sharedFailed) return null;
        XSharedPreferences prefs = shared;
        if (prefs == null) {
            try {
                prefs = new XSharedPreferences(Az100Hook.MODULE_PACKAGE, FILE);
                shared = prefs;
            } catch (Throwable t) {
                sharedFailed = true;
                return null;
            }
        }
        // The file is written by another process, so the cached copy goes stale.
        // reload() is a stat() plus a reparse only when the mtime moved, which
        // is cheap enough to do on every read -- these are all called from
        // connection callbacks, not from a loop.
        try {
            prefs.reload();
        } catch (Throwable ignored) {
        }
        return prefs;
    }

    static boolean autoLea() {
        return getBoolean(KEY_AUTO_LEA, DEFAULT_AUTO_LEA);
    }

    static boolean volumeFix() {
        return getBoolean(KEY_VOLUME_FIX, DEFAULT_VOLUME_FIX);
    }

    static boolean twin() {
        return getBoolean(KEY_TWIN, DEFAULT_TWIN);
    }

    static boolean diagnose() {
        return getBoolean(KEY_DIAGNOSE, DEFAULT_DIAGNOSE);
    }

    private static boolean getBoolean(String key, boolean fallback) {
        XSharedPreferences prefs = shared();
        if (prefs == null) return fallback;
        try {
            return prefs.getBoolean(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * True for a switch this module actually defines.
     *
     * <p>Guards {@link Az100ConfigProvider}'s write path.  The provider is
     * reachable by another process, so without this a caller could put arbitrary
     * keys into the file -- harmless on its own, but it would silently collide
     * with a future switch of the same name.
     */
    static boolean isKnownKey(String key) {
        return KEY_AUTO_LEA.equals(key) || KEY_VOLUME_FIX.equals(key)
                || KEY_TWIN.equals(key) || KEY_DIAGNOSE.equals(key);
    }
}
