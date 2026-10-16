package com.tosasitill.az100

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve

/**
 * The 降噪 tile of the ColorOS volume panel lives in SystemUI but every piece of
 * its data comes from Melody, through
 * `content://com.oplus.melody.provider.EarphoneControlProvider`:
 *
 * ```
 *   query .../melody_method_active_device
 *          -> name, address                       (which earphone is shown)
 *   query .../melody_method_noise_reduction, selection=address, args=[mac]
 *          -> name, address, type, supports       (type = current mode,
 *                                                  supports = JSON mode list)
 *   call  melody_method_noise_reduction {name, address, type}   (the click)
 *   notify(base uri, flags) with 0x200 (mode changed) or 0x500 (wear changed,
 *          low byte carries 0x10 left / 0x1 right)
 * ```
 *
 * Melody knows nothing about the AZ100, so all of those are misses: no active
 * device, no support list, no wear state -- and the tile hides itself (the
 * "invisible control" the user started with) or refuses every click.  The answers
 * are therefore produced here, inside Melody's own process, and the headset is
 * driven by the same Airoha session the module uses elsewhere
 * ([DirectAirohaController]).
 *
 * That is also why nothing of this runs in SystemUI: during a panel render the
 * module only reacts to binder calls that arrive at this provider, and the headset
 * work happens on the Airoha worker thread owned by this process.
 *
 * The tile's rules were read from the stock code, not guessed:
 *
 * - `NoiseReductionDetailTile.isAvailable()` needs more than one entry in
 *   support["noise"] and a wear state != 0;
 * - `EarphoneController.getType()` parses the `supports` column as a JSON list of
 *   ints and returns the `type` column as the mode;
 * - `handleUpdateState()` sets the tile state to 0 only while there is no active
 *   device, which is what greyed it out;
 * - the cycle is fixed in the tile (`[1, 5, 10, 2]`) and filtered by the support
 *   list, so leaving 10 out removes 自适应 from the panel.
 */
object MelodyProviderHook {

    private const val PROVIDER = "com.oplus.melody.provider.EarphoneControlProvider"
    private const val BASE_URI = "content://$PROVIDER"
    private const val PATH_ACTIVE = "/melody_method_active_device"
    private const val PATH_NOISE = "/melody_method_noise_reduction"

    /**
     * Module-private read of the cached battery.  SystemUI never asks for it; the
     * Melody-side page does, because the Airoha link lives in this process while
     * that page runs in Melody's `:fg` process.
     */
    private const val PATH_BATTERY = "/melody_method_az100_battery"
    private const val METHOD_NOISE = "melody_method_noise_reduction"
    private const val METHOD_WEAR = "melody_method_control_wear"

    /** Melody's noise ids; see the qs_noise_reduction_* strings. */
    private const val NOISE_OFF = 1
    private const val NOISE_ANC = 5
    private const val NOISE_TRANSPARENT = 2

    /** Modes the tile may cycle through -- 自适应 (10) is left out on purpose. */
    private const val SUPPORTS = "[1,5,2]"

    /** Notification flags: the high byte selects the callback inside SystemUI. */
    private const val FLAG_NOISE = 0x200
    private const val FLAG_WEAR = 0x500

    /**
     * Not a SystemUI flag: its observer only looks at the high byte and knows
     * 0x100 / 0x200 / 0x500, so 0x600 is ignored there.  The Melody-side control
     * page repaints on any change of this authority, which is what this flag is
     * for: fresh battery levels have arrived.
     */
    private const val FLAG_BATTERY = 0x600

    /** Wear bits of the low byte: 0x10 left, 0x1 right. */
    private const val WEAR_BITS = 0x11

    /**
     * After a wear transition, repeat the notification a couple of times.
     * SystemUI may register its observer just after the transition.  Repeating for
     * the whole connection wakes SystemUI on every provider query, so the window is
     * short.
     */
    private const val WEAR_HEARTBEAT_MS = 10_000L
    private const val WEAR_HEARTBEAT_WINDOW_MS = 30_000L

