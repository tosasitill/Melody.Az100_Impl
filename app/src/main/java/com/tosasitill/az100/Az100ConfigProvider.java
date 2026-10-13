package com.tosasitill.az100;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Lets another process read and write the module's switches.
 *
 * <h2>Why this exists</h2>
 *
 * The switches have to be settable from the Melody detail page, which runs in
 * {@code com.oplus.melody}.  That process cannot open this module's prefs file
 * for writing -- different UID, and {@code /data/data/<module>} is {@code 0700}
 * -- so the write has to be performed by this module's own process.  This
 * provider is that entry point, and it is the only one: the settings page writes
 * through the same file directly because it already runs here.
 *
 * <p>Melody reaches it with {@code ContentResolver.call()}.  No provider
 * permission is declared: the payload is four booleans that turn features on and
 * off, and a permission would have to be granted to Melody by signature or by
 * hand, neither of which is available for a third-party package.  A caller that
 * can reach this provider can already change the module's behaviour, which is
 * what the user would be doing anyway.
 *
 * <h2>Reads from the Bluetooth process</h2>
 *
 * {@code com.android.bluetooth} does <em>not</em> go through here -- it reads
 * the file with {@link Prefs}, because binding to this app would mean keeping it
 * alive just so a hook could see its configuration, and a hook has to work when
 * nothing else is running.
 */
public class Az100ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.tosasitill.az100.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    /** {@code call()} method returning every switch in one bundle. */
    public static final String METHOD_GET_ALL = "get_all";

    /** {@code call()} method taking a key/value pair in the extras. */
    public static final String METHOD_SET = "set";

    /** {@code Bundle} key: which switch.  One of the {@code Prefs.KEY_*} names. */
    public static final String EXTRA_KEY = "key";

    /** {@code Bundle} key: the new value. */
    public static final String EXTRA_VALUE = "value";

    @Override public boolean onCreate() {
        return true;
    }

    /**
     * Every switch, as booleans, with defaults filled in for anything unset.
     *
     * <p>Returning the defaults rather than only what is on disk means the
     * caller never has to know them, so a newly added switch shows the right
     * position on an older install without a migration.
     */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_SET.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("ok", set(extras));
            return result;
        }
        Bundle all = new Bundle();
        SharedPreferences prefs = open();
        all.putBoolean(Prefs.KEY_AUTO_LEA, prefs.getBoolean(Prefs.KEY_AUTO_LEA, Prefs.DEFAULT_AUTO_LEA));
        all.putBoolean(Prefs.KEY_TWIN, prefs.getBoolean(Prefs.KEY_TWIN, Prefs.DEFAULT_TWIN));
        all.putBoolean(Prefs.KEY_VOLUME_FIX, prefs.getBoolean(Prefs.KEY_VOLUME_FIX, Prefs.DEFAULT_VOLUME_FIX));
        all.putBoolean(Prefs.KEY_DIAGNOSE, prefs.getBoolean(Prefs.KEY_DIAGNOSE, Prefs.DEFAULT_DIAGNOSE));
        return all;
    }

    private boolean set(Bundle extras) {
        if (extras == null) return false;
        String key = extras.getString(EXTRA_KEY);
        if (key == null) return false;
        // Only known switches.  Without this the caller could write any key into
        // the file, which is harmless today but would silently collide with a
        // future one.
        if (!Prefs.isKnownKey(key)) return false;
        try {
            // commit(), not apply(): the caller is a different process and shows
            // a success line as soon as this returns.  apply() defers the disk
            // write to a background thread, so the caller could read back its own
            // change before it landed -- and a crash in between would lose it.
            return open().edit().putBoolean(key, extras.getBoolean(EXTRA_VALUE)).commit();
        } catch (Throwable t) {
            Logs.e("config: write failed for " + key, t);
            return false;
        }
    }

    private SharedPreferences open() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("no context");
        return Prefs.open(context);
    }

    /* Unused halves of ContentProvider.  Melody only needs call(). */

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }

    @Override public String getType(Uri uri) {
        return null;
    }
}
