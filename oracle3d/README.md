# Holodrum 3D

Oracle of Seasons rebuilt as one seamless 3D voxel world, with real shadows and a free
camera. It runs on PC (Windows, macOS, Linux) and Android, and in a browser.

The game itself still runs underneath: the ROM assembled from the
[oracles-disasm](https://github.com/Stewmath/oracles-disasm) decomp runs in an
emulator core, and everything the game does (Link, enemies, NPCs, items, cut bushes,
the Rod of Seasons, menus) shows up in 3D. The 3D side ignores the Game Boy Color's
limits. The whole of Holodrum is drawn at once at any resolution and aspect ratio, so
there are no screen edges, and walking from one area into the next is continuous.

No game data ships with this project. You supply the ROM. On first launch the app
builds the 3D world from it (about a minute), then caches it.

## How it works

| Piece | File | What it does |
| --- | --- | --- |
| World capture | `src/capture.js` | Drives the game to warp into every overworld and Subrosia room in all four seasons, and reads back room layouts, metatile graphics and collision tables from RAM (addresses from the decomp's `.sym`). Every distinct 16×16 metatile goes into one shared texture pool. |
| Height model | `src/terrain.js` | Converts the oblique 2D art to 3D. Solid tiles are extruded, and the bottom rows of each object stand up as its front wall. Water and holes sink below the ground. |
| Elevation | `src/elevation.js` | Splits the map into regions separated by cliffs and stairs, and works out from each cliff band which side is higher. Plateaus rise above the lowlands, and stairs become ramps. |
| Rendering | `src/world.js`, `src/main.js` | Three.js. Every surface looks up its pixel from the tile map on the GPU, so animated water, season changes and cut bushes update without rebuilding textures. Directional sun with shadow maps, tilt-shift depth of field. |
| Live room | `src/main.js` | The room Link is in is rebuilt from the game's RAM every frame, so changes appear immediately. Interiors and dungeons are drawn this way too. |
| Sprites | `src/sprites.js` | OAM sprites become upright voxel figures that cast shadows. Each sprite is matched to its game object to find where its feet touch the ground. |
| Seamless edges | `src/main.js` | Link's world position is continuous across room edges. The game's scroll transition is fast-forwarded so walking never pauses. |

## Build the ROM from the decomp

```sh
git clone https://github.com/Stewmath/oracles-disasm
cd oracles-disasm
make seasons        # needs WLA-DX 10.6 and python3-yaml; writes seasons.gbc
```

## Run it

```sh
cd oracle3d
npm install
npm run dev         # open the printed URL and pick seasons.gbc
```

For faster development, put `seasons.gbc` in `oracle3d/dev-data/` (git-ignored) so it
loads automatically. `npm run capture -- dev-data/seasons.gbc` prebuilds the world into
`dev-data/world/` so you skip the in-app build.

## PC build

```sh
npm run desktop     # build and run in Electron
npm run dist:win    # Windows installer + portable .exe in release/
npm run dist:mac    # macOS .dmg (run this on a Mac)
npm run dist:linux  # Linux AppImage
```

## Android build

```sh
npm run android     # builds, syncs into android/ and opens Android Studio
```

In Android Studio, press **Run** with a phone plugged in, or use
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The app runs in landscape and
full screen, with on-screen controls. Pick the ROM on first launch.

## Controls

| Action | Keyboard | Touch | Gamepad |
| --- | --- | --- | --- |
| Move | Arrows / WASD | Left stick | Stick / d-pad |
| A / B | X or K / Z or J | A / B | A / B |
| Start / Select | Enter / Shift | START / SELECT | Start / Back |
| Orbit camera | Drag | Drag | |
| Zoom | Wheel | Pinch | |
| Reset camera | C | | |
| Show the 2D screen | Tab | | |
| Save / load state | F5 / F9 | | |

Movement is camera-relative, so "up" always walks away from the camera.

## Limits

- Only the room Link is in has live NPCs and enemies. Neighbouring rooms show their
  terrain but are empty until you walk in (the game only runs one room at a time).
- Heights are inferred from the 2D art, so a few tiles get the wrong shape.
- Interiors and dungeons show one room at a time; moving between them still uses the
  game's (fast-forwarded) transitions.
- The emulator core is [WasmBoy](https://github.com/torch2424/wasmboy) (GPL-3.0), so a
  build you distribute is GPL-3.0 as well.
