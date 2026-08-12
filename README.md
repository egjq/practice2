# Simple EPUB Reader (Mars)

A lightweight Android 6 reader port focused on the reading behavior of Georg2002/SimpleEPUBReader, built clean-room for the Likebook Mars / Boyue-era e-ink devices.

## Included

- Android 6.0+ (`minSdk 23`)
- EPUB 2/3 container + OPF spine parsing
- Vertical Japanese text (`vertical-rl`)
- Ruby / furigana through WebView XHTML rendering
- Left tap / Volume Down / Page Down / D-pad Left = next page
- Right tap / Volume Up / Page Up / D-pad Right = previous page
- Open EPUB from Android's file picker or "Open with" from a file manager
- Full-screen black-on-white rendering
- No network permission

## Deliberately removed

- Dictionary
- Text selection
- Highlighting / marking
- Context menus
- Internet image loading
- Library management / covers / shelves
- JavaScript from books

## Build

GitHub Actions builds a debug-signed APK that is directly installable on Android 6.

Local build (Android SDK + Java 17 + Gradle 8.7 installed):

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Compatibility

This intentionally avoids AndroidX, Kotlin, Compose, animations, services, background sync, and vendor-specific e-ink APIs. Standard Japanese XHTML EPUBs with ruby are the main target. DRM-protected, heavily scripted, unusual-encoding, or complex fixed-layout EPUBs may not render correctly.
