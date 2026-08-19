#!/usr/bin/env bash
# Installs End Call Button and turns on everything it needs, over adb.
#
# NOTE on the escaping below: component names often contain '$' (inner classes).
# Reading the existing list and writing it back naively will silently TRUNCATE
# other apps' entries -- e.g. Android Auto's
#   ...SharedNotificationListenerManager$ListenerService
# becomes ...SharedNotificationListenerManager, and Auto stops getting
# notifications. We quote for both the host shell and the device shell.
set -euo pipefail

PKG=com.syed.endcall
A11Y="$PKG/$PKG.EndCallService"
NL="$PKG/$PKG.CallNotificationListener"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
SERIAL="${ADB_SERIAL:-}"
adb() { if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi; }

echo "==> installing $APK"
adb install -r "$APK"

echo "==> enabling accessibility service"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  null|"")        new="$A11Y" ;;
  *"$A11Y"*)      new="$cur" ;;
  *)              new="$cur:$A11Y" ;;
esac
adb shell settings put secure enabled_accessibility_services "'$new'"
adb shell settings put secure accessibility_enabled 1

echo "==> enabling notification access"
# 'cmd notification allow_listener' appends safely and never rewrites the list,
# which is exactly why we use it instead of editing the secure setting by hand.
adb shell cmd notification allow_listener "$NL"

echo "==> granting phone permissions"
adb shell pm grant $PKG android.permission.READ_PHONE_STATE || true
adb shell pm grant $PKG android.permission.ANSWER_PHONE_CALLS || true

echo "==> exempting from battery optimisation (belt and braces; it has no timers anyway)"
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null || true

echo
echo "==> verifying"
adb shell dumpsys activity services $PKG | grep -E "ServiceRecord" || echo "  !! no services bound"
echo
echo "Done. Open the app to confirm all four rows are green."
