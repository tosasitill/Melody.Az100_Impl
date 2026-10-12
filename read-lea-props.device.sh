#!/system/bin/sh
# Re-read the LE Audio related properties as root.
#
# NOTE: plain `adb shell getprop <name>` runs in the shell domain, and several
# of these are behind property_contexts read restrictions -- it returns an
# empty string even when the property is set.  Always read them as root.

for p in \
    persist.bluetooth.leaudio.bypass_allow_list \
    persist.bluetooth.leaudio.allow_list_extend \
    persist.bluetooth.enable_dual_mode_audio \
    ro.bluetooth.leaudio.le_audio_connection_by_default \
    bluetooth.profile.hap.enabled_by_default \
    persist.sys.oplus.bt.le_audio \
    persist.sys.oplus.bt.le_audio_over_spp \
    persist.sys.oplus.bt.le_audio_white_list_enable \
    persist.sys.bt.leacoexist
do
    printf '%-58s = [%s]\n' "$p" "$(getprop $p)"
done

echo
echo "== DeviceConfig bluetooth/le_audio_allow_list =="
device_config get bluetooth le_audio_allow_list
