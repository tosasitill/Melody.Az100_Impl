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
    private static FileOutputStream traceStream;

    /**
     * Cap the trace file.  Measured 2026-10-13: 0.70 MB/day while the headset is
     * connected, and the file had reached 173 KB in one process lifetime without
     * ever being trimmed.  A cap keeps a long-lived Bluetooth process from
     * growing it without bound; the old content is kept as {@code .1} so the
     * previous session stays readable.
     */
    private static final long TRACE_MAX_BYTES = 2L * 1024 * 1024;

    /**
     * Mirror the trace into a file the module can always write and {@code adb
     * shell} can always read ({@code /sdcard/Android/data/&lt;pkg&gt;/files/}).
     * logd drops messages from a process as chatty as SystemUI, which made the
     * logcat-only trace useless for diagnosis.
     */
    static void traceToFile(File directory) {
        if (directory == null) return;
        synchronized (Logs.class) {
            traceFile = new File(directory, "az100.log");
            // A different target file invalidates the open stream.
            closeQuietly();
            traceOpened = false;
        }
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
        String line = System.currentTimeMillis() + " " + message + '\n';
        // One synchronized block for open/append/rotate.  The old version read
        // traceOpened and then opened the file, so two threads arriving together
        // could both see "not opened yet" and both open with append=false -- the
        // second open truncated whatever the first had just written.  That is how
        // the process-startup lines went missing from az100.log.
        synchronized (Logs.class) {
            try {
                if (traceStream == null) {
                    traceStream = new FileOutputStream(file, traceOpened);
                    traceOpened = true;
                }
                traceStream.write(line.getBytes(StandardCharsets.UTF_8));
                traceStream.flush();
                if (traceStream.getChannel().size() > TRACE_MAX_BYTES) rotate(file);
            } catch (Throwable t) {
                // A failed write must not break the caller: drop the stream so the
                // next call reopens it.
                closeQuietly();
            }
        }
    }

    /** Move the current trace aside and start a fresh one. */
    private static void rotate(File file) {
        closeQuietly();
        File previous = new File(file.getParentFile(), file.getName() + ".1");
        if (!file.renameTo(previous)) {
            // Rename refused (usually because the target exists): truncate in
            // place rather than growing forever.
            try {
                traceStream = new FileOutputStream(file, false);
            } catch (Throwable ignored) {
                traceStream = null;
            }
            return;
        }
        try {
            traceStream = new FileOutputStream(file, false);
        } catch (Throwable ignored) {
            traceStream = null;
        }
    }

    private static void closeQuietly() {
        FileOutputStream stream = traceStream;
        traceStream = null;
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    /** Same as {@link #trace(String)}, for a failure worth recording too. */
    static void trace(String message, Throwable t) {
        trace(message + " - " + t);
    }
}
