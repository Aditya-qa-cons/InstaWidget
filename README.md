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

The app declares **one permission**: `RECEIVE_BOOT_COMPLETED`, and only so it
can ask the system to rebind the notification listener after a restart, which
several OEM builds otherwise leave dead. There is deliberately no `INTERNET`
permission, because nothing is ever sent anywhere.

Notification access is a special access the user grants by hand in Settings; it
cannot be requested programmatically, which is what `SetupActivity` exists for.

## Setup, and what cannot be automated

`SetupActivity` is a checklist that re-evaluates on every resume:

1. **Notification access** — manual, and unavoidably so. Android has no API to
   request it.
2. **Notification reader running** — repaired automatically. Granted access
   does not guarantee a live binding; app updates drop it and OEM battery
   managers kill it. `NotificationListenerService.requestRebind()` is called
   from `onListenerDisconnected`, on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`,
   from the widget's `onUpdate`, and on resuming the setup screen.
3. **Widget on the home screen** — one button, via `requestPinAppWidget`.
4. **Instagram inbox link** — chosen automatically on first run from the first
   candidate that resolves; only needs attention if the widget lands on the
   home feed.

So a clean install is: open the app, grant notification access, tap "Add widget
to home screen". Everything else settles by itself.

## Look and feel

The widget is a rounded card with a soft vertical wash, a gradient header pill
carrying the Instagram ramp, and one row per DM: a coloured monogram circle,
sender, relative time and a two-line preview. Instagram's notifications carry
no avatar the app may reuse and there is no network access to fetch one, so
`Avatars` draws a stable monogram instead, its colour picked by a hash of the
sender so the same person always gets the same circle.

Widget colours have a `values-night` variant, since launchers render widgets
over whatever wallpaper the user has and a permanently white card looks wrong
at night. The app's own screen stays light and sets its colours explicitly
rather than inheriting them, so it does not depend on the platform theme.

`tools/generate-icons.py` renders the launcher icons and the widget picker
preview, which is drawn as an accurate mock of the real layout.

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

Reading the text is its own problem. A MessagingStyle notification keeps the
message in a parcelled `android.messages` array and can leave `EXTRA_TEXT`
unset entirely, so the filter reads that array first (newest entry backwards),
then falls back to `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, the last `android.textLines`
entry, and finally `android.summaryText`. MessagingStyle is also treated as
proof that the notification is a DM.

## When a DM arrives but the widget stays empty

The setup screen has a **Diagnostics** panel showing what the listener saw and
what the filter decided, plus a **Copy diagnostics** button. It answers the
three questions that look identical from the outside:

* **"Notifications seen: 0 total"** and **"Listener last connected: never"**
  while access reads as granted — the service was never bound, or the binding
  was lost. An app update is enough to lose it on many builds, and OEM battery
  managers kill it and never bring it back. Use **Reconnect notification
  reader**, which calls `NotificationListenerService.requestRebind()`; the
  setup screen also fires that automatically on resume when access is granted
  but the listener has never run. If it stays disconnected, toggle notification
  access off and on, and on Xiaomi enable Autostart and set battery saver to
  No restrictions.
* **"0 from Instagram"** while the total climbs — the listener works, but
  Instagram is not posting notifications. Check Instagram's own DM notification
  settings, and note that Instagram posts nothing while you are sitting in the
  conversation.
* **A `SKIPPED` line** — the notification arrived and the filter rejected it.
  The line says why and dumps the signals it saw (`category`, `template`,
  `channel`, whether MessagingStyle messages were present), which is enough to
  fix the rule in `DmNotificationFilter`.

## One row per conversation

The widget behaves like an inbox: `DmStore.add` folds each DM into its
conversation, keyed on the normalised sender name, and moves that conversation
to the top. Instagram exposes no thread id in a notification, so the sender
name is the only handle available.

A badge shows how many messages have folded into a row since the user last
opened the inbox from the widget. Two things make that count honest:

* A MessagingStyle notification is re-posted every time the conversation
  changes, so the same newest message arrives repeatedly. Identical preview
  text is treated as the same message, refreshing the timestamp without
  inflating the count.
