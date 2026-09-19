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
#   tools/install.sh [path/to/app-release.apk]
#   NO_VERIFY=1 tools/install.sh        # also turn off Play Protect's adb
#                                       # install check for this run
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$PROJECT_ROOT/dist/app-release.apk}"
PACKAGE="com.instawidget.dm"
LISTENER="$PACKAGE/$PACKAGE.DmNotificationListener"

command -v adb >/dev/null || { echo "adb is not on PATH." >&2; exit 1; }
[[ -f "$APK" ]] || { echo "No APK at $APK" >&2; exit 1; }

if [[ -z "$(adb devices | awk 'NR>1 && $2=="device"')" ]]; then
    echo "No device with USB debugging authorised. Run 'adb devices' and accept" >&2
    echo "the prompt on the phone." >&2
    exit 1
fi

# Play Protect verifies adb installs too on some builds. --no-verify turns
# that off for the duration of this run and puts it back afterwards, for the
# case where the handset blocks the install outright with no "install anyway".
RESTORE_VERIFIER=""
if [[ "${NO_VERIFY:-0}" == 1 ]]; then
    previous="$(adb shell settings get global verifier_verify_adb_installs 2>/dev/null | tr -d '\r')"
    [[ "$previous" == "null" || -z "$previous" ]] && previous=1
    echo "==> Disabling adb install verification for this run"
    adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 \
        && RESTORE_VERIFIER="$previous"
fi
restore_verifier() {
    if [[ -n "$RESTORE_VERIFIER" ]]; then
        adb shell settings put global verifier_verify_adb_installs "$RESTORE_VERIFIER" >/dev/null 2>&1 || true
        RESTORE_VERIFIER=""
    fi
}
trap restore_verifier EXIT

echo "==> Installing $APK"
if ! output="$(adb install -r "$APK" 2>&1)"; then
    echo "$output"
    if grep -qi "verification\|play protect\|INSTALL_FAILED_VERIFICATION" <<<"$output"; then
        cat <<'MSG'

Play Protect refused this install. Retry with verification off for the run:

    NO_VERIFY=1 tools/install.sh

If that is refused too, the block is a device policy rather than a scan
verdict, and no change to the APK will get past it.
MSG
    fi
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" <<<"$output"; then
        cat <<'MSG'

The installed copy is signed with a different key, and Android will not
upgrade across a signature change. This happens once, moving off the old
debug-signed build. Uninstalling drops the cached DMs and the notification
access grant; the script re-grants access afterwards.

    adb uninstall com.instawidget.dm && tools/install.sh
MSG
    fi
    exit 1
fi

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
