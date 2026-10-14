package com.tosasitill.az100

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Private settings in the module app, published to the framework's remote prefs. */
object Prefs {
    const val FILE = "az100"
    const val KEY_AUTO_LEA = "auto_lea"
    const val KEY_VOLUME_FIX = "volume_fix"
    const val KEY_TWIN = "twin_auto_connect"
    const val KEY_DIAGNOSE = "diagnose"

    const val DEFAULT_AUTO_LEA = true
    const val DEFAULT_VOLUME_FIX = true
    const val DEFAULT_TWIN = true
    const val DEFAULT_DIAGNOSE = false

    private val defaults = linkedMapOf(
        KEY_AUTO_LEA to DEFAULT_AUTO_LEA,
        KEY_VOLUME_FIX to DEFAULT_VOLUME_FIX,
        KEY_TWIN to DEFAULT_TWIN,
        KEY_DIAGNOSE to DEFAULT_DIAGNOSE
    )
    private val lock = Any()
    private var listenerRegistered = false
    private var local: SharedPreferences? = null
    @Volatile private var writableRemote: SharedPreferences? = null
    @Volatile private var hookedRemote: SharedPreferences? = null
    @Volatile var frameworkConnected = false
        private set

    fun initialize(context: Context) {
        synchronized(lock) {
            if (listenerRegistered) return
            local = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            listenerRegistered = true
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    synchronized(lock) {
                        try {
                            val remote = service.getRemotePreferences(FILE)
                            val prefs = local ?: return
                            // Reuse framework settings if the private file is new.
                            val editor = prefs.edit()
                            for ((key, fallback) in defaults) {
                                if (!prefs.contains(key) && remote.contains(key)) {
                                    editor.putBoolean(key, remote.getBoolean(key, fallback))
                                }
                            }
                            if (!editor.commit()) throw IllegalStateException("Cannot import settings")
                            writableRemote = remote
                            frameworkConnected = publish()
                        } catch (t: Throwable) {
                            writableRemote = null
                            frameworkConnected = false
                            Logs.e("config: cannot connect to framework preferences", t)
                        }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    synchronized(lock) {
                        writableRemote = null
                        frameworkConnected = false
                    }
                }
            })
        }
    }

    fun open(context: Context): SharedPreferences {
        initialize(context.applicationContext ?: context)
        return checkNotNull(local)
    }

    /** A successful save confirms both private storage and framework publication. */
    fun save(context: Context, key: String, value: Boolean): Boolean {
        if (!isKnownKey(key)) return false
        val prefs = open(context)
        synchronized(lock) {
            if (!prefs.edit().putBoolean(key, value).commit()) return false
            return publish()
        }
    }

    private fun publish(): Boolean {
        val prefs = local ?: return false
        val remote = writableRemote ?: return false
        return try {
            val editor = remote.edit()
            for ((key, fallback) in defaults) editor.putBoolean(key, prefs.getBoolean(key, fallback))
            editor.commit()
        } catch (t: Throwable) {
            Logs.e("config: remote write failed", t)
            false
        }
    }

    /** Remote prefs update through LSPosed without binding to the module app. */
    fun attach(prefs: SharedPreferences) {
        hookedRemote = prefs
    }

    val autoLea: Boolean get() = getBoolean(KEY_AUTO_LEA, DEFAULT_AUTO_LEA)
    val volumeFix: Boolean get() = getBoolean(KEY_VOLUME_FIX, DEFAULT_VOLUME_FIX)
    val twin: Boolean get() = getBoolean(KEY_TWIN, DEFAULT_TWIN)
    val diagnose: Boolean get() = getBoolean(KEY_DIAGNOSE, DEFAULT_DIAGNOSE)

    private fun getBoolean(key: String, fallback: Boolean): Boolean = try {
        hookedRemote?.getBoolean(key, fallback) ?: fallback
    } catch (t: Throwable) {
        fallback
    }

    fun isKnownKey(key: String?): Boolean = key != null && defaults.containsKey(key)
}