* Tapping the widget goes through `OpenInboxActivity`, an invisible activity
  that opens Instagram and then resets the counts, on the grounds that the
  user has now seen what was waiting. It exists because a broadcast receiver
  cannot reliably start an activity from the background, whereas this is the
  foreground activity for the instant it lives. It opens Instagram first and
  treats the bookkeeping as best-effort, so a tap can never fail because of
  it.

`tools/grouping-check.py` ports those merge rules to Python and asserts them
against the awkward cases, since the real ones cannot run off-device.

## Opening the exact conversation

Off by default, behind **Open the exact conversation** on the setup screen.
When on, a row tries `https://ig.me/m/<username>`, Instagram's own "message me"
link, which is the closest thing it publishes to a per-thread deep link.

It only applies when the notification title is a handle rather than a display
name -- `priya.desai` qualifies, `Cozy Cat Kitchen | Homemade Cat...` does not
-- and `Instagram.threadIntent` returns null in every other case, so the row
falls back to the inbox rather than failing.

## Known limitation: no per-thread deep link

Instagram has **no public deep link to a specific conversation**, so every row
opens the general inbox. That is expected behaviour, not a bug.

Getting to the inbox at all is not fixed either: Instagram documents none of
these links and which one lands on the inbox rather than the home feed depends
on the installed Instagram version. `Instagram.INBOX_LINKS` holds the
candidates, and the setup screen lets the user Try each and Use whichever
works; the choice is remembered and the widget follows it. The order in code is
the default, most likely first:

1. `https://www.instagram.com/direct/inbox/` sent to `com.instagram.android`
2. `instagram://direct_inbox`
3. `instagram://direct-inbox`
4. `instagram://direct_v2`
5. the same web URL with no package, which lands in a browser

Every tap on the widget opens that inbox: the header, each row, and the empty
state. The one exception is the small gear in the header corner, which opens
the setup screen so it stays reachable from the home screen.

Reaching Instagram at all needs the `<queries>` block in the manifest. From
targetSdk 30 the platform hides other packages, so `resolveActivity()` returns
null for `instagram://direct_inbox` even with Instagram installed, and the
widget silently falls through to the browser.

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

### Widget missing from the launcher's widget picker

Open the app. It reports **"Widget registered with Android: yes"** when the
framework has the provider, which it reads back from
`AppWidgetManager.getInstalledProviders()`. If that says yes and the picker
still does not list the widget, the app is fine and the launcher is hiding it.

**Use the "Add widget to home screen" button.** It calls
`AppWidgetManager.requestPinAppWidget()`, which asks the launcher to place the
widget directly. That is a different code path from the picker, and launchers
that omit third-party widgets from their picker generally still honour it.
Xiaomi's launcher is the reason this button exists: its picker lists a curated
set of first-party widgets and will not surface this one at all, by name or by
search.

Two further things the app already does for pickier launchers:

* Ships a real **PNG preview** at four densities. Launchers that build their
  own picker tend to decode `previewImage` with `BitmapFactory`, which returns
  null for a vector XML drawable, and then skip the widget.
* **Exports the widget receiver.** The platform delivers `APPWIDGET_UPDATE` to
  the component either way, but a launcher enumerating providers with
  `PackageManager.queryBroadcastReceivers` from its own process never sees a
  non-exported receiver.

Restarting the launcher (Settings > Apps > System launcher > Force stop) clears
a stale cached widget list, which is worth trying before anything else.

`tools/generate-icons.py` regenerates the preview and the launcher icons.

### The widget looks fine but stopped updating

A listener that has silently died is indistinguishable from a quiet inbox, so
the widget shows a **Reader offline** strip when notification access is granted
but the service is not bound. Tapping it opens the setup screen, which asks for
a rebind on resume.

## Release and debug builds

```bash
tools/build-apk-offline.sh            # dist/app-release.apk
tools/build-apk-offline.sh --debug    # dist/app-debug.apk
```

**Install the release build.** A debug build is marked `android:debuggable`
and is signed with the generic `CN=Android Debug` identity, and Google Play
Protect blocks installs on those grounds. The release build is neither: no
debuggable flag, and a real 4096-bit signing identity.

