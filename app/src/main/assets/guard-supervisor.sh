#!/system/bin/sh
# Root-owned, no credentials. Uninstalling the APK stops the supervisor.
guard_dir=/data/adb/mobile-data-guard
mkdir -p "$guard_dir"
chmod 700 "$guard_dir"
exec 0<>"$guard_dir/supervisor.lock"
flock -n 0 || exit 0
umask 077
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
while true; do
    # The user's off switch outranks everything, even a leftover copy of this script.
    [ -f "$guard_dir/disabled" ] && exit 0
    guard_apk=$(pm path com.dalong.dataguard </dev/null 2>/dev/null | head -n 1 | cut -d: -f2-)
    guard_uid=$(cmd package list packages -U com.dalong.dataguard </dev/null 2>/dev/null | sed -n 's/^package:com.dalong.dataguard uid:\([0-9]*\).*$/\1/p' | head -n 1)
    if [ -z "$guard_apk" ] || [ -z "$guard_uid" ]; then
        exit 0
    fi
    CLASSPATH="$guard_apk" app_process / com.dalong.dataguard.RootMain daemon "$guard_uid" >"$guard_dir/runtime.log" 2>&1
    sleep 1
done
