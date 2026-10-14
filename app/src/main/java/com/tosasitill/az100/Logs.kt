package com.tosasitill.az100

import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File
import java.io.FileOutputStream

/**
 * Single logging gate.
 *
 * [trace] is for the Airoha link and the hook timeline -- a handful of calls per
 * session, never a per-frame path.  It goes to logcat under tag `AZ100` and is
 * mirrored into a file the Bluetooth process owns, because logd drops messages
 * from a process as chatty as this one.
 *
 * [i] is for one-shot lifecycle events, so a broken install stays diagnosable
 * even when the trace file was never opened.
 */
object Logs {

    /** Verbose LSPosed logging.  Flip to true, rebuild and reinstall. */
    const val DEBUG = false

    const val TAG = "Az100LSPosed"
    const val TRACE_TAG = "AZ100"

    /**
     * Cap the trace file.  Measured 2026-10-13: 0.70 MB/day while the headset is
     * connected, and the file had reached 173 KB in one process lifetime without
     * ever being trimmed.  A cap keeps a long-lived Bluetooth process from
     * growing it without bound; the old content is kept as `.1` so the previous
     * session stays readable.
     */
    private const val TRACE_MAX_BYTES = 2L * 1024 * 1024

    private val lock = Any()

    @Volatile
    private var traceFile: File? = null

    @Volatile
    private var traceOpened = false

    private var traceStream: FileOutputStream? = null

    /**
     * Point the trace at [directory], or at nothing when it is null.
     *
     * A different target invalidates the open stream, so this is also the way to
     * turn file tracing off.
     */
    fun traceToFile(directory: File?) {
        synchronized(lock) {
            traceFile = directory?.let { File(it, "az100.log") }
            closeQuietly()
            traceOpened = false
        }
    }

    fun d(message: String) {
        if (DEBUG) YLog.debug(msg = message, tag = TAG)
    }

    fun d(message: String, t: Throwable) {
        if (DEBUG) YLog.debug(msg = message, e = t, tag = TAG)
    }

    /** One-shot lifecycle events: process install, fatal setup errors. */
    fun i(message: String) {
        YLog.info(msg = message, tag = TAG)
    }

    fun e(message: String, t: Throwable) {
        YLog.error(msg = message, e = t, tag = TAG)
    }

    /** Airoha link timeline and hook events; never called on a hot path. */
    fun trace(message: String) {
        Log.i(TRACE_TAG, message)
        val file = traceFile ?: return
        val line = "${System.currentTimeMillis()} $message\n"
        // One synchronized block for open/append/rotate.  The old version read
        // traceOpened and then opened the file, so two threads arriving together
        // could both see "not opened yet" and both open with append=false -- the
        // second open truncated whatever the first had just written.  That is how
        // the process-startup lines went missing from az100.log.
        synchronized(lock) {
            try {
                val stream = traceStream ?: FileOutputStream(file, traceOpened)
                    .also { traceStream = it; traceOpened = true }
                stream.write(line.toByteArray(Charsets.UTF_8))
                stream.flush()
                if (stream.channel.size() > TRACE_MAX_BYTES) rotate(file)
            } catch (t: Throwable) {
                // A failed write must not break the caller: drop the stream so
                // the next call reopens it.
                closeQuietly()
            }
        }
    }

    /** Same as [trace], for a failure worth recording too. */
    fun trace(message: String, t: Throwable) = trace("$message - $t")

    /** Move the current trace aside and start a fresh one. */
    private fun rotate(file: File) {
        closeQuietly()
        val previous = File(file.parentFile, file.name + ".1")
        if (!file.renameTo(previous)) {
            // Rename refused (usually because the target exists): truncate in
            // place rather than growing forever.
            traceStream = try {
                FileOutputStream(file, false)
            } catch (t: Throwable) {
                null
            }
            return
        }
        traceStream = try {
            FileOutputStream(file, false)
        } catch (t: Throwable) {
            null
        }
    }

    private fun closeQuietly() {
        val stream = traceStream ?: return
        traceStream = null
        try {
            stream.close()
        } catch (t: Throwable) {
            // Nothing useful to do; the next trace() reopens.
        }
    }
}