    /** Retries after a connect: 1s, 3s, 8s.  No further notifies. */
    private val WEAR_RETRY_MS = longArrayOf(1000L, 3000L, 8000L)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var noiseMode = NOISE_OFF

    /** Set once the wear state has been announced, cleared when the buds go. */
    @Volatile
    private var worn = false

    @Volatile
    private var lastWearAt = 0L

    /** When the wear flag last actually changed.  Heartbeats stop after the window. */
    @Volatile
    private var wearChangedAt = 0L

    @Volatile
    private var installed = false

    /** Bumped on every ACL transition so a late hide cannot overtake a reconnect. */
    @Volatile
    private var aclDropGeneration = 0

    @Volatile
    private var wearBurstGeneration = 0

    /** The provider query is the one chance to reach an already-registered observer. */
    @Volatile
    private var wearDeliveredToQuery = false

    fun install(param: HookRuntime, ctx: Context?) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val loader = param.appClassLoader ?: return
        context = ctx
        if (ctx != null) Logs.traceToFile(ctx.getExternalFilesDir(null))
        // The detail activity runs in Melody's :fg process and is independent of
        // the provider signatures used by SystemUI.
        MelodyDetailBatteryHook.install(param)
        LeAudioWhitelistHook.install(param)
        try {
            val provider = Reflect.findClass(PROVIDER, loader)
            with(param) {
                provider.resolve().optional(silent = true).firstMethod {
                    name = "query"
                    parameters(
                        Uri::class.java, Array<String>::class.java, String::class.java,
                        Array<String>::class.java, String::class.java
                    )
                }.hook {
                    after {
                        val original = result as? Cursor
                        val patched = patch(
                            args[0] as? Uri,
                            args[2] as? String,
                            args[3] as? Array<String>,
                            original
                        )
                        if (patched != null) {
                            result = patched
                            closeReplaced(original, patched)
                        }
                    }
                }
                provider.resolve().optional(silent = true).firstMethod {
                    name = "call"
                    parameters(String::class.java, String::class.java, Bundle::class.java)
                }.hook {
                    before {
                        if (onCall(args[0] as? String, args[2] as? Bundle)) {
                            result = Bundle()
                        }
                    }
                }
            }
            hookMelodyBluetoothReceiver(param)
            watchAz100Connection(ctx)
            hookBatteryProvider(param)
            if (ctx != null && DirectAirohaController.audioProfileConnected(ctx)) {
                DirectAirohaController.setPresent(true)
                startWearBurst()
            }
            Logs.trace("melody hooks installed")
            Logs.i("melody provider hooks installed")
        } catch (t: Throwable) {
            Logs.e("melody provider hook failed", t)
            Logs.trace("melody hook failed: $t")
        }
    }

    /* ------------------------------------------------------------------ */
    /* provider contract                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Close the cursor Melody returned once it has been replaced.
     *
     * `ContentProvider#query` transfers ownership of the returned cursor to the
     * caller, and the caller only ever sees the replacement -- nothing else holds
     * the original, so dropping the reference leaks whatever it owns (for the
     * stock provider a SQLite cursor, with its window and file descriptor).
     * SystemUI re-queries on every media-route callback, so this leaked once per
     * callback for the lifetime of the process.
     *
     * Only called when [replacement] actually differs from [original]; a cursor
     * we are still handing back must never be closed.  Failures are swallowed:
     * a cursor that refuses to close is not a reason to fail the query.
     */
    private fun closeReplaced(original: Cursor?, replacement: Cursor) {
        if (original == null || original === replacement) return
        try {
            original.close()
        } catch (t: Throwable) {
            Logs.d("closing replaced cursor failed: $t")
        }
    }

    /**
     * Answer the two queries the tile depends on.  A non-null return replaces
     * Melody's own result; null leaves it alone, which keeps a real Melody earphone
     * working exactly as before.
     */
    private fun patch(uri: Uri?, selection: String?, args: Array<String>?, result: Cursor?): Cursor? {
        val path = uri?.path ?: return null
        if (PATH_ACTIVE == path) {
            if (hasRow(result)) return null           // Melody knows a real earphone
            // A non-null row is what makes SystemUI build an ActiveDevice at all;
            // whether the controls are really shown is decided by the wear flag
            // alone (see announceWear).  Answering unconditionally also covers a
            // stale "explicitly gone" ACL hint left over from a disconnect that
            // happened before this process started.
            Logs.trace("provider active")
            announceReachable()
            return activeCursor()
        }
        if (PATH_BATTERY == path) {
            // Cache only.  A query must not open SPP; the once-per-connection sync
            // fills the cache.
            return batteryCursor()
        }
        if (PATH_NOISE == path) {
            // EarphoneController.getType() on this ROM always queries with
            // selection="address" and the active device MAC.  Keep that gate: a
            // loose match can answer another headset's query with the AZ100 row,
            // while a mismatch must fall through to Melody.
            if ("address" != selection || args == null || args.isEmpty()) return null
            if (!Az100Hook.isAz100Mac(args[0])) return null
            // 2+ entries in "noise" is the second isAvailable() condition; the
            // panel only ever asks with the address it got from us.
            announceReachable()
            // SystemUI hits this from media-route callbacks, not only when the
            // panel is open.  syncOnce latches, so this cannot redial.
            Logs.trace("provider noise mode=$noiseMode")
            syncIfNeeded()
            return noiseCursor()
        }
        return null
    }

    /** A click in the panel arrives as a call on the provider. */
    private fun onCall(method: String?, extras: Bundle?): Boolean {
        if (extras == null) return false
        val address = extras.getString("address")
        if (!Az100Hook.isAz100Mac(address)) return false
        if (METHOD_NOISE == method) {
            apply(extras.getInt("type", NOISE_OFF))
            return true
        }
        if (METHOD_WEAR == method) {
            // SystemUI asks whether wear detection should be watched; the AZ100
            // reports its own state, which is pushed through the notifications.
            return true
        }
        return false
    }

    private fun activeCursor(): Cursor =
        MatrixCursor(arrayOf("name", "address")).apply {
            addRow(arrayOf(Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC))
        }

    private fun noiseCursor(): Cursor =
        MatrixCursor(arrayOf("name", "address", "type", "supports")).apply {
            addRow(
                arrayOf<Any?>(
                    Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC,
                    noiseMode, SUPPORTS
                )
            )
        }

    /** Cached battery as a cursor: left, right, case; -1 = not reported yet. */
    private fun batteryCursor(): Cursor {
        val values = DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC)
        return MatrixCursor(arrayOf("name", "address", "left", "right", "case")).apply {
            addRow(
                arrayOf<Any?>(
                    Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC,
                    if (values == null) -1 else values[0],
                    if (values == null) -1 else values[1],
                    if (values == null) -1 else values[2]
                )
            )
        }
    }

    /**
     * Battery levels arrived on this process's Airoha link: let the Melody-side
     * control page reload them (it reads the module-private battery query in
     * [batteryCursor]).
     */
    fun onHeadsetBattery() {
        if (!installed) return
        notifyChange(FLAG_BATTERY)
        notifyBatteryProvider()
    }

    /**
     * Settings reads `content://com.oplus.melody.BatteryProvider`.  Its columns are
     * only mac, left and right; the case has no column there.  A query with no
     * selection lists every known headset, so the AZ100 row is appended.  A query
     * that already names this mac replaces that row.
     */
    private fun hookBatteryProvider(param: HookRuntime) {
        try {
            val provider = Reflect.findClass(
                "com.oplus.melody.provider.BatteryProvider", param.appClassLoader
            )
            with(param) {
                provider.resolve().optional(silent = true).firstMethod {
                    name = "query"
                    parameters(
                        Uri::class.java, Array<String>::class.java, String::class.java,
                        Array<String>::class.java, String::class.java
                    )
                }.hook {
                    after {
                        val original = result as? Cursor
                        val patched = patchBattery(
                            args[3] as? Array<String>,
                            original
                        )
                        if (patched != null) {
                            result = patched
                            closeReplaced(original, patched)
                        }
                    }
                }
            }
            Logs.trace("battery provider hooked")
        } catch (t: Throwable) {
            Logs.e("battery provider hook failed", t)
        }
    }

    private fun patchBattery(args: Array<String>?, result: Cursor?): Cursor? {
        val values = DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC) ?: return null
        val left = if (values.isNotEmpty()) values[0] else -1
        val right = if (values.size > 1) values[1] else -1
        if (left < 0 && right < 0) return null
        val named = args?.any { Az100Hook.isAz100Mac(it) } == true
        if (named) {
            return MatrixCursor(
                arrayOf("macAddress", "headsetLeftBattery", "headsetRightBattery")
            ).apply {
                addRow(arrayOf<Any?>(Az100Hook.AZ100_MAC, known(left), known(right)))
            }
        }
        if (result == null) return null
        val cursor = MatrixCursor(
            arrayOf("macAddress", "headsetLeftBattery", "headsetRightBattery")
        )
        val mac = result.getColumnIndex("macAddress")
        val leftCol = result.getColumnIndex("headsetLeftBattery")
        val rightCol = result.getColumnIndex("headsetRightBattery")
        if (mac >= 0 && result.moveToFirst()) {
            do {
                cursor.addRow(
                    arrayOf(
                        result.getString(mac),
                        if (leftCol < 0) 0 else result.getInt(leftCol),
                        if (rightCol < 0) 0 else result.getInt(rightCol)
                    )
                )
            } while (result.moveToNext())
        }
        cursor.addRow(arrayOf<Any?>(Az100Hook.AZ100_MAC, known(left), known(right)))
        return cursor
    }

    /**
     * A side with no reading stays unknown rather than becoming `0`.
     *
     * `-1` is this module's "not reported yet" sentinel -- the only values
     * `DirectAirohaController.onBattery` ever stores are `0..100` or `-1` -- and
     * `0` is a real, meaningful battery level.  Coercing the sentinel with
     * `maxOf(value, 0)` reported a drained battery for a side that had simply
     * not answered yet, which is why the detail page uses `--` for the same
     * case (`MelodyDetailBatteryHook.percent`).  Keep one convention: a side
     * with nothing to report is left unknown.
     */
    private fun known(value: Int): Int = if (value in 0..100) value else -1

    /** Settings observes the provider URI; the flag value is not interpreted. */
    private fun notifyBatteryProvider() {
        val ctx = context ?: return
        try {
            ctx.contentResolver.notifyChange(
                Uri.parse("content://com.oplus.melody.BatteryProvider"), null
            )
        } catch (t: Throwable) {
            Logs.d("battery notify failed", t)
        }
    }

    private fun hasRow(cursor: Cursor?): Boolean = cursor != null && cursor.count > 0

    /* ------------------------------------------------------------------ */
    /* state                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * One read-back per connection, so a panel opened after the buds were switched
     * on the headset itself still shows the current mode.  Further queries return
     * [noiseMode] and do not page the headset.
     */
    private fun syncIfNeeded() {
        val ctx = context ?: return
        DirectAirohaController.syncOnce(ctx, Az100Hook.AZ100_MAC)
    }

    /** A mode the user picked in the panel: local state, then the headset. */
    private fun apply(mode: Int) {
        noiseMode = mode
        Logs.trace("melody click mode=$mode")
        context?.let { DirectAirohaController.request(it, Az100Hook.AZ100_MAC, airohaStatus(mode)) }
        // Let SystemUI re-read the label right away; if the headset refuses the
        // command its own report arrives later and corrects this again.  The same
        // notification is what repaints the Melody-side control page.
        notifyChange(FLAG_NOISE)
    }

    /**
     * Mode the headset reported (see [Az100Hook.onAirohaMode]).  Runs in whichever
     * process owns the link, hence the [installed] guard.
     */
    fun onHeadsetMode(airohaStatus: Int) {
        if (!installed) return
        val mode = melodyMode(airohaStatus)
        if (mode == noiseMode) return
        noiseMode = mode
        notifyChange(FLAG_NOISE)
    }

    /**
     * Query-time wear refresh: every provider interaction is a chance to
     * re-announce the wear flag, which is the only thing that actually gates the
     * tile (`isAvailable()` checks it as `earState != 0`).  The ACL broadcasts keep
     * this cheap; the one-time profile probe of [DirectAirohaController] covers the
     * case where Melody's process starts after the buds are already connected.
     */
    private fun announceReachable(): Boolean {
        val ctx = context
        var reachable = ctx != null && DirectAirohaController.reachable(ctx, Az100Hook.AZ100_MAC)
        // A stale ACL-absent latch must not keep the tile hidden while audio is
        // still up.  Closing our own short-lived SPP socket can be reported as
        // ACL_DISCONNECTED even though A2DP/HFP never dropped.
        if (!reachable && ctx != null && DirectAirohaController.audioProfileConnected(ctx)) {
            DirectAirohaController.setPresent(true)
            reachable = true
            Logs.trace("wear restored, audio profile still connected")
        }
        // A provider query means SystemUI has just registered its observer.
        // Deliver the current wear flag once for this connection; do not repeat it
        // on later queries.
        announceWear(reachable, true)
        return reachable
    }

    private fun announceWear(wornNow: Boolean, fromQuery: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val changed = wornNow != worn
        if (!changed) {
            if (fromQuery && wornNow && !wearDeliveredToQuery) {
                wearDeliveredToQuery = true
            } else if (now - wearChangedAt > WEAR_HEARTBEAT_WINDOW_MS
                || now - lastWearAt < WEAR_HEARTBEAT_MS
            ) {
                return
            }
        } else {
            worn = wornNow
            wearChangedAt = now
            if (!wornNow) wearDeliveredToQuery = false
            Logs.trace("wear announce worn=$wornNow")
        }
        if (fromQuery && wornNow) wearDeliveredToQuery = true
        lastWearAt = now
        notifyChange(if (wornNow) FLAG_WEAR or WEAR_BITS else FLAG_WEAR)
    }

    /**
     * Melody's own manifest receiver is started by the system for an incoming ACL
     * connect even when the process was dead, so hooking it is the only event that
     * survives the processes being killed between two sessions.
     */
    private fun hookMelodyBluetoothReceiver(param: HookRuntime) {
        try {
            val receiver = Reflect.findClass(
                "com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver", param.appClassLoader
            )
            with(param) {
                receiver.resolve().optional(silent = true).firstMethod {
                    name = "onReceive"
                    parameters(Context::class.java, Intent::class.java)
                }.hook {
                    before {
                        onMelodyBroadcast(args[1] as? Intent)
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.d("melody bt receiver hook failed", t)
        }
    }

    private fun onMelodyBroadcast(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val device = intent.getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        )
        if (device == null || !Az100Hook.isAz100Mac(device.address)) return
        if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
            cancelAclDrop()
            DirectAirohaController.setPresent(true)
            startWearBurst()
            syncIfNeeded()
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
            confirmAclGone(context)
        }
    }

    /** Wear state, straight from the ACL broadcast: no polling, no wakeups. */
    private fun watchAz100Connection(ctx: Context?) {
        if (ctx == null) return
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiver: Context, intent: Intent) {
                        val device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                        )
                        if (device == null || !Az100Hook.isAz100Mac(device.address)) return
                        if (BluetoothDevice.ACTION_ACL_CONNECTED == intent.action) {
                            cancelAclDrop()
                            DirectAirohaController.setPresent(true)
                            startWearBurst()
                            syncIfNeeded()
                        } else {
                            // Do not hide the tile inside the broadcast.  An SPP
                            // close can emit ACL_DISCONNECTED while the audio
                            // profiles are still connected; confirm after they have
                            // settled.
                            confirmAclGone(receiver)
                        }
                    }
                },
                filter
            )
        } catch (t: Throwable) {
            Logs.e("melody ACL receiver failed", t)
        }
    }

    /**
     * `earState` stays at the last value SystemUI observed.  One notify before its
     * observer exists is lost, but a forever timer is unnecessary.  Retry only
     * three times after the connection is seen, then stop.
     */
    private fun startWearBurst() {
        val generation = ++wearBurstGeneration
        wearDeliveredToQuery = false
        announceWear(true)
        Thread({
            var waited = 0L
            for (delay in WEAR_RETRY_MS) {
                try {
                    Thread.sleep(delay - waited)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                waited = delay
                if (generation != wearBurstGeneration || !installed) return@Thread
                val ctx = context
                if (ctx == null || !DirectAirohaController.audioProfileConnected(ctx)) return@Thread
                notifyChange(FLAG_WEAR or WEAR_BITS)
                lastWearAt = SystemClock.elapsedRealtime()
                Logs.trace("wear retry +${delay}ms")
            }
        }, "az100-wear-burst").apply {
            isDaemon = true
            start()
        }
    }

    private fun cancelAclDrop() {
        aclDropGeneration++
    }

    /**
     * ACL_DISCONNECTED is not by itself proof that the AZ100 left this phone.  The
     * battery refresh opens and closes one RFCOMM socket; this stack can report that
     * socket teardown as an ACL drop.  Recheck the public audio profile states
     * before clearing `earState`, which is the flag
     * `NoiseReductionDetailTile.isAvailable()` requires to be non-zero.
     */
    private fun confirmAclGone(ctx: Context?) {
        val generation = ++aclDropGeneration
        val app = if (ctx == null || ctx.applicationContext == null) {
            ctx
        } else {
            ctx.applicationContext
        }
        Thread({
            try {
                Thread.sleep(1200L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            if (generation != aclDropGeneration) return@Thread
            if (DirectAirohaController.audioProfileConnected(app)) {
                Logs.trace("acl disconnect ignored, audio profile still connected")
                DirectAirohaController.setPresent(true)
                announceWear(true)
                return@Thread
            }
            Logs.trace("acl disconnect confirmed")
            wearBurstGeneration++
            DirectAirohaController.setPresent(false)
            if (app != null) {
                DirectAirohaController.disconnect(app, Az100Hook.AZ100_MAC, "acl gone")
            }
            announceWear(false)
        }, "az100-acl-check").apply {
            isDaemon = true
            start()
        }
    }

    private fun notifyChange(flags: Int) {
        val ctx = context ?: return
        try {
            ctx.contentResolver.notifyChange(Uri.parse(BASE_URI), null, flags)
        } catch (t: Throwable) {
            Logs.d("melody notify failed", t)
        }
    }

    /* ------------------------------------------------------------------ */
    /* mode mapping                                                        */
    /* ------------------------------------------------------------------ */

    /** Melody mode -> Airoha status (2 ANC, 3 ambient, 1 off). */
    private fun airohaStatus(mode: Int): Int = when (mode) {
        NOISE_ANC -> 2
        NOISE_TRANSPARENT -> 3
        else -> 1
    }

    /**
     * Airoha status -> Melody mode.  自适应 (4) is shown as 降噪: it is the same
     * noise cancelling with a level that follows the surroundings, which is how the
     * official app displays it, and the panel no longer offers it.
     */
    private fun melodyMode(airohaStatus: Int): Int = when (airohaStatus) {
        2, 4 -> NOISE_ANC
        3 -> NOISE_TRANSPARENT
        else -> NOISE_OFF
    }
}
