#!/system/bin/sh
# Fix 2: open the LE Audio gate in PhonePolicy.processInitProfilePriorities().
#
# PhonePolicy.java:386 reads
#     persist.bluetooth.leaudio.bypass_allow_list   -> boolean z
# and line 389 accepts the device when
#     z || shouldEnableHapByDefault || isLeAudioAllowed(dev) || isLeAudioOnlyDevice
# The other three are all false for the AZ100 on this ROM:
#   - shouldEnableHapByDefault needs BluetoothUuid.HAS
#   - isLeAudioAllowed compares DeviceProperties.getModelName() against an
#     allowlist that is empty (DeviceConfig bluetooth/le_audio_allow_list = null,
#     persist.bluetooth.leaudio.allow_list_extend unset), and getModelName() is
#     never populated on this build
#   - isLeAudioOnlyDevice needs getRemoteType()==2, the AZ100 is DUAL
# so bypass is the only lever that actually works.
#
# Also records the prior value so the change is reversible.

PROP=persist.bluetooth.leaudio.bypass_allow_list
OLD=$(getprop $PROP)

echo "== before =="
echo "$PROP = [$OLD]"

setprop $PROP true
sleep 1

NEW=$(getprop $PROP)
echo "== after =="
echo "$PROP = [$NEW]"

if [ "$NEW" != "true" ]; then
    echo "!! setprop failed"
    exit 1
fi

echo "== restarting bluetooth =="
svc bluetooth disable
sleep 4
i=0
while [ $i -lt 20 ]; do
    P=$(pidof com.android.bluetooth 2>/dev/null)
    [ -z "$P" ] && break
    kill -9 $P 2>/dev/null
    sleep 1
    i=$((i + 1))
done
svc bluetooth enable
sleep 12

echo "== done =="
echo "pid=$(pidof com.android.bluetooth)"
echo "bluetooth_on=$(settings get global bluetooth_on)"
