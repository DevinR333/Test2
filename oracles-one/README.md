# Oracles One

*Oracle of Seasons* and *Oracle of Ages* as **one game**: a new native engine (C + SDL3) for PC and
Android. It reads the worlds, graphics and rules from the
[oracles-disasm](https://github.com/Stewmath/oracles-disasm) disassembly. Nothing runs Game Boy code
or emulates the hardware.

- **One seamless world.** The rooms are laid edge to edge, so walking off a screen just keeps
  going. No screen-by-screen scrolling, and the camera eases after Link.
- **Any screen shape.** Fill the screen, 16:9 or 4:3 (F2). Pinch, the mouse wheel, +/- or the
  shoulder buttons zoom from close-up out to a large part of the map.
- **Holodrum in its seasons.** Every area shows its own default season, as in Seasons. The Rod of
  Seasons changes the screen you stand on.
- **A new house in each main town** connects Holodrum and present-day Labrynna. Its front door in
  Horon Village opens into Lynna City and back. Each house copies a small house already in that
  town, so it looks and blocks like the originals.
- **One Link.** Hearts, rupees, items, rings, seeds and bombs are shared. Once you have all 16
  hearts, heart containers give 300 rupees and pieces of heart give 150.
- **Every item works in both worlds.** Item code never asks which world it's in, so Roc's Cape
  works in Labrynna and the Magnetic Gloves work in Holodrum.
- **Both games' item pages in one Start menu**, drawn from the originals' own inventory screens,
  cursor and font. The page of the world you're in comes first: Seasons' page in Holodrum,
  Ages' page in Labrynna. Select slides to the other one, and A or B equips the highlighted
  item.
- **A normal game from the start**, not a linked one.
- **Linked secrets without typing** (`src/secrets.c`). Once someone tells you a secret, walk up to
  the person who takes it and they recognise it and give their reward. All 20 originals' secrets
  are there, with their rewards taken from the original scripts.
- **Floating HUD** like *The Minish Cap*: hearts at top left, B and A at top right, rupees at
  bottom right. There's no status bar.
- **Place names.** Walking into a new area fades its name in and out, using the names the
  originals' map screen gives each screen ("Horon Village", "Maku Tree"...).

| Horon Village's new house (to Labrynna) | Lynna City's new house (to Holodrum) |
| --- | --- |
| ![Horon Village](docs/screenshots/house_horon.png) | ![Lynna City](docs/screenshots/house_lynna.png) |
| **Start in Holodrum: Seasons' page first** | **Start in Labrynna: Ages' page first** |
| ![Seasons page](docs/screenshots/menu_seasons.png) | ![Ages page](docs/screenshots/menu_ages.png) |
| **Arriving in Horon Village** | **4:3, zoomed out** |
| ![Holodrum](docs/screenshots/holodrum.png) | ![4:3](docs/screenshots/aspect_4_3_zoomed_out.png) |

![Touch controls on a 20:9 phone](docs/screenshots/touch.png)

## Status

| In the game | Still simplified |
| --- | --- |
| Every place in both games (423 areas: Holodrum in its seasons, Subrosia, Labrynna present and past, every house, cave and dungeon floor), all 1,098 warps, all 241 chests | Cutscenes, and scripted story events beyond who appears and what they say |
| Every character and enemy the originals place, with their own sprites (4,315 objects); characters say their original lines | Enemies act by family (shooters, fliers, hoppers, chargers, burrowers) rather than each one's exact code |
| Every item of both games, in both worlds: sword, shield, bombs, bombchus, boomerang, satchel / slingshot / shooter with all five seeds (Gale Seeds fly to the originals' trees), Roc's Feather and Cape, Rod of Seasons, Harp of Ages (present and past), Switch Hook, Cane of Somaria, Magnetic Gloves, shovel, bracelet and gloves, Fool's Ore, flippers / mermaid suit, and the Flute's Ricky, Dimitri and Moosh (drawn under Link, each with their ability) | Companions' own moves (Ricky's punches, Dimitri eating, Moosh's stomp) |
| Who appears and what they say follow the story: Horon Village's and the Sunken City's people by the originals' stages, Ages' villagers by their progress tables | |
| Cutting, lifting and throwing, ledges, holes, water, lava, conveyors, push blocks, bombable walls, digging, burning, signs; side-view rooms with gravity, ladders and jumps | |
| Shutters close behind Link until a room's enemies are beaten; chests the originals make appear show up then | |
| Bosses give a heart container and their essence; Onox and Veran end their games; Twinrova and Ganon wait in the Room of Rites until both are beaten | |
| The 20 linked secrets: the linked NPCs tell them, their takers reward them, no typing | |
| Link's own animations from the originals (walking, sword, lifting, swimming, jumping, falling, drowning) | |
| Both games' music in every room and their sound effects, from their own sound data | |
| Shops at their original prices (Subrosia's for ore chunks), and the story items characters hand over: the Rod of Seasons, the Harp's tunes, flippers, Mermaid Suit, Pirate's Bell, keys, Island Chart... | Overworld keyholes are already open (the keys aren't needed) |
| Sword: spin attack (hold, release), beams from the Noble and Master Swords at full health; the trading sequence up to the Biggoron's Sword; riding, Ricky punches, Dimitri bites and Moosh stomps | |
| Rings: a ring page in the Start menu (the originals' names and descriptions) and their effects; rings you wear go in the ring box, and Select during play swaps through them on the fly | Minigames (their prizes come from talking to their hosts) |
| Gasha Seeds: plant them in Gasha spots, beat monsters, harvest the nut (the originals' prize odds by spot and progress) | |
| Shared hearts, rupees, items, rings, seeds and bombs; extra hearts become rupees | |

`--all-items` gives every item of both games, for testing.

## Build

The game data is generated from the disassembly (a git submodule) and is not committed.

```bash
git submodule update --init oracles-one/disasm
cd oracles-one
pip install pillow
tools/build_assets.sh                 # writes assets/
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release   # fetches SDL3 if it isn't installed
cmake --build build
ctest --test-dir build
./build/oracles-one
```

Controls: arrows or WASD move, X is A, Z is B, Enter is Start, Tab or Right Shift is Select (in play:
swap rings),
+/- or the mouse wheel zoom, F2 changes the aspect ratio. Gamepads use their usual layout, with
the shoulder buttons zooming.

### Android APK in one step

`Build APK.exe` (Windows, double-click; it runs `build_apk.bat`) or `./build_apk.sh` (macOS/Linux)
downloads Java, the
Android SDK and NDK, and the disassembly, builds the game data, and writes `OraclesOne.apk`
(see `READ-ME-FIRST.txt`). `tools/make_zip.sh` compiles `Build APK.exe` (from
`tools/launcher/`, with MinGW) and packs it, the source and both scripts into
`dist/OraclesOne-source.zip`.

The on-screen touch controls can be turned off: in the inventory, tap TOUCH CONTROLS above the
page or press X/Y (T on a keyboard). The choice is kept in `settings.txt` in the app's folder.

Controllers work over USB or Bluetooth (Xbox, PlayStation, Switch Pro, 8BitDo...), as do
handhelds' built-in controls (Retroid Pocket, AYN, AYANEO). Face buttons go by their printed
label, so A is the button marked A; PlayStation's cross is A. Tested with SDL's virtual pads in
`tests/test_input.c`.

### Android Studio

Run `tools/build_assets.sh` first. The APK packages `assets/`.

1. Android Studio → **File → Open** → `oracles-one/android`.
2. Let Gradle sync. It needs NDK 27.2.12479018 and CMake, and installs them if they're missing.
   The first build also downloads SDL3's source.
3. Run on a phone, or **Build → Build APK(s)**. It's arm64 only (any recent phone, such as a
   Snapdragon 8 Gen 2).

The game runs in landscape. Touch controls show the d-pad on the left, A/B on the right, and
Select/Start at the bottom. Pinch with two fingers anywhere else to zoom.

### Headless screenshots

```bash
SDL_VIDEO_DRIVER=offscreen SDL_RENDER_DRIVER=software ./build/oracles-one \
  --shot out.bmp --frames 120 --size 1280x720 --world labrynna --pos 1176,790 --hold U
```

This holds Up for 120 frames from just south of Lynna City's new house, so it goes through the
door into Holodrum. Other options: `--aspect 4:3|16:9`, `--zoom Z`, `--menu 0|1` (open the menu on
its first or second page), `--touch`, `--all-items`.

## Layout

- `tools/extract_world.py`: room layouts, tilesets, graphics and palettes from the disassembly
  become `metatiles.rgba` (every distinct 16x16 tile), `areas.bin` (every overworld, dungeon floor
  and room of both games: atlas index, collision byte and original metatile per tile, and each
  screen's room number and map-screen name) and `warps.bin` (both games' warp tables). Holodrum
  is also written in each season, for the rod.
- `tools/extract_sprites.py`: Link, the HUD art, both games' item icons, their original item
  pages, the inventory cursor and the font, in the games' palettes.
- `src/world.c`: areas, drawing, the originals' collision rules (`checkGivenCollision_allowHoles`)
  and their warps (warp tiles, and the top/bottom screen-edge warps of `findScreenEdgeWarpSource`).
- `tools/extract_objects.py`: every placed character, enemy and part with its sprites, stats, first
  line of dialogue and linked secret. `tools/extract_audio.py`: both games' music and effects.
- `src/actors.c`: characters, enemies and bosses. `src/terrain.c`: what Link does with the ground.
  `src/tiles.c`: the tile property tables. `src/audio.c`: the sound engine and synth.
- `src/link.c`: walking and collision. `src/items.c`: using items. `src/game.c`: the shared save
  and its rules. `src/treasure.c`: treasures by the originals' numbers, chests, keys and locked doors.
- `src/hud.c`: the HUD and the Start menu with both games' item pages. `src/input.c`: keyboard, gamepad, touch, pinch.
- `src/main.c`: the loop, camera, zoom, aspect ratios, the town houses and screenshot mode.

Not affiliated with Nintendo or Capcom. The data comes from the community disassembly.
