# Contributing to Unloop

Thanks for helping. A few rules keep the app trustworthy.

## Ground rules

1. **No network, ever.** The app must not declare `INTERNET`. CI fails the Play build if it appears.
2. **No data leaves the phone.** No analytics, crash reporters, accounts or third-party SDKs that phone home.
3. **Never read payment or banking apps.** `PrivacyGuard` is the single place that decides this. Do not weaken it.
4. **Fail open.** If an app update changes a screen and a rule cannot find what it expects, it must do nothing, never block the whole app.
5. **Never trap the user.** Nothing may stop someone from opening Android settings, turning the service off or uninstalling in the Google Play build.
6. **Be honest in the UI.** Do not show a control for something that does not work.

## Where things go

* Rules and decisions go in `app/android/core` (pure Kotlin, no `android.*`). Every rule needs a test with a synthetic screen tree (see `core/src/test/.../Trees.kt`). The core compiles with warnings as errors.
* Anything that touches Android objects goes in `app/android/app`.
* Screens go in `app/lib/screens`, shared widgets in `app/lib/ui`. All state lives in `AppState` and is read from and saved to the native side.
* App ids and labels live in one adapter file per app (for example `InstagramIds.kt`) so an app update means patching one place.

## Check before you open a pull request

```bash
cd app && flutter analyze && flutter test
cd android && ./gradlew :core:test :app:testFullDebugUnitTest :app:lintFullDebug :app:lintPlayRelease
```

Do not run two Gradle builds at once on the same folder.

## Adding or fixing a rule for an app

1. Turn on **Settings > Learn mode**, pick the app, capture the screen. It saves view names, sizes and positions, never text.
2. Turn the capture into a small synthetic tree in a test and make it fail first.
3. Change the adapter until it passes. Keep the "nothing found means nothing happens" behaviour.

## Style

Small functions, comments that say why, no dead code, no copy-pasted code from other projects. By contributing you agree your work is licensed under AGPL-3.0.
