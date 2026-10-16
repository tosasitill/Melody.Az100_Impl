package com.tosasitill.az100

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Lets another process read and write the module's switches.
 *
 * ## Why this exists
 *
 * The switches have to be settable from the Melody detail page, which runs in
 * `com.oplus.melody`.  That process cannot open this module's prefs file for
 * writing -- different UID, and `/data/data/<module>` is `0700` -- so the write
 * has to be performed by this module's own process.  This provider is that entry
 * point, and it is the only one: the settings page writes through the same file
 * directly because it already runs here.
 *
 * Melody reaches it with `ContentResolver.call()`.  No provider permission is
 * declared: the payload is four booleans that turn features on and off, and a
 * permission would have to be granted to Melody by signature or by hand, neither
 * of which is available for a third-party package.
 *
 * Because the provider is exported with no permission, the caller is checked by
 * UID instead -- see [isTrustedCaller].  Without that check any installed app
 * could rewrite the module's switches, and one of them (auto-upgrade to LE
 * Audio) is a behaviour the user may have deliberately turned off.
 *
 * ## Reads from the Bluetooth process
 *
 * `com.android.bluetooth` does *not* go through here -- it reads the file with
 * [Prefs], because binding to this app would mean keeping it alive just so a hook
 * could see its configuration, and a hook has to work when nothing else is
 * running.
 */
class Az100ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every switch, as booleans, with defaults filled in for anything unset.
     *
     * Returning the defaults rather than only what is on disk means the caller
     * never has to know them, so a newly added switch shows the right position on
     * an older install without a migration.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isTrustedCaller()) {
            // Answer as if the write failed rather than throwing: the caller is a
            // different process and a crash there would be blamed on Melody.
            Logs.i("config: refused uid ${Binder.getCallingUid()} for $method")
            if (METHOD_SET == method) {
                return Bundle().apply { putBoolean("ok", false) }
            }
            return Bundle()
        }
        if (METHOD_SET == method) {
            return Bundle().apply { putBoolean("ok", set(extras)) }
        }
        val prefs = open()
        return Bundle().apply {
            putBoolean(
                Prefs.KEY_AUTO_LEA,
                prefs.getBoolean(Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA)
            )
            putBoolean(Prefs.KEY_TWIN, prefs.getBoolean(Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN))
            putBoolean(
                Prefs.KEY_VOLUME_FIX,
                prefs.getBoolean(Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX)
            )
            putBoolean(
                Prefs.KEY_DIAGNOSE,
                prefs.getBoolean(Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE)
            )
        }
    }

    private fun set(extras: Bundle?): Boolean {
        val key = extras?.getString(EXTRA_KEY) ?: return false
        // Only known switches.  Without this the caller could write any key into
        // the file, which is harmless today but would silently collide with a
        // future one.
        if (!Prefs.isKnownKey(key)) return false
        return try {
            // commit(), not apply(): the caller is a different process and shows a
            // success line as soon as this returns.  apply() defers the disk write
            // to a background thread, so the caller could read back its own change
            // before it landed -- and a crash in between would lose it.
            Prefs.save(context ?: return false, key, extras.getBoolean(EXTRA_VALUE))
        } catch (t: Throwable) {
            Logs.e("config: write failed for $key", t)
            false
        }
    }

    /**
     * Only this module and Melody may read or write the switches.
     *
     * The provider cannot use a `permission` for this: Melody is a third-party
     * package, so the permission would need a signature match (impossible) or a
     * manual grant.  Checking the calling UID achieves the same thing without
     * asking the user to configure anything.
     *
     * The module's own UID is allowed because the settings page and the provider
     * share a process.  Melody's UID is resolved from the package manager rather
     * than hard-coded, so it survives a reinstall or a different ROM build.  Root
     * (`Process.ROOT_UID`) is allowed for adb-driven inspection of the switches.
     *
     * A failure to resolve Melody's UID is *not* treated as "allow everything":
     * if the package is missing, nothing legitimate is calling this anyway.
     */
    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        if (uid == Process.ROOT_UID) return true
        val ctx = context ?: return false
        val melody = try {
            ctx.packageManager.getPackageUid(MELODY_PACKAGE, 0)
        } catch (t: Throwable) {
            Logs.d("config: cannot resolve $MELODY_PACKAGE: $t")
            -1
        }
        return uid == melody
    }

    private fun open(): SharedPreferences {
        val context = context ?: throw IllegalStateException("no context")
        return Prefs.open(context)
    }

    /* Unused halves of ContentProvider.  Melody only needs call(). */

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? = null

    companion object {
        const val AUTHORITY = "com.tosasitill.az100.config"

        /** The one external process allowed to read and write the switches. */
        const val MELODY_PACKAGE = "com.oplus.melody"
        val URI: Uri = Uri.parse("content://$AUTHORITY")

        /** `call()` method returning every switch in one bundle. */
        const val METHOD_GET_ALL = "get_all"

        /** `call()` method taking a key/value pair in the extras. */
        const val METHOD_SET = "set"

        /** [Bundle] key: which switch.  One of the `Prefs.KEY_*` names. */
        const val EXTRA_KEY = "key"

        /** [Bundle] key: the new value. */
        const val EXTRA_VALUE = "value"
    }
}
