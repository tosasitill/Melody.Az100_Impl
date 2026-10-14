package com.tosasitill.az100

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

/**
 * YukiHookAPI entry and process dispatch.
 *
 * `com.android.bluetooth` gets the LE Audio path. `com.oplus.melody` gets the
 * volume tile.  Nothing else is loaded, and neither process is ever reached
 * from the other's hooks.
 */
@InjectYukiHookWithXposed
object Az100Entry : IYukiHookXposedInit {

    /**
     * Verbose LSPosed logging.  [Logs.trace] still mirrors into the Bluetooth
     * process's own file regardless, because logd drops messages from it.
     */
    private const val DEBUG_LOG = false

    override fun onInit() = configs {
        debugLog {
            tag = Logs.TRACE_TAG
            isEnable = DEBUG_LOG
        }
    }

    override fun onHook() = encase {
        loadApp(Az100Hook.BLUETOOTH) {
            try {
                LeAudioTransportHook.install(this)
            } catch (t: Throwable) {
                Logs.e("Bluetooth hook failed", t)
            }
        }
        loadApp(Az100Hook.MELODY) {
            try {
                // `onCreate`'s receiver is the Application itself, so this is the
                // same moment the old `Application#attach` hook fired at, without
                // touching a framework method the ROM also hooks.
                onAppLifecycle {
                    onCreate {
                        try {
                            MelodyProviderHook.install(this@loadApp, this)
                        } catch (t: Throwable) {
                            Logs.e("Melody hook failed", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Logs.e("Melody hook failed", t)
            }
        }
    }
}
