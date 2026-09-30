# Maple v83 Offline (Android)

A single-player, offline MapleStory v83 client for Android phones, built with libGDX (Java).

This folder is **only the game engine**. The game art, maps and music come from **your own**
v83 `.wz` files. The build packs them into the APK on your PC. They are never committed to git,
because Nexon owns them.

## Build the APK (Windows)

You need Android Studio installed. It includes Java and the Android SDK.

1. Get this repo onto your PC. You can use **Code → Download ZIP** on GitHub (pick this branch) or `git clone`.
2. Open the `maple` folder and double-click **`BUILD-APK.bat`**.
   - It reads the `.wz` files from `C:\msclass`. If your game is somewhere else, the script asks you
     to drag the folder into the window.
   - The first build downloads libGDX and the Android build tools, then packs about 1.9 GB of game
     data, so it takes a few minutes.
3. You get **`MapleV83.apk`** in the `maple` folder. Install it with **`INSTALL-ON-PHONE.bat`** (phone plugged
   in with USB debugging on), or copy it to the phone and tap it.

Everything is inside the APK. After installing, the phone doesn't need your PC or an internet connection.

### Other scripts

| Script | What it does |
| --- | --- |
| `CHECK-WZ.bat` | Reads your `.wz` files like the game does and writes a report to `wzcheck.txt`. Run this first if anything looks wrong. |
| `PLAY-ON-PC.bat` | Runs the same game in a window on your PC: arrow keys to move, Alt or Space to jump, Up for portals and ladders, M for the travel menu, F3 for debug. |

## Controls on the phone

| Control | What it does |
| --- | --- |
| D-pad (bottom left) | Walk, climb ladders and ropes (up/down), lie down (down) |
| **JUMP** | Jump. Down + Jump drops through a platform. Jump + left/right jumps off a ladder. |
| **UP** | Go through a portal |
| **MAPS** (top right) | Travel list: Henesys, Ellinia, Perion, Kerning, Orbis, Ludibrium... |
| **DEBUG** | Shows footholds, ladders, portal areas and your position |
| Back button | Opens the travel list |

The game remembers the last map you were on.

## What works (phase 1)

- Reads v83 GMS `.wz` files directly: version detection, encrypted names, all image formats, links
- Maps: parallax, tiled and scrolling backgrounds, tiles, animated objects, foregrounds, map names,
  background music
- Movement using the original client's physics: walking, slopes, walls, jumping, falling,
  dropping through platforms, ladders and ropes
- Portals between maps, including invisible and touch portals
- NPCs standing in place, and mobs wandering around their spawn area
- Your character built from Character.wz: body, head, face, hair, starter clothes and a sword

## Next phases

2. Combat: attacking, mob HP, damage numbers, EXP, levels and drops
3. NPC chat, shops, inventory and equipment, quests (from the OdinMS/HeavenMS data)
4. Jobs, skills, bosses, saving your character

## Project layout

| Path | What it is |
| --- | --- |
| `core/src/main/java/maple/wz` | WZ archive reader (memory-mapped, lazy) |
| `core/src/main/java/maple/map` | Maps: footholds and physics, backgrounds, portals, ladders, NPCs and mobs |
| `core/src/main/java/maple/chr` | Character look (`Avatar`) and player movement (`Player`) |
| `core/src/main/java/maple/ui` | Touch controls, travel menu |
| `core/src/test` | Tests that build small made-up `.wz` files and run the reader, physics and map loader on them |
| `desktop` | PC version, `wzcheck` tool, screenshot test mode |
| `android` | Android launcher. `copyWzFiles` puts your `.wz` files into the APK uncompressed so the game can map them. |

Command line: `gradlew android:assembleDebug -PwzDir=C:\msclass`, `gradlew desktop:run -PwzDir=...`,
`gradlew core:test`.
