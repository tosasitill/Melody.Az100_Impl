package com.tosasitill.az100

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

/** The modern entry is the only module entry in this APK. */
class ModernXposedEntry : XposedModule() {
    private var processName = ""
    private val installedPackages = mutableSetOf<String>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        Logs.attach(this)
        RuntimeEnvironment.moduleApkPath = moduleApplicationInfo.sourceDir
        if (processName == Az100Hook.BLUETOOTH) Logs.traceToFile(File("/data/misc/bluetooth"))
        Logs.i("module ${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} loaded via libxposed API $apiVersion in $processName")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        if (packageName != Az100Hook.BLUETOOTH && packageName != Az100Hook.MELODY) return
        synchronized(installedPackages) {
            if (packageName in installedPackages) return
        }
        val runtime = HookRuntime(this, param.classLoader)
        try {
            Prefs.attach(getRemotePreferences(Prefs.FILE))
            // Application.attach runs before providers and Application.onCreate.
            // This also captures a Context for native loading and the SPP worker.
            with(runtime) {
                Application::class.java.getDeclaredMethod("attach", Context::class.java).hook {
                    after {
                        val application = instanceOrNull as? Application ?: return@after
                        RuntimeEnvironment.application = application
                        if (packageName == Az100Hook.MELODY) {
                            try {
                                MelodyProviderHook.install(runtime, application)
                                Logs.i("Melody registered ${runtime.hookCount} hooks in $processName")
                            } catch (t: Throwable) {
                                Logs.e("Melody hook install failed", t)
                            }
                        }
                    }
                }
            }
            if (packageName == Az100Hook.BLUETOOTH) {
                LeAudioTransportHook.install(runtime)
                Logs.i("Bluetooth registered ${runtime.hookCount} hooks in $processName")
            }
            // Recorded only once the install actually returned.  Marking it done
            // up front meant a failure anywhere in this block left the package
            // permanently uninstalled for the life of the process: the retry that
            // `onPackageReady` would otherwise perform was refused by the very
            // set that was supposed to make it idempotent.
            synchronized(installedPackages) { installedPackages.add(packageName) }
        } catch (t: Throwable) {
            Logs.e("modern entry failed for $packageName", t)
            log(Log.ERROR, Logs.TAG, "modern entry failed for $packageName", t)
        }
    }
}

internal object RuntimeEnvironment {
    @Volatile var application: Application? = null
    @Volatile var moduleApkPath: String? = null
}
