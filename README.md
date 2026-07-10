# rice-launcher

A minimal, text-only Android launcher in the same design language as my
[linux rice](https://github.com/mosschief/rice): three colors, one monospace
font, zero decoration.

```
14:32  thu 9 jul                          87%
─────────────────────────────────────────────
run…
─────────────────────────────────────────────
firefox
messages
obsidian
calculator
camera
clock
…
```

## Design

Same palette as the desktop, following the system light/dark setting
(the Android equivalent of the rice's day/night toggle):

| Name       | Day       | Night     | Use                          |
|------------|-----------|-----------|------------------------------|
| Background | `#f2f1e5` | `#1c1b16` | window background            |
| Accent     | `#deddd1` | `#2e2d26` | dividers, pressed states     |
| Foreground | `#000000` | `#f2f1e5` | text                         |
| Muted      | `#888888` | `#888888` | hint text                    |

Flat everywhere: no icons, no ripples, no rounded corners, no shadows.
The Android status bar is hidden (swipe from the top edge to peek it);
a waybar-style row with clock and battery takes its place, above a
wmenu-style `run…` prompt that is always focused with the keyboard up,
and a text-only app list sorted by most recently used.

## Features

- Text-only list of launchable apps, most recently launched first
  (never-launched apps alphabetical below); tap to launch
- The `run…` prompt is auto-focused with the keyboard up — type to
  filter (prefix matches ranked first), Enter/Go launches the top match
  (wmenu behavior)
- Hidden system status bar with a clock + battery row of our own
- Work-profile apps included, shown as `appname (Work)` and launched
  into the right profile (via `LauncherApps`)
- Long-press a row for the system App Info screen (uninstall, etc.)
- Fast swipe up anywhere → Firefox with the address bar focused and
  keyboard up (mimics the Firefox search widget's intent); slower swipes
  scroll the list as usual
- Follows system dark mode via `values-night` resources
- Zero dependencies: no AndroidX, no libraries — framework `Activity` +
  `ListView` only. minSdk 26, targetSdk 35.

## Font

Ships with [Iosevka](https://github.com/be5invy/Iosevka) (regular + bold,
subset to Latin). The rice uses **Iosevka Oui**; to match it exactly,
replace the bundled files with the Oui TTFs (renamed, all-lowercase):

```
app/src/main/res/font/iosevka_regular.ttf
app/src/main/res/font/iosevka_bold.ttf
```

## Build

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then press Home and pick **rice** as the default launcher.

## Layout of the code

- `app/src/main/kotlin/.../MainActivity.kt` — the whole launcher (one file)
- `app/src/main/res/values/colors.xml` / `values-night/colors.xml` — the palette
- `app/src/main/res/values/themes.xml` — flat Material theme overrides
- `app/src/main/res/layout/` — search prompt + list
