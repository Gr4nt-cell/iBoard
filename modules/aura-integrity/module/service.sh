#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/launch.sh" "$MODDIR" >/dev/null 2>&1 &
