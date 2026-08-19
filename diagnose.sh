#!/usr/bin/env bash
# Run this WHILE A WHATSAPP CALL IS CONNECTED on the target phone.
#
# This answers the one question the design hangs on: does this phone's WhatsApp
# publish a hang-up action we can fire, or must we fall back to telecom/root?
set -uo pipefail
SERIAL="${ADB_SERIAL:-}"
adb() { if [ -n "$SERIAL" ]; then command adb -s "$SERIAL" "$@"; else command adb "$@"; fi; }

echo "###################### DEVICE ######################"
for p in ro.product.model ro.build.version.release ro.build.version.sdk persist.sys.locale ro.product.locale; do
  printf '%-32s %s\n' "$p" "$(adb shell getprop $p | tr -d '\r')"
done

echo
echo "###################### CALL APPS ######################"
for p in com.whatsapp com.whatsapp.w4b com.imo.android.imoim com.facebook.orca; do
  v=$(adb shell dumpsys package $p 2>/dev/null | grep -m1 versionName | tr -d '\r' | xargs)
  [ -n "$v" ] && printf '%-28s %s\n' "$p" "$v"
done

echo
echo "###################### AUDIO MODE ######################"
echo "(want MODE_IN_COMMUNICATION=3 during a VoIP call, MODE_IN_CALL=2 for cellular)"
adb shell dumpsys audio 2>/dev/null | grep -iE "^ *mode |audio mode|Mode owner" | head -5

echo
echo "###################### TELECOM ######################"
echo "(if WhatsApp appears here, TelecomManager.endCall() will work on it)"
adb shell dumpsys telecom 2>/dev/null | grep -iE "mCalls|ConnectionService|SelfManaged|state=" | head -20

echo
echo "############### LIVE CALL NOTIFICATION ###############"
echo "(looking for: category=call, ongoing flag, and the ACTIONS list)"
adb shell dumpsys notification --noredact 2>/dev/null \
  | awk '/NotificationRecord.*(whatsapp|imo|orca|dialer)/{f=1} f{print} f&&/^$/{f=0}' \
  | grep -iE "NotificationRecord|category|flags|android.template|callType|actions|Action|contentIntent|fullScreenIntent|tickerText" \
  | head -60

echo
echo "###################### OUR APP ######################"
adb shell dumpsys activity services com.syed.endcall 2>/dev/null | grep -E "ServiceRecord" || echo "not bound"
adb logcat -d -t 200 2>/dev/null | grep -i endcall | tail -20
