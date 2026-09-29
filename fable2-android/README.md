# Fable 2 for Android (native)

A native arm64 Android build of
[himdo/Fable-2-Recomp](https://github.com/himdo/Fable-2-Recomp). The game's
Xbox 360 code is statically recompiled to C++ and compiled for ARM, just as the
Windows release compiles it for x86. No Wine, Box64 or other emulation layer
is involved.

This folder has no game code in it. `build_apk.cmd` builds the APK on your PC
from **your own** disc image. The ISO, the extracted files and the generated
code stay on your machine. Don't share the resulting `Fable2.apk`, because it
contains code generated from the disc.

## What you need

- A Windows 10/11 PC with ~60 GB free disk and a few hours for the first build.
- Your Fable 2 disc image. The recomp targets the GOTY USA/EU disc with
  SHA-256 `685a0d3bea9718812f17bcd155907a5359a548b6d3d8342dd2a6c944f45e35ff`.
- An arm64 Android 10+ phone with Vulkan 1.1. A recent Snapdragon
  (8 Gen 2 or newer) is strongly recommended.
- Optional: a USB cable with **USB debugging** enabled on the phone, so the
  builder can install the APK and copy the game files for you.

## Build

1. Download this folder (or clone the repo).
2. Double-click **`build_apk.cmd`** and pick your ISO when asked.
   You can also pass the path: `build_apk.cmd "D:\Games\Fable2.iso"`.
3. Wait. The builder:
   1. installs anything missing with `winget`: Git, Python, CMake, Ninja,
      OpenJDK 17 and the Visual C++ runtime. It also downloads the Android
      SDK and NDK into the work folder.
   2. checks the ISO hash and extracts `default.xex`, `data/` and
      `$SystemUpdate/` (`tools/extract_xiso.py`).
   3. clones Fable-2-Recomp and its ReXGlue SDK fork at pinned commits and
      applies `patches/`.
   4. downloads the official ReXGlue 0.10.0 code generator (the same one
      the recomp's own build uses) and recompiles your `default.xex` to C++.
   5. cross-compiles everything for Android and writes **`Fable2.apk`** here.
   6. if a phone is connected, installs the APK and copies the game files to
      it (~7 GB).

Each step is skipped when it's already done. If something fails, fix it and
run the builder again. The work folder defaults to `C:\f2build`
(`-WorkDir` changes it; keep the path short).

## Install by hand (no USB debugging)

1. Copy `Fable2.apk` to the phone and install it.
2. Connect the phone to the PC with USB in **File transfer** mode. In File
   Explorer, create a folder named `Fable2` in the phone's internal storage.
3. Copy the *contents* of `C:\f2build\game\` (`default.xex`, `data`,
   `$SystemUpdate`) into that `Fable2` folder (about 7 GB).
4. Open **Fable II**, tap **Allow file access** and switch it on (the game
   needs it to read that folder), then go back to the app.

The game can't be packed into the APK itself: Android doesn't install APKs
anywhere near 7 GB, so the app and the game data are separate, as with other
large Android games.

Saves, settings (`fable2_config.toml`, `fable_2.toml`) and logs are written
to the same `Fable2` folder.

## Controls

The on-screen pad has every Xbox 360 input:

| Where | Controls |
|---|---|
| Top corners | LT / LB (left), RT / RB (right) |
| Top centre | BACK, START, and ◎, which hides or shows the pad |
| Bottom left | Left stick, L3 button above it, d-pad |
| Bottom right | A / B / X / Y, right stick, R3 button above it |

A Bluetooth or USB controller works too, alongside the touch controls. When a
controller connects, the touch pad hides itself; ◎ brings it back.

## How the port works

| Piece | Where |
|---|---|
| Android window surface, Vulkan presenter hookup, `SDL_main` entry, message boxes, content folder, GPU plugin loading, Android CMake branch | `patches/rexglue-sdk-android.patch` (ReXGlue SDK) |
| Touch pad driver registration, Android error text, desktop-only debug thread disabled | `patches/fable2-android.patch` (Fable-2-Recomp) |
| Touch pad input driver + JNI | `app-src/` |
| Launcher, SDL activity, touch overlay, Gradle/CMake project | `android/` |
| ISO extraction | `tools/extract_xiso.py` |

The SDK already had most of Xenia's Android support: shared memory, threading
and the Vulkan Android surface. The patch adds the missing glue and fixes two
things that would break any non-Windows link. One is a Windows-only linker
flag. The other is two copies of SDL.

What has been checked, without the game or an Android NDK:

- The whole SDK compiles for arm64, and `librexruntime.so` plus
  `librexgpu-xenos.so` link (ARM64 Linux cross-build).
- The patched Fable 2 sources (`main.cpp`, config, patches, hooks), the touch
  driver and the SDL entry point compile for arm64. The generated game code
  was replaced with a stand-in.
- The Java (launcher, activity, touch overlay, SDL's Java layer) compiles
  against the Android 14 framework classes.
- All patches apply cleanly to fresh checkouts at the pinned commits.
- The ISO extractor was tested on a synthetic disc image.

Not yet checked: a real NDK build, the recompiled game code itself, and
running on a phone. Expect the first build or launch to need fixes. When
something goes wrong, the logs are in `Android/data/com.fable2.recomp/files/logs/`,
or run `adb logcat -s rexglue SDL Fable2`.

## Known limits

- arm64 only.
- Phones that use 16 KB memory pages (an Android 15 developer option on some
  Pixels) are untested. The emulated Xbox memory layout expects 4 KB pages.
- Performance depends on the phone's Vulkan driver. Adreno (Snapdragon) is the
  best bet; Mali and PowerVR are much less likely to render correctly.
