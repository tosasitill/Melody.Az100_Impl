package com.tosasitill.az100

import android.bluetooth.BluetoothDevice
import android.content.ContentProvider
import android.database.Cursor
import android.net.Uri
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the AZ100 look LE-Audio-capable to Melody, and through Melody to whatever
 * asks Melody about the headset.
 *
 * ## Why this is a whitelist problem
 *
 * Melody does not own the LE Audio switch; the switch lives in the system Settings
 * app (`wireless.settings.DEVICE_PROFILES_SETTINGS`, highlighted key `LE_AUDIO` --
 * Melody only navigates there, see `com.oplus.melody.leaudio.b#d`).  What Melody
 * owns is the capability database every consumer reads:
 *
 * ```
 *   WhitelistConfigDTO            id / name / fuzzyMatchName / function{...}
 *   WhitelistConfigDTO$Function   ~135 ints, one per feature.  The one that
 *                                 matters here is brLeCoexist.
 *   r7.b  (FeatureOptions)        the two global gates, read from system props:
 *                                 persist.sys.oplus.bt.le_audio_white_list_enable
 *                                 persist.sys.bt.leacoexist
 * ```
 *
 * `J6.b#f(address)` -- "does this device do the BR/LE switch?" -- is exactly
 * `FeatureOptions.d() && whitelist(address).function.brLeCoexist == 1`, and
 * `FeatureOptions.d()` is the two properties above.  So the answer for any device
 * is a whitelist lookup, and the AZ100 is simply absent from it.
 *
 * The whitelist itself is server-pushed (`WhitelistRepository.refreshWhitelist` ->
 * `model/repository/whitelist/outside_snapshot.json`), matched by device name
 * (exact, or prefix when `fuzzyMatchName`) and by product id, and re-exported to
 * other apps through
 * `content://com.oplus.melody.providers.MelodyProvider/find_whitelist?macAddress=...`
 * (see `MelodyAliveProvider`, codes 1/2/8/9).  A miss returns a null cursor, i.e.
 * "unknown device", and the consumer hides its LE Audio switch.
 *
 * ## What this hook does
 *
 * Two things, both narrow:
 *
 * 1. forces the four `r7.b` flags that gate LE Audio on (`i` = le_audio, `j` =
 *    le_audio_over_spp, `q` = white_list_enable, `r` = leacoexist);
 * 2. answers the whitelist lookup with a synthetic entry for the AZ100 that has
 *    exactly one capability set: `function.brLeCoExist = 1`.  Every other feature
 *    stays 0, so nothing else about the device changes.
 *
 * Only the two static resolvers are hooked -- `WhitelistUtils.a(productId, name)`
 * and `WhitelistUtils.b(device, list)`.  Both the in-process callers (`J6.b#f`)
 * and the exported provider (`c9.a#a` -> `WhitelistRepository.a` -> `c(...)` ->
 * `T.a`) funnel through them, so the whole app sees the same answer without
 * touching the cached list.
 *
 * ## Measured result (2026-10-11, OnePlus PLJ110 / ColorOS)
 *
 * **This hook works, but it is not what gates the LE Audio switch in Settings.**
 *
 * Root `content query` on `.../find_whitelist?macAddress=<AZ100>` does return the
 * synthesized entry with `"brLeCoExist":1`.  However the persistent trace shows the
 * only consumers of Melody's whitelist are `com.android.bluetooth` and
 * `com.oplus.exsystemservice`, and both read `ears_whitelist` (the 93-row outside
 * snapshot, which has no capability bits) -- never `find_whitelist`.
 * `com.android.settings` never queries Melody at all.
 *
 * The real gate lives in the Bluetooth stack: see
 * `PhonePolicy#processInitProfilePriorities` and `AdapterService#isLeAudioAllowed`
 * in `Bluetooth.apk`.  It needs the device's SDP to carry `0000184e` (LE Audio),
 * plus the `persist.bluetooth.enable_dual_mode_audio` /
 * `persist.bluetooth.leaudio.bypass_allow_list` properties.
 *
 * The four `persist.sys.oplus.bt.le_audio*` properties this hook forces were
 * already `true` on the device, so [hookFeatureOptions] is redundant there.
 * Everything is kept because it is harmless and keeps Melody's own BR/LE-coexist
 * view consistent once LE Audio actually runs.
 */
object LeAudioWhitelistHook {

    /** Flip to false and reinstall to leave Melody exactly as it was. */
    private const val ENABLED = true

    private const val WHITELIST_DTO = "com.oplus.melody.common.data.WhitelistConfigDTO"
    private const val WHITELIST_FUNCTION = "$WHITELIST_DTO\$Function"
    private const val WHITELIST_UTILS = "com.oplus.melody.common.util.T"
    private const val FEATURE_OPTIONS = "r7.b"
    private const val ALIVE_PROVIDER = "com.oplus.melody.alive.provider.MelodyAliveProvider"

