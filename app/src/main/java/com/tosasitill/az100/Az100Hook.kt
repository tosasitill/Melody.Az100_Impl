package com.tosasitill.az100

/**
 * Volume-panel adapter for the Technics EAH-AZ100.
 *
 * The panel lives in SystemUI but reads everything from Melody's
 * EarphoneControlProvider.  This module is therefore scoped to
 * `com.oplus.melody` only: it answers that provider and drives the headset
 * through [DirectAirohaController].  SystemUI and Device Space are not injected.
 *
 * The one exception is `com.android.bluetooth`, which [LeAudioTransportHook]
 * needs in order to force the AZ100's bond over LE -- see that class for why.  It
 * is a self-contained experiment: turning its `ENABLED` flag off leaves the
 * Bluetooth process untouched and this object back to Melody-only.
 */
object Az100Hook {

    /** This module's own package, used by [Prefs] to find its prefs file. */
    const val MODULE_PACKAGE = "com.tosasitill.az100"

    const val MELODY = "com.oplus.melody"
    const val BLUETOOTH = "com.android.bluetooth"

    const val AZ100_MAC = "B8:20:8E:EE:84:85"

    /**
     * The other half of the set.  The AZ100 presents two LE identities, both
     * named `Technics EAH-AZ100`, both with CoD `0x240404`, both under the same
     * OUI:
     *
     * ```
     * B8:20:8E:EE:84:85   [ DUAL ]  bonded, exposes the full LE Audio GATT family
     * B8:20:8E:EE:3C:62   [ LE   ]  the second earbud, never bonded here
     * ```
     *
     * `84:85` declares `Audio Locations: Sink= left` only, so the group it forms
     * on its own can only ever be mono -- see [LeAudioTransportHook].  The second
     * earbud has to be treated the same way, or the pair can never form a
     * two-device group.
     */
    const val AZ100_LE_MAC = "B8:20:8E:EE:3C:62"

    const val AZ100_NAME = "Technics EAH-AZ100"

    fun isAz100Mac(address: String?): Boolean =
        address != null && AZ100_MAC.equals(address, ignoreCase = true)

    /** Headset reported a mode.  Only the volume tile consumes it. */
    fun onAirohaMode(address: String?, status: Int) {
        if (!isAz100Mac(address)) return
        MelodyProviderHook.onHeadsetMode(status)
    }

    /** Battery samples are cached by the controller; the tile does not show them. */
    fun onAirohaBattery(address: String?, values: IntArray?) {
        if (!isAz100Mac(address) || values == null) return
        MelodyProviderHook.onHeadsetBattery()
    }
}