That signing key is the app's identity. Android only accepts an upgrade signed
with the same key, so the keystore under `keystore/` has to survive: lose it
and every future version must be installed fresh, taking the cached DMs and the
notification access grant with it. It is gitignored deliberately — this
repository is public, and anyone holding that key could sign an "upgrade" the
phone would install over this app without complaint. Back up
`keystore/release.keystore` and `keystore/release.password` together,
somewhere private.

Moving from the old debug-signed build to the release build is a signature
change, so it needs one uninstall:

```bash
adb uninstall com.instawidget.dm && tools/install.sh
```

Upgrades after that are ordinary in-place installs.

## Play Protect

A release build clears the two things Play Protect reliably objects to in a
sideloaded APK: it is not `android:debuggable`, and it is not signed with the
shared `CN=Android Debug` identity. What it cannot clear is Play Protect
declining to install an app from an unregistered developer at all, which newer
Android builds do.

The two cases look different on the phone, and that is how to tell them apart:

* **A prompt offering to scan, or a warning with "install anyway"** — a scan
  verdict on an app it has not seen before. Accept the scan, or use
  `tools/install.sh`; `adb install` does not go through the same prompt.
* **Blocked outright, with no way to proceed** — a policy decision, not a
  verdict on the code. Nothing about the APK changes this. Try
  `NO_VERIFY=1 tools/install.sh`, which turns off Play Protect's check on adb
  installs for that run and restores it afterwards. If that is refused too,
  the device requires apps to come from a registered developer and the only
  routes left are registering as one or installing on a device without that
  requirement.

Turning off "Scan apps with Play Protect" in the Play Store also works, but it
disables scanning for everything on the phone, and re-enabling it later can
flag the app again.

## Installing and upgrading

Run this **on a computer**, with the phone connected by USB and USB debugging
enabled. The script drives the phone through adb; there is no adb on the
handset, so copying it to the device does nothing.

```bash
tools/install.sh              # uses dist/app-release.apk
tools/install.sh path/to.apk
```

Use the script rather than tapping the APK. Android 13+ marks an app installed
from an APK file as restricted and resets the `ACCESS_RESTRICTED_SETTINGS`
app-op, which is what guards notification access, and re-installing by tapping
re-applies that **every time** -- hence the
disable-access / allow-restricted-settings / re-enable-access dance on each
upgrade. Nothing in the app can prevent it: the flag is set by whoever installs
it, not by the app. `adb install` is a session-based install and is exempt, and
the script additionally re-asserts the app-op and the listener grant, so an
upgrade needs no taps at all.

`adb install -r dist/app-release.apk` alone usually preserves the grant too.
Tapping the APK in a file manager is the one route that reliably loses it.

### Upgrading with no computer to hand

Tap the APK as usual, then open the app and read the checklist. The dance is
only needed to *change* the notification access toggle; it is not needed when
the toggle is still on and merely the binding died, which is the usual case.
So:

* **"Notification reader running"** — nothing to do.
* **"Notification reader not running yet"** while access is still granted —
  tap **Reconnect notification reader**. No restricted-settings step, because
  nothing in Settings is being changed.
* **"Grant notification access"** — access really was revoked, and only then
  does the allow-restricted-settings step apply.

Then open **IG DM Widget**, tap **Open notification access settings**, enable
it, and add the widget from your launcher's widget picker.

### "Restricted setting" when granting notification access

Android 13 and newer refuse to let a **sideloaded** app hold notification
listener access. Installing the APK by tapping it in a file manager counts as
sideloading, and the toggle comes up greyed out behind a *"For your security,
this setting is currently unavailable"* dialog. It is a platform restriction,
not an app failure.

Clear it in one of these ways:

**On the phone, no PC needed** — Settings > Apps > (Manage apps) >
**IG DM Widget** > **⋮** menu, top-right > **Allow restricted settings**. Then
go back and grant notification access. The setup screen has an *Open this
app's info page* button that takes you straight there.

**Over adb** — either lift the restriction:

```bash
adb shell appops set com.instawidget.dm ACCESS_RESTRICTED_SETTINGS allow
```

or skip the Settings UI and grant the listener directly:

```bash
adb shell cmd notification allow_listener \
    com.instawidget.dm/com.instawidget.dm.DmNotificationListener
```

Installing with `adb install -r` in the first place also usually avoids the
restriction, because that is a session-based install rather than a sideload.