    /** Product id used when Melody has no DeviceInfoManager row for the device. */
    private const val FALLBACK_PRODUCT_ID = "AZ100"

    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var loader: ClassLoader? = null

    /**
     * One synthesised entry per key, reused instead of rebuilt.
     *
     * Measured 2026-10-13: on a single connect, Melody resolves the same mac 59
     * times in six seconds (and the same name 8 times), and every miss used to
     * allocate a fresh `WhitelistConfigDTO` plus a `WhitelistConfigDTO$Function`
     * through reflection and append a line to the external-storage trace.  The
     * entry is immutable from the caller's side -- Melody only reads
     * `function.brLeCoExist` -- so one instance per key is enough.
     *
     * Keyed by name or mac, so the two resolvers share the cache.  Unbounded growth
     * is not a concern here: the keys are the headset's two addresses and one
     * product name.
     */
    private val ENTRIES = ConcurrentHashMap<String, Any>()

    /** One trace line per key, so the cache hit path stays silent. */
    private val REPORTED = ConcurrentHashMap.newKeySet<String>()

    fun install(param: PackageParam) {
        if (!ENABLED) return
        val classLoader = param.appClassLoader ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
            loader = classLoader
        }
        hookWhitelistUtils(param)
        hookFeatureOptions(param)
        hookAliveProvider(param)
    }

    /* ------------------------------------------------------------------ */
    /* the two resolvers                                                   */
    /* ------------------------------------------------------------------ */

    private fun hookWhitelistUtils(param: PackageParam) {
        val classLoader = loader ?: return
        try {
            val utils = Reflect.findClass(WHITELIST_UTILS, classLoader)
            with(param) {
                // a(Collection<WhitelistConfigDTO> all, String productId, String deviceName)
                utils.resolve().optional(silent = true).firstMethod {
                    name = "a"
                    parameters(Collection::class.java, String::class.java, String::class.java)
                }.hook {
                    after {
                        if (result != null) return@after
                        val productId = args[1] as? String
                        val name = args[2] as? String
                        if (!isAz100Name(name)) return@after
                        result = entryCached("n:$name", productId, name)
                    }
                }
                // b(BluetoothDevice device, Collection<WhitelistConfigDTO> all)
                utils.resolve().optional(silent = true).firstMethod {
                    name = "b"
                    parameters(BluetoothDevice::class.java, Collection::class.java)
                }.hook {
                    after {
                        if (result != null) return@after
                        val device = args[0] as? BluetoothDevice ?: return@after
                        val address = device.address
                        if (!Az100Hook.isAz100Mac(address)) return@after
                        val name = device.name
                        result = entryCached(
                            "m:$address", null,
                            if (name.isNullOrEmpty()) Az100Hook.AZ100_NAME else name
                        )
                    }
                }
            }
            Logs.trace("le whitelist: resolvers hooked")
        } catch (t: Throwable) {
            Logs.e("le whitelist: resolver hook failed", t)
        }
    }

    /**
     * The synthetic entry for [key], built once.
     *
     * Two threads can race here; both may build an entry, and the one that loses the
     * `putIfAbsent` is discarded.  That is harmless -- the objects are equal in
     * every field Melody reads -- and it keeps the hot path free of a lock.
     */
    private fun entryCached(key: String, productId: String?, name: String?): Any {
        ENTRIES[key]?.let { return it }
        val built = entry(productId, name)
        val result = ENTRIES.putIfAbsent(key, built) ?: built
        if (REPORTED.add(key)) {
            Logs.trace("le whitelist: synthesized ${maskKey(key)} (cached for later hits)")
        }
        return result
    }

    /** Redact the address inside a cache key (`m:MAC` / `n:NAME`). */
    private fun maskKey(key: String?): String {
        if (key == null) return "?"
        if (key.startsWith("m:")) return "m:" + mask(key.substring(2))
        return key
    }

    /**
     * The synthetic entry.  Everything except `brLeCoexist` stays at the class
     * default (0 = unsupported) so no other Melody feature turns itself on for a
     * headset it still does not really know.
     */
    private fun entry(productId: String?, name: String?): Any {
        val classLoader = loader
        val dto = Reflect.newInstance(Reflect.findClass(WHITELIST_DTO, classLoader))
        val function =
            Reflect.newInstance(Reflect.findClass(WHITELIST_FUNCTION, classLoader))
        Reflect.callMethod(function, "setBrLeCoexist", 1)
        Reflect.callMethod(dto, "setFunction", function)
        Reflect.callMethod(
            dto, "setId",
            if (productId.isNullOrEmpty()) FALLBACK_PRODUCT_ID else productId
        )
        Reflect.callMethod(
            dto, "setName",
            if (name.isNullOrEmpty()) Az100Hook.AZ100_NAME else name
        )
        Reflect.callMethod(dto, "setFuzzyMatchName", false)
        return dto
    }

    /* ------------------------------------------------------------------ */
    /* the global gates                                                    */
    /* ------------------------------------------------------------------ */

    /** The four system properties that gate the whole feature, in read order. */
    private val GATES = arrayOf(
        "persist.sys.oplus.bt.le_audio",                   // -> field i
        "persist.sys.oplus.bt.le_audio_over_spp",          // -> field j
        "persist.sys.oplus.bt.le_audio_white_list_enable", // -> field q
        "persist.sys.bt.leacoexist",                       // -> field r
    )

    /**
     * Two hooks, because either alone leaves a hole:
     *
     * 1. `a(String)` is `getBooleanProperty`, the only reader of those four
     *    properties.  Answering `true` here means the flags come out right no
     *    matter *when* `c()` runs -- and in particular if it already ran before
     *    this module was loaded.
     * 2. `c(MelodyApplication)` is the initializer.  Forcing the four fields right
     *    after it covers the opposite ordering.
     *
     * Neither hook touches any other property or field, so nothing else about
     * Melody's feature detection changes.
     */
    private fun hookFeatureOptions(param: PackageParam) {
        val classLoader = loader ?: return
        try {
            val options = Reflect.findClass(FEATURE_OPTIONS, classLoader)

            with(param) {
                // public static boolean a(String property)
                options.resolve().optional(silent = true).firstMethod {
                    name = "a"
                    parameters(String::class.java)
                }.hook {
                    before {
                        val property = args[0] as? String
                        if (!isGate(property)) return@before
                        result = true
                        Logs.trace("le whitelist: gate $property -> true")
                    }
                }

                // public static void c(MelodyApplication)
                options.resolve().optional(silent = true).firstMethod {
                    name = "c"
                    parameters(Reflect.findClass("com.oplus.melody.MelodyApplication", classLoader))
                }.hook {
                    after {
                        force(options, "i", GATES[0])
                        force(options, "j", GATES[1])
                        force(options, "q", GATES[2])
                        force(options, "r", GATES[3])
                    }
                }
            }

            Logs.trace("le whitelist: feature options hooked")
        } catch (t: Throwable) {
            Logs.e("le whitelist: feature option hook failed", t)
        }
    }

    private fun isGate(property: String?): Boolean {
        if (property == null) return false
        return GATES.any { it == property }
    }

    private fun force(options: Class<*>, field: String, property: String) {
        try {
            Reflect.setStaticBooleanField(options, field, true)
            Logs.trace("le whitelist: forced $property = true")
        } catch (t: Throwable) {
            Logs.d("le whitelist: cannot force $field", t)
        }
    }

    /* ------------------------------------------------------------------ */
    /* the exported provider (diagnostics)                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Melody re-exports the whitelist to other apps through
     * `content://com.oplus.melody.providers.MelodyProvider`.  Nothing is patched
     * here -- the resolvers above already answer that path -- but every query is
     * logged with its caller, which is the only way to find out whether the Settings
     * app is really the consumer.
     */
    private fun hookAliveProvider(param: PackageParam) {
        val classLoader = loader ?: return
        try {
            val provider = Reflect.findClass(ALIVE_PROVIDER, classLoader)
            with(param) {
                provider.resolve().optional(silent = true).firstMethod {
                    name = "query"
                    parameters(
                        Uri::class.java, Array<String>::class.java, String::class.java,
                        Array<String>::class.java, String::class.java
                    )
                }.hook {
                    after {
                        try {
                            val uri = args[0] as? Uri
                            val path = uri?.path
                            if (path == null || !path.contains("whitelist")) return@after
                            val caller = (instanceOrNull as? ContentProvider)?.callingPackage
                            val mac = uri.getQueryParameter("macAddress")
                            val cursor = result as? Cursor
                            Logs.trace(
                                "le whitelist: ${path.substring(1)}"
                                        + " from $caller"
                                        + (if (mac == null) "" else " mac=${mask(mac)}")
                                        + " -> " + (if (cursor == null) "null" else "${cursor.count} row(s)")
                            )
                        } catch (t: Throwable) {
                            Logs.d("le whitelist: provider log failed", t)
                        }
                    }
                }
            }
            Logs.trace("le whitelist: alive provider hooked")
        } catch (t: Throwable) {
            Logs.e("le whitelist: alive provider hook failed", t)
        }
    }

    /* ------------------------------------------------------------------ */

    private fun isAz100Name(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val upper = name.uppercase(Locale.ROOT)
        return upper.contains("AZ100") || upper.contains("AZ-100")
                || upper.contains("EAH-") || upper.contains("TECHNICS")
    }

    /** Never log a full address: the module's own traces stay redacted. */
    private fun mask(address: String?): String {
        if (address == null || address.length < 5) return "?"
        return "**:**:**" + address.substring(address.length - 5)
    }
}
