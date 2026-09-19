#!/usr/bin/env bash
#
# Installs or upgrades the app over adb, without the
# disable-access / allow-restricted-settings / re-enable-access dance.
#
# RUN THIS ON A COMPUTER, not on the phone. It drives the phone over USB via
# adb; there is no adb on the handset, so copying this script to the device
# does nothing. Connect the phone by USB with developer options > USB
# debugging turned on, then run it from the project directory.
#
# Why that dance happens: Android 13+ marks an app installed from an APK file
# as "restricted" and resets the ACCESS_RESTRICTED_SETTINGS app-op, which is
# what guards notification access. Re-installing by tapping the APK re-applies
# that every single time. There is nothing the app can do about it from the
# inside -- the flag is set by whoever installs it.
#
# adb install is a session-based install and is exempt, so the grant survives.
# This script also re-asserts both settings explicitly, so an upgrade is
# hands-free even if the platform does reset them.
#
#   tools/install.sh [path/to/app-debug.apk]
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$PROJECT_ROOT/dist/app-debug.apk}"
PACKAGE="com.instawidget.dm"
LISTENER="$PACKAGE/$PACKAGE.DmNotificationListener"

command -v adb >/dev/null || { echo "adb is not on PATH." >&2; exit 1; }
[[ -f "$APK" ]] || { echo "No APK at $APK" >&2; exit 1; }

if [[ -z "$(adb devices | awk 'NR>1 && $2=="device"')" ]]; then
    echo "No device with USB debugging authorised. Run 'adb devices' and accept" >&2
    echo "the prompt on the phone." >&2
    exit 1
fi

echo "==> Installing $APK"
adb install -r "$APK"

# Lift the Android 13+ sideload restriction. Harmless where it is not applied.
echo "==> Allowing restricted settings"
adb shell appops set "$PACKAGE" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null \
    || echo "    (not supported on this build; skipping)"

echo "==> Granting notification access"
if ! adb shell cmd notification allow_listener "$LISTENER" >/dev/null 2>&1; then
    # Older builds have no 'cmd notification', so edit the secure setting.
    current="$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')"
    [[ "$current" == "null" ]] && current=""
    if [[ ":$current:" != *":$LISTENER:"* ]]; then
        updated="${current:+$current:}$LISTENER"
        adb shell settings put secure enabled_notification_listeners "$updated"
    fi
fi

# The binding does not always come back by itself after an update.
adb shell am start -n "$PACKAGE/.SetupActivity" >/dev/null 2>&1 || true

echo
echo "==> Notification access now reads:"
adb shell settings get secure enabled_notification_listeners | tr ':' '\n' | grep "$PACKAGE" \
    || echo "    NOT granted -- open the app and grant it by hand."
