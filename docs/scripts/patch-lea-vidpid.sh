#!/bin/sh
# ---------------------------------------------------------------------------
# Make the AZ100 look like an OPPO LE Audio device at the *native* layer.
#
# The native stack answers "is this one of our own LE Audio devices?" by
# reading two keys out of the device's own section in bt_config.conf:
#
#     broadcastVendorId   (uint16)
#     broadcastProductId  (uint32, only the low 3 bytes are compared)
#
# and matching them against the 8 OPPO product ids compiled into
# libbluetooth_jni.so -- all under company id 0x079A, registered under the
# interop feature name "OplusLeAudioWhiteList".  Nothing in the stack writes
# those two keys for a third-party headset: they are normally populated from
# OPPO's private LE broadcast payload, which the AZ100 does not send.  Without
# them the AZ100 is never treated as a native LE Audio device, whatever the
# Java layer believes.
#
# Readers, all in libbluetooth_jni.so (file offsets == vaddrs, so these are
# directly usable in a disassembler):
#
#   oplus_btif_is_lea_device                 0x643180   <-- the main gate
#   oplus_bta_dm_check_lea_whitelist         0x67b6b0
#   oplus_handle_le_audio_lea_bit_processing 0x65ba40
#   oplus_btm_process_inq_results            0x76bcf0
#   oplus_btm_ble_process_adv_pkt_cont       0x76a620
#   oplus_btm_sec_retry_le_encrypt_if_needed 0x784bb0
#   oplus_btm_sec_encrypt_change             0x788950
#   oplus_btif_set_remote_deivce_class       0x643f40
#
# Each of them does, in effect:
#
#     if (!btif_config_get_int(addr, "broadcastVendorId",  &vendor))  return false;
#     if (!btif_config_get_int(addr, "broadcastProductId", &product)) return false;
#     return interop_match_vendor_and_3bytes_product_id(
#                "OplusLeAudioWhiteList", vendor, product);
#
# Worth knowing: the *other* native check, the one inside
# interop_match_by_feature_name_impl (0x1112e40), is dead code.  Its only
# caller -- stack_connection_complete_event_report (0x6b02e0) -- passes
# "INTEROP_DCS_REMOTE_VIP_DEVICE", not "OplusLeAudioWhiteList", so the
# vendor/product branch at 0x1112fc0 is never reached.  That path writes
# [UserIdentity] UserType = VipUser and is an unrelated feature.
#
# Ordering matters: btif_config holds a process-wide in-memory cache that is
# populated once per process lifetime and flushed back to the file, so editing
# the file under a live process is silently undone.  The device-side script
# therefore disables the adapter, kills the process, waits for it to be gone,
# edits, and only then re-enables.
#
# Usage:
#   ./patch-lea-vidpid.sh            patch (briefly interrupts Bluetooth)
#   ./patch-lea-vidpid.sh check      read-only: show current state
#   ./patch-lea-vidpid.sh revert     restore the newest backup
#   ./patch-lea-vidpid.sh revert F   restore a specific backup
# ---------------------------------------------------------------------------

set -e

CONF=/data/misc/bluedroid/bt_config.conf
ADDR="${ADDR:-b8:20:8e:ee:84:85}"
DEVICE_SCRIPT="$(dirname "$0")/patch-lea-vidpid.device.sh"
REMOTE=/data/local/tmp/lea-vidpid.sh

asroot() { adb shell "su -c '$1'"; }

show() {
    echo "--- $CONF  [$ADDR] ---"
    asroot "sed -n \"/^\\[$ADDR\\]/,/^\\[/p\" $CONF" | sed '$d' |
        grep -E 'Name|DevClass|DevType|AddrType|broadcast|LeAudio|customLea' || true
    echo "--- backups ---"
    asroot "ls -1 ${CONF}.bak.* 2>/dev/null || echo none"
}

case "${1:-patch}" in

check)
    show
    exit 0
    ;;

revert)
    if [ -n "$2" ]; then
        BAK="$2"
    else
        BAK="$(asroot "ls -1t ${CONF}.bak.* 2>/dev/null | head -1" | tr -d '\r')"
    fi
    [ -n "$BAK" ] || { echo "no backup found" >&2; exit 1; }
    echo "==> restoring $BAK"
    asroot "svc bluetooth disable" >/dev/null 2>&1 || true
    sleep 3
    asroot "kill -9 \$(pidof com.android.bluetooth) 2>/dev/null; sleep 1"
    asroot "cp -a $BAK $CONF && chown bluetooth:bluetooth $CONF && chmod 660 $CONF"
    asroot "svc bluetooth enable" >/dev/null 2>&1 || true
    sleep 10
    show
    exit 0
    ;;

patch)
    [ -f "$DEVICE_SCRIPT" ] || { echo "missing $DEVICE_SCRIPT" >&2; exit 1; }
    adb push "$DEVICE_SCRIPT" "$REMOTE" >/dev/null
    asroot "sh $REMOTE"
    echo
    echo "Done.  Expect a short Bluetooth interruption.  Then:"
    echo "    adb logcat -s oplus_bt_btif_dm | grep 'Is oplus LEA device'"
    ;;

*)
    echo "usage: $0 [patch|check|revert [backup]]" >&2
    exit 2
    ;;
esac
