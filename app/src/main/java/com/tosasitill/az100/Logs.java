package com.tosasitill.az100;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * Single logging gate.
 *
 * Hot paths (hooks that run per frame / per device render) must never build a
 * message unless {@link #DEBUG} is on: {@code if (Logs.DEBUG) Logs.d("...")}
 * compiles to a single static-field read when DEBUG is false.  {@link #i} is
 * only for a handful of one-shot events so a broken install stays diagnosable.
 *
 * {@link #trace} is for the Airoha link only (a handful of calls per session).
 * It goes to logcat under tag {@code AZ100} -- unlike {@link #i}, which goes
 * through LSPosed and is only mirrored to logcat when the module's verbose log
 * is enabled in LSPosed Manager:
 *
 * <pre>adb logcat -s AZ100:V</pre>
 */
final class Logs {

    /** Flip to true, rebuild and reinstall for verbose LSPosed logging. */
    static final boolean DEBUG = false;

    static final String TAG = "Az100LSPosed";

    static final String TRACE_TAG = "AZ100";

    private static volatile File traceFile;
    private static volatile boolean traceOpened;

    /**
     * Mirror the trace into a file the module can always write and {@code adb
     * shell} can always read ({@code /sdcard/Android/data/&lt;pkg&gt;/files/}).
     * logd drops messages from a process as chatty as SystemUI, which made the
     * logcat-only trace useless for diagnosis.
     */
    static void traceToFile(File directory) {
        if (directory == null) return;
        traceFile = new File(directory, "az100.log");
    }

    private Logs() {
    }

    static void d(String message) {
        if (DEBUG) XposedBridge.log(TAG + ": " + message);
    }

    static void d(String message, Throwable t) {
        if (DEBUG) XposedBridge.log(TAG + ": " + message + " - " + t);
    }

    /** One-shot lifecycle events (process install, fatal setup errors). */
    static void i(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static void e(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message + " - " + t);
    }

    /** Airoha link timeline; only called on link events, never on hot paths. */
    static void trace(String message) {
        Log.i(TRACE_TAG, message);
        File file = traceFile;
        if (file == null) return;
        try {
            boolean append = traceOpened;
            if (!append) traceOpened = true;
            FileOutputStream out = new FileOutputStream(file, append);
            String line = System.currentTimeMillis() + " " + message + '\n';
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** Same as {@link #trace(String)}, for a failure worth recording too. */
    static void trace(String message, Throwable t) {
        trace(message + " - " + t);
    }
}
