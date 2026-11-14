# Unloop

Use your apps without the endless loop. Unloop steps you out of Reels, Shorts, For You and Spotlight while keeping messages, search and posting. Free, open source, private: **no account, no ads, no analytics, no internet permission.**

## What it does

* **Skips the endless feeds:** Instagram Reels, YouTube Shorts, TikTok For You, Snapchat Spotlight, Facebook Reels. It goes back, taps a tab, or covers the button.
* **Hide options (all off until you turn them on):** Reels/Shorts/Explore buttons, Stories row, the end of the feed, comments, sponsored and suggested posts, search-only YouTube, and modes such as Messages only.
* **Time:** daily limits per app or group, a daily allowance for a feed, quiet-hour schedules, pause.
* **Awareness:** a scroll counter and an optional "send me home after N swipes".
* **Commitment:** a 60 second wait before loosening anything, and a strict mode that only lets rules get tighter.
* **Local insights, notification snoozing, backup to your clipboard.**

Everything is processed on your phone. See [PRIVACY.md](PRIVACY.md).

## How it works

Android's Accessibility service lets Unloop see the structure of a screen (view names, positions, which tab is open) in the apps it supports. It never reads what you type, and never reads payment or banking apps. Unloop has no internet permission, so nothing can leave the phone.

## Build

You need Flutter, JDK 21 and the Android SDK.

```bash
cd app
flutter pub get
flutter run                                         # the "full" build (default)
flutter build appbundle --flavor play --release     # the Google Play build
flutter build apk --flavor full --release           # GitHub / F-Droid build
```

Two flavors share one codebase:

| Flavor | For | Difference |
|---|---|---|
| `play` | Google Play | No `WRITE_SECURE_SETTINGS`, no foreground service, strict mode never touches the Android settings page. |
| `full` | GitHub, F-Droid, your own phone | Adds the payment-app step-aside (needs a one-time `adb shell pm grant com.gainandshine.unloop android.permission.WRITE_SECURE_SETTINGS`). |

Release signing: create `app/android/key.properties` (never commit it) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Test

```bash
cd app && flutter analyze && flutter test
cd app/android && ./gradlew :core:test :app:testFullDebugUnitTest :app:lintFullDebug :app:lintPlayRelease
```

## Layout

```
app/                 Flutter app (UI in lib/, own design system in lib/ui)
app/android/core/    Pure Kotlin rules engine: detection, limits, schedules, usage. No Android types, fully unit tested.
app/android/app/     Android shell: accessibility service, overlays, bridge to Flutter
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GNU AGPL-3.0](LICENSE).
