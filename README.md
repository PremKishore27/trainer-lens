# Trainer Lens (Android)
Screen-reading overlay for Pokémon GO (MediaProjection + on-device ML Kit OCR). Never taps or injects input.

1. Bag advisor - open Items, scroll the list, tap the 🔍 bubble. Tune keep-counts in `Rules.kt`.
2. IV checker - open a Pokémon summary: IV % (or range), Great/Ultra League rank. Base stats are fetched from
   pogoapi.net at runtime and kept in memory only.

## Get the APK
Push this folder to a GitHub repo -> Actions tab -> "Build APK" -> download the `TrainerLens-debug-apk` artifact
(unzip -> app-debug.apk), then sideload it. Or open in Android Studio and run on a device.
First launch: tap steps 1-4 on the start screen.

Status: draft, not yet compiled or run on a device.
