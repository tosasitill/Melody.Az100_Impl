#!/system/bin/sh
# ---------------------------------------------------------------------------
# Undo the failed configuration route, completely.
#
# Removes:
#   * broadcastVendorId / broadcastProductId from bt_config.conf
#     (they are read by the native gate, but the gate also demands
#      customLeaBitWritten == 1 -- which nothing in the stack ever writes --
#      and a vendor/product pair from a hard-coded .rodata table that does not
#      contain the AZ100.  So the keys were inert.)
#   * the [OplusLeAudioWhiteList] address entry the module wrote into
#     bt_iot_update_list.conf
#     (it matches by address, while the gate matches by vendor/product)
#
# Both files are cached in memory for the lifetime of the Bluetooth process, so
# the adapter must be stopped and the process killed before editing -- same
# dance as the patch script.  Backups are taken first.
# ---------------------------------------------------------------------------

CONF=/data/misc/bluedroid/bt_config.conf
IOT=/data/misc/bluedroid/bt_iot_update_list.conf
STAMP=$(date +%Y%m%d-%H%M%S)

cp -a "$CONF" "$CONF.bak.$STAMP" || exit 1
[ -f "$IOT" ] && cp -a "$IOT" "$IOT.bak.$STAMP"

echo "==> disabling adapter"
svc bluetooth disable
sleep 3

i=0
while [ $i -lt 20 ]; do
    P=$(pidof com.android.bluetooth 2>/dev/null)
    [ -z "$P" ] && break
    kill -9 $P 2>/dev/null
    sleep 1
    i=$((i + 1))
done

P=$(pidof com.android.bluetooth 2>/dev/null)
if [ -n "$P" ]; then
    echo "!! bluetooth still alive (pid $P) -- aborting, nothing was edited"
    exit 1
fi
echo "==> process gone"

echo "==> removing broadcast* keys"
sed -i '/^broadcastVendorId[[:space:]]*=/d; /^broadcastProductId[[:space:]]*=/d' "$CONF"

if [ -f "$IOT" ]; then
    echo "==> removing [OplusLeAudioWhiteList] entry"
    sed -i '/^\[OplusLeAudioWhiteList\]$/,/^AddressBlacklist/d' "$IOT"
fi

chown bluetooth:bluetooth "$CONF" 2>/dev/null
chmod 660 "$CONF" 2>/dev/null
if [ -f "$IOT" ]; then
    chown bluetooth:bluetooth "$IOT" 2>/dev/null
    chmod 660 "$IOT" 2>/dev/null
fi

echo "==> bt_config.conf, AZ100 section:"
sed -n "/^\[b8:20:8e:ee:84:85\]/,/^\[/p" "$CONF" | head -10

echo "==> leftover broadcast keys (want none):"
grep -n 'broadcast' "$CONF" || echo "  (none)"

echo "==> bt_iot_update_list.conf:"
cat "$IOT" 2>/dev/null

echo "==> enabling adapter"
svc bluetooth enable
sleep 10
echo "done"
