# IG DM Widget

A free Android home screen widget that shows previews of your recent Instagram
DMs. Tapping the widget opens Instagram's direct message inbox.

No Instagram login, no Meta Business API, no scraping, no network access.

## How it works

Instagram has no public API for reading your own DMs, so this app doesn't try.
Instead a `NotificationListenerService` reads the DM notifications Instagram
already posts to your phone, keeps the last 25 previews in `SharedPreferences`,
and renders them in a scrollable home screen widget.

```
Instagram posts a DM notification
        |
        v
DmNotificationListener  --(is this a DM?)-->  DmNotificationFilter
        |
        v
DmStore (SharedPreferences, JSON, on-device only)
        |
        v
DmWidgetService / DmRemoteViewsFactory  -->  ListView inside DmWidgetProvider
```

The app declares **no permissions at all**. In particular there is no
`INTERNET` permission, because nothing is ever sent anywhere. Notification
access is a special access the user grants by hand in Settings; it cannot be
requested programmatically, which is what `SetupActivity` exists for.

## Source layout

| File | Role |
| --- | --- |
| `SetupActivity.kt` | One-screen explainer, notification-access status, button to the Settings page, and a "clear cache" button |
| `DmNotificationListener.kt` | Receives every notification, keeps Instagram DMs, ignores the rest |
| `DmNotificationFilter.kt` | Decides DM vs. like/comment/follow/story |
| `DmStore.kt` | The whole persistence layer: one JSON string in `SharedPreferences` |
| `DmMessage.kt` | Sender, preview, timestamp |
| `DmWidgetProvider.kt` | `AppWidgetProvider`: header, list adapter, click intents |
| `DmWidgetService.kt` | `RemoteViewsService` + factory supplying the scrollable rows |
| `Instagram.kt` | Package allowlist, inbox deep link with web fallback, access check |

## Filtering DMs from everything else

Instagram documents none of this, so `DmNotificationFilter` is layered:

1. A **strong signal** means DM outright: `Notification.CATEGORY_MESSAGE`, a
   `MessagingStyle` template, or a notification channel id mentioning
   "direct" / "message".
2. Otherwise, a phrase from the non-DM list ("liked your", "started following
   you", "commented:", "went live", …) means it is not a DM.
3. Anything left over is treated as a DM. Instagram's engagement notifications
   essentially always carry one of those phrases, so the fallback errs toward
   showing a real message rather than silently dropping it.

If you find a notification type slipping through, add its phrase to
`NON_DM_PHRASES`.

## Known limitation: no per-thread deep link

Instagram exposes `instagram://direct_inbox` but has **no public deep link to a
specific conversation**. Every row therefore opens the general inbox. This is
expected behaviour, not a bug. If Instagram isn't installed, the widget falls
back to `https://www.instagram.com/direct/inbox/`.

Two other things follow from the design:

* Previews only appear for DMs that arrive **while notification access is
  granted** (plus whatever is still in the shade when you grant it). There is no
  way to backfill history.
* If you have Instagram's DM notifications muted or turned off, there is nothing
  for the widget to read.

## Building

Standard Android Studio / Gradle project:

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK platform `android-34` and build-tools `34.0.0`.
`minSdk` 26, `targetSdk` 34, Kotlin 1.9.24, AGP 8.5.2, no dependencies beyond
the Kotlin standard library.

### Building without dl.google.com

`tools/build-apk-offline.sh` builds the same debug APK without the Android
Gradle Plugin, for networks that block `dl.google.com` (which serves both AGP
and the Android SDK). It drives the same underlying tools AGP drives — `aapt2`,
`kotlinc`, `d8`, `zipalign`, `apksigner` — and writes `dist/app-debug.apk`.

It expects:

* JDK 17 (`JAVA_HOME`)
* `aapt2`, `apksigner`, `zipalign` and a platform `android.jar`
  (`ANDROID_HOME`, default `/usr/lib/android-sdk`; Debian/Ubuntu supply these
  via `apt install aapt apksigner zipalign android-sdk-build-tools
  android-sdk-platform-23`)
* `kotlinc`, `kotlin-stdlib` and `r8lib.jar` under `TOOLS_DIR`
  (default `/opt/android-tools`)

Prefer `./gradlew assembleDebug` whenever the network allows it.

## Installing

```bash
adb install -r dist/app-debug.apk
```

Then open **IG DM Widget**, tap **Open notification access settings**, enable
it, and add the widget from your launcher's widget picker.
