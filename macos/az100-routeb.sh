#!/bin/bash
#
# Route B console: drive the AZ100 bond from adb, no taps on the phone.
#
# The module (com.tosasitill.az100, scoped to com.android.bluetooth) polls
# debug.az100.action every 2s and runs whatever it finds there through the
# ordinary BluetoothDevice API inside the Bluetooth process.  Output lands in
# /data/misc/bluetooth/az100_console.log.
#
# Quoting matters: adb hands the whole command to the device shell, so the
# value has to be quoted *for the device*, not for this script.  Unquoted,
# `setprop debug.az100.action unpair B8:20:8E:EE:84:85` reaches setprop as
# three arguments and silently does nothing but print usage.
#
#   ./az100-routeb.sh act "status"
#   ./az100-routeb.sh unpair
#   ./az100-routeb.sh autobond        # LE bond；确认弹窗已被模块绕过
#   ./az100-routeb.sh bond-le         # LE bond
#   ./az100-routeb.sh log 40
#   ./az100-routeb.sh dump
#
# `autobond` is the one to use.  Two things have to hold for it to produce a
# bond, and both are now understood:
#
#   1. The bond must be forced onto LE -- that is FORCE_LE_TRANSPORT in the
#      module, and the native log shows `btif_dm_create_bond: ... transport=2`.
#   2. The LE "Just Works" consent must not be raised at all.  Answering it is
#      impossible on this build (btif_dm_ssp_reply branches on pairing_cb.is_le_only,
#      which is never set), and the Settings dialog's own button goes through the
#      same broken call -- so a manual tap fails too.  The module instead
#      suppresses the prompt entirely via BYPASS_CONSENT.
#
# The remaining prerequisite is on the headset, not the phone: the AZ100 has to
# be in pairing mode, otherwise the LE create-connection just times out after
# 30 s (`on_create_connection_timeout`, reason=8) and no SMP ever runs.

set -u

MAC="${AZ100_MAC:-B8:20:8E:EE:84:85}"
PROP="debug.az100.action"
CONSOLE="/data/misc/bluetooth/az100_console.log"
HOOKLOG="/data/misc/bluetooth/az100.log"

act() {
    local action="$1"
    local wait="${2:-6}"
    # Quote for the device shell; `none` is the module's idle sentinel.
    #
    # The idle write has to be held longer than the module's 2s poll.  The
    # console runs each action string only once (`if (action.equals(lastAction))
    # return;`) and only resets that memory when it actually observes the idle
    # sentinel -- so a 0.3s window can be missed entirely, leaving the next
    # identical action silently dropped.  That is exactly what made a retry
    # loop look like it ran when it did not.
    adb shell "setprop $PROP 'none'" >/dev/null 2>&1
    sleep 3
    adb shell "setprop $PROP '$action'" >/dev/null 2>&1
    echo "-- sent <$action>, waiting ${wait}s"
    sleep "$wait"
    adb shell "setprop $PROP 'none'" >/dev/null 2>&1
    adb shell su -c "tail -n 25 $CONSOLE" 2>&1 | tail -n 14
}

log() {
    adb shell su -c "tail -n ${1:-40} $CONSOLE" 2>&1
}

# The device has no usable shell grep here, so pull and scan on the host.
dump() {
    adb shell dumpsys bluetooth_manager > /tmp/az100-dumpsys.txt 2>/dev/null
    adb shell su -c "cat $HOOKLOG" > /tmp/az100-hooklog.txt 2>&1
    adb shell su -c "cat /data/misc/bluedroid/bt_config.conf" > /tmp/az100-btconfig.txt 2>&1
    python3 - <<'EOF'
import re
d = open('/tmp/az100-dumpsys.txt', encoding='utf-8', errors='replace').read()
print('== bonded ==')
m = re.search(r'Bonded devices:\s*(\d+)', d)
print('Bonded devices:', m.group(1) if m else '?')
for line in d.splitlines():
    if re.match(r'\s*[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:.*', line) and (
            'AZ100' in line or '[ LE' in line or '[ DUAL' in line):
        print('   ', line.strip())
print('== 184e ==')
print('occurrences:', d.lower().count('184e'))
print('[LE UUIDs] lines:', d.count('[LE UUIDs'))
print('== BR/EDR UUIDs ==')
for line in d.splitlines():
    if '[BR/EDR UUIDs]' in line or '[LE UUIDs' in line:
        print('   ', line.strip())
print('== LeAudio ==')
hits = [l.strip() for l in d.splitlines()
        if re.search(r'le_audio|LeAudio|leaudio', l, re.I)]
print('\n'.join('    ' + h for h in hits[:12]) or '    (none)')
print('== bt_config.conf (AZ100 section) ==')
try:
    c = open('/tmp/az100-btconfig.txt', encoding='utf-8', errors='replace').read()
except OSError:
    c = ''
secs = re.split(r'(?m)^(?=\[)', c)
found = False
for sec in secs:
    head = sec.splitlines()[0] if sec.splitlines() else ''
    if '84:85' in head.lower() or '84:85' in sec.lower():
        print('   ', head.strip())
        for l in sec.splitlines()[1:]:
            if l.strip():
                print('      ', l.strip())
        found = True
if not found:
    print('    (no AZ100 section yet)')
print('    Service lines with 184e:', len(re.findall(r'(?im)^Service\s*=.*184e', c)))
print('== hook log (bond/pair lines) ==')
h = open('/tmp/az100-hooklog.txt', encoding='utf-8', errors='replace').read().splitlines()
sel = [l for l in h if re.search(r'bond:|pair:|auto-confirm|action |result |consent:', l)]
print('\n'.join('    ' + s for s in sel[-25:]) or '    (none)')
EOF
}

case "${1:-dump}" in
    act) act "${2:?usage: act \"<action>\" [wait]}" "${3:-6}" ;;
    log) log "${2:-40}" ;;
    unpair) act "unpair $MAC" 8 ;;
    autobond) act "autobond $MAC 2" 25 ;;
    sweep) act "sweep $MAC 2" 40 ;;
    bond-le) act "bond $MAC 2" 20 ;;
    bond-auto) act "bond $MAC 0" 20 ;;
    confirm) act "confirm $MAC" 6 ;;
    status) act "status" 4 ;;
    dump) dump ;;
    *) echo "usage: $0 {act \"<action>\" [wait]|log [n]|unpair|autobond|sweep|bond-le|bond-auto|confirm|status|dump}" ;;
esac
