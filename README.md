# LocalDex

Run Samsung DeX **entirely on your phone** — no dock, no cable, no computer, no root.

![The DeX desktop running on-device with several app windows open](docs/images/dex-on-device.jpg)

LocalDex creates a hidden second display on your phone, lets Samsung run the DeX desktop on it, and shows it fullscreen with full mouse-style interaction. Curious how? See the [technical breakdown](docs/TECHNICAL.md).

## Requirements

- Samsung device on **One UI 8 or later** (desktop on virtual displays), Galaxy S23 or newer hardware generation
- Android 13+ (wireless debugging, and the runtime permissions pairing discovery needs on this API level)
- Wi-Fi network (wireless debugging needs an active Wi-Fi connection; no data leaves the device — the connection is phone-to-itself)

## Setup

1. Install the APK (`app/build/outputs/apk/debug/app-debug.apk`).
2. Enable **Developer options** (Settings → About phone → Software information →
   tap *Build number* 7 times).
3. Open LocalDex and follow the checklist:
   - Allow notifications (used to enter the pairing code).
   - Tap **Start Pairing** — LocalDex opens Developer options; go to
     **Wireless debugging → Pair device with pairing code**, then type the
     6-digit code into the LocalDex notification's reply field.
4. Back in LocalDex: pick a display spec and tap **Start DeX**.

Pairing is one-time. After the first successful connection LocalDex grants itself `WRITE_SECURE_SETTINGS` (over its own ADB shell) so it can switch wireless debugging back on automatically after a reboot or network change.

## Usage

- **Display spec** is `WIDTHxHEIGHT/DPI` (default `1920x1440/240`). Lower DPI = more desktop room; higher DPI = bigger UI.
- **Input is a virtual mouse**: one finger = click and drag (drag window handles to move windows), two fingers = scroll wheel. **Back** (gesture or key) is forwarded to DeX.
- A small handle peeks from the right edge — **tap it** to reveal the window controls (**◀ Left**, **Right ▶**, **Window / Full**) and **Stop** (with confirmation), tap again to hide them. The handle is excluded from the system's edge-swipe gesture so a swipe near it doesn't steal the tap. Its vertical position (default 1/3 up from the bottom) is adjustable on the main screen under **Exit tab position**.
- Leave the viewer with Home; the session keeps running. Return (or stop) via the **LocalDex notification** — the notification's *Stop* action always ends the session, even if the viewer is gone.

## Viewing DeX from a computer

The virtual display is a normal Android display, so a computer with adb access to the phone can open and interact with the *same* DeX desktop with plain scrcpy:

```sh
scrcpy --display-id=<N>
```

The display id `N` is shown in the LocalDex main screen and in the notification while a session is running (e.g. *"LocalDex is running (display 7)"*).

Alternatively, a computer can create its own DeX virtual display without LocalDex at all: `scrcpy --new-display=1920x1080/240`. 

## Windowed apps / window controls

Apps open as floating windows with a **drag handle** at the top: drag the handle to move, drag edges to resize, tap the handle for minimize / maximize / split-screen. Known quirk: the **split-screen** option in the handle menu moves the app into split-screen on the phone's main screen, not on the DeX display.

The side tab has window controls: **◀ Left** / **Right ▶** snap the window to that half of the display, and **Window / Full** toggles it between maximized and a centered window — use that one when a maximized app won't come back out of fullscreen on its own. With a keyboard attached, **Meta+Left/Right** snap to a half, **Meta+Up** maximizes, and **Meta+Down** restores to a centered window. Android's own desktop windowing documents no keyboard shortcut for any of this (only the drag gestures above), so LocalDex drives it directly.

Under the hood this changes the window's *windowing mode* (`am start --task <id> --windowingMode <mode>`) before applying bounds, because bounds alone can't un-maximize anything: `resizeTask` in the framework rejects every resize on a fullscreen task (`canResizeTask()` is true only for freeform and multi-window), so an `am task resize` against a maximized window is silently discarded. Which window it acts on comes from `am stack list`: the focused-window lookup is only a hint (LocalDex's own window holds focus while you're tapping its button, so the DeX display's focus is often unknown at that moment), and it falls back to the display's visible task. Best-effort regardless: it reads `dumpsys`/`am` text output, which is not a stable API, so it can miss on some builds — when it genuinely can't tell which window to move it says so rather than doing nothing quietly.

## How it works

The interesting parts — the on-device ADB connection, the bundled scrcpy server and custom client, and the one-line trick that brings resizable windows back on One UI 8.5+ — are covered in the [technical breakdown](docs/TECHNICAL.md). Built on [libadb-android](https://github.com/MuntashirAkon/libadb-android) (ADB layer ported from [anyapk](https://github.com/sam1am/anyapk)) and [scrcpy](https://github.com/Genymobile/scrcpy).

## Building

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the install is blocked with `INSTALL_FAILED_VERIFICATION_FAILURE`:

```sh
adb shell settings put global verifier_verify_adb_installs 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global verifier_verify_adb_installs 1
```

## Notes & limitations

- Wireless debugging turns itself off on reboot; LocalDex re-enables it automatically once it holds `WRITE_SECURE_SETTINGS` (see Setup), otherwise flip it on manually in Developer options.
- Audio stays on the phone (not routed through the session).
- One session at a time.