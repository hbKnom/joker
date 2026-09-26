---
name: verify
description: Build, install, and drive the scripta sandbox app on an Android emulator/device to verify editor changes by observing rendering and behavior.
---

# Verifying scripta (self-drawn Compose code editor)

The editor is a library (`editor/`, KMP: android + jvm "desktop"). The runnable
surface is the **Android sandbox app** (`sandbox/`). Desktop has no app entry point
and (v1) no text input, so device verification = the Android sandbox.

## Build + install + launch

```bash
# Build the debug APK (use PowerShell/gradlew.bat, NOT bash `.\gradlew.bat`)
./gradlew.bat :sandbox:assembleDebug          # APK: sandbox/build/outputs/apk/debug/sandbox-debug.apk
APK=sandbox/build/outputs/apk/debug/sandbox-debug.apk
adb -s <serial> install -r "$APK"
adb -s <serial> shell am force-stop top.yukonga.scripta
adb -s <serial> shell am start -n top.yukonga.scripta/.sandbox.MainActivity
```

Package `top.yukonga.scripta`, activity `.sandbox.MainActivity`.

Sandbox UI (top toolbar, left→right): **加载 3MB YAML** (loads ~240k lines / 60k
entries — the perf smoke test), **换行: 关/开** (soft-wrap toggle), **只读: 关/开**
(read-only toggle). Below is the editor.

## Screenshot

- **Emulator:** `adb -s emulator-5554 exec-out screencap -p > out.png` works.
- **Real device (multi-display / warning-on-stdout corrupts the PNG):** capture to a
  file, then pull. Disable Git-Bash path mangling and pick the main display:
  ```bash
  export MSYS_NO_PATHCONV=1
  adb -s <serial> shell screencap -p -d <displayId> /sdcard/ss.png   # displayId from: dumpsys SurfaceFlinger --display-id (HWC display 0)
  adb -s <serial> pull /sdcard/ss.png out.png
  ```
- The Read tool reports the screenshot's displayed size + a multiplier; **multiply
  screenshot pixel coords by that factor to get device coords for `adb input`.**

## Drive it

```bash
adb -s <s> shell input tap <x> <y>                        # tap a toolbar button / place caret
adb -s <s> shell input swipe <x> <y> <x> <y> 700          # ~700ms same-point = long-press (select word)
adb -s <s> shell input swipe 450 2000 450 300 90          # fast fling (scroll down)
adb -s <s> shell input swipe 450 1700 450 500 2500        # slow drag
adb -s <s> shell input keyevent 66                        # ENTER (inserts newline; onKeyEvent path)
```

Hit-test check (highest-value for scroll/geometry changes): scroll deep, long-press a
known word, confirm the selection highlight lands on exactly that word+line.
Read-only check: toggle 只读:开 → caret disappears, typing/paste blocked, but
long-press still selects. Edit check: place caret, ENTER → line numbers renumber
(proves recomposition-on-edit still fires).

## Rough perf (frame time)

```bash
adb -s <s> shell dumpsys gfxinfo top.yukonga.scripta reset
# ...drive a fixed scroll sequence...
adb -s <s> shell dumpsys gfxinfo top.yukonga.scripta | grep -E "Total frames|percentile"
```
Caveat: the emulator is GPU-bound (50th ~20-30ms), so it underrepresents
recomposition (CPU) savings. Fling frame counts vary with input registration — reset
+ re-run for A/B, and compare tail (90th/99th) percentiles, not just the median.

## Gotchas
- No Compose UI test harness wired up (`withHostTest {}` not enabled); commonTest is
  pure logic only. Device verification is the only runtime check for the @Composable.
- Real device may be locked (secure keyguard) — adb can't unlock; ask the user.
- `am start` may land the app paused if the screen was asleep; `input keyevent
  KEYCODE_WAKEUP` first.
