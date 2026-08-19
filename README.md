# End Call Button

A large floating **End Call** button that stays on screen for the whole call, so you can hang up from *any* screen — not just from inside the calling app.

Built for a family member who kept losing the WhatsApp call screen mid-call (a stray touch navigates away, and the hang-up button is suddenly nowhere to be found) and had no way back to end the call.

## What it does

- Shows a floating hang-up button whenever a call is **answered** — WhatsApp, other VoIP apps, and normal cellular calls
- Stays on top of every screen: home, other apps, lock screen
- **Tap → confirm**: the first tap opens a wide `END CALL` bar, the second ends the call. Two big targets, so a stray touch cannot drop a call
- **Long-press to drag**, snaps to either side edge and remembers the spot. The confirm bar always opens inward
- **Double-press volume-down** to hang up without touching the screen at all
- Never appears for a *ringing* call, so it can never reject a call by accident

## Battery

Measured on a real device: **0.02 s of CPU per 3 minutes**, no wakelocks, no alarms, no scheduled jobs.

Detection is entirely push-based — the system tells the app when a call notification appears or disappears, so nothing polls and nothing runs between calls. The overlay window does not exist unless a call is active, and it never animates while idle.

## How it ends a call

Most reliable first, falling through if a step doesn't actually drop the call:

1. **The calling app's own hang-up action**, taken from its ongoing call notification. Version-proof — it keeps working when the app reshuffles its obfuscated view IDs
2. **`TelecomManager.endCall()`** — solid for cellular calls
3. **An accessibility click** on the app's own hang-up button

Every step verifies the call genuinely ended before reporting success, rather than assuming a delivered intent worked.

No root required.

## Install

```bash
git clone https://github.com/s-shahriar/end-call-button
cd end-call-button
./install.sh            # installs and turns on everything it needs, over adb
```

Or download the APK from [Releases](../../releases), install it, and enable the two services manually:

- **Settings → Accessibility → End Call Button** — draws the button, enables the volume shortcut
- **Settings → Notifications → Device & app notifications → End Call Button** — detects calls
- Grant the **Phone** permission for cellular calls

Open the app to check all three are green.

## Notes

- Requires Android 12 (API 31) or newer
- Matching is done on notification *structure* — action count, call type, audio mode — never on English button text, so it works on a non-English phone
- `diagnose.sh` dumps a live call's notification structure over adb, useful when a particular app's calls aren't detected
- The app logs to logcat under tag `EndCallDiag`

## Building

A fresh clone builds without any signing setup (it falls back to the debug key). For release builds, create `keystore.properties` at the repo root:

```properties
storeFile=keystore/your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and `keystore/` are gitignored.
