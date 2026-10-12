#!/system/bin/sh
# Runs on the device as root.  See patch-lea-vidpid.sh for the full rationale.
#
# The critical ordering: the adapter must be *off* and the bluetooth process
# must be *gone* before the file is edited.  btif_config keeps a process-wide
# in-memory cache that is only populated once per process lifetime, so editing
# the file under a live process is silently undone by the next flush.

CONF=/data/misc/bluedroid/bt_config.conf
SEC=b8:20:8e:ee:84:85
STAMP=$(date +%Y%m%d-%H%M%S)

echo "==> backup -> $CONF.bak.$STAMP"
cp -a "$CONF" "$CONF.bak.$STAMP" || exit 1

echo "==> disabling adapter"
svc bluetooth disable
sleep 3

echo "==> ensuring the process is really gone"
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
    echo "!! process $P still alive after 20s -- aborting, file untouched"
    svc bluetooth enable
    exit 1
fi
echo "    process gone"

echo "==> editing"
if grep -q '^broadcastVendorId' "$CONF"; then
    echo "    keys already present"
else
    sed -i "s|^\[$SEC\]$|[$SEC]\nbroadcastVendorId = 1946\nbroadcastProductId = 401428|" "$CONF" || exit 1
fi
chown bluetooth:bluetooth "$CONF"
chmod 660 "$CONF"
grep -n broadcast "$CONF" || echo "    !! no broadcast keys found after edit"

echo "==> enabling adapter"
svc bluetooth enable
sleep 10

echo "==> verification (file after a fresh stack start)"
grep -n broadcast "$CONF" || echo "    !! keys were overwritten by the new process"
echo "    pid: $(pidof com.android.bluetooth)"
