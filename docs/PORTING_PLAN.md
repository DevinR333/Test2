# Porting plan: Walkabout Mini Golf (Quest) → Android phone, near 1:1

Personal-use port of a copy you own. Goal: the same game as on Quest, with touch controls instead
of VR hands. Don't commit any game files (bundles, APKs, exports, decompiled code) to this public repo.

## Why this approach

The Quest build is IL2CPP: the game's C# was compiled to ARM64 machine code (`libil2cpp.so`), so
there is no source to recover automatically. What *can* be recovered:

| Piece | Source | Result |
| --- | --- | --- |
| Courses, models, audio, lost-ball spots | asset bundles (AssetRipper) | exact |
| Script names + saved settings (physics values, pars, speeds) | `global-metadata.dat` + bundles (AssetRipper / Cpp2IL) | exact |
| Gameplay logic | `libil2cpp.so` → Ghidra pseudocode → hand/AI translation to C# | behaves like the original |
| Shaders | compiled GPU code | rewrite from material settings (or AssetRipper Premium) |
| Baked lighting | ASTC HDR lightmaps (not convertible yet) | re-bake in Unity |

The key trick: **fill in the game's own empty script files** (same class names, namespaces and
fields) with translated code. Every scene and prefab already points at those scripts, so they
start working in place without re-wiring anything.

## Phase 0: Work locally

Run Claude Code **on the PC** (desktop app or CLI) in `C:\walkabout`, so it can open the files and run
the tools itself. Install:
- Unity 6000.3.9f1 with Android Build Support (already done)
- JDK 21 (for Ghidra), .NET 8 SDK (to build tools), Python 3
- Ghidra 11.x (ghidra-sre.org)
- AssetRipper 2.0.0 or newer

## Phase 1: Clean export with real script data

1. Extract `base.apk` (rename a copy to .zip) into `C:\tt_struct` so it contains `assets\` and `lib\`.
2. Unzip the main `.obb`; copy everything from its `assets\aa\Android\` plus the Tourist Trap
   bundles (`touristtrapcommon_*`) into `C:\tt_struct\assets\aa\Android\`.
3. AssetRipper → Open Folder `C:\tt_struct`. The log must **not** say "Mixed game structure" or
   "Unknown scripting backend"; it should detect Il2Cpp and run Cpp2IL.
4. Export Unity Project → `C:\tt_export5`.
5. Check: `Assets\Scripts\Assembly-CSharp\Ball.cs` should now list fields, not the
   "Dummy class" note. If it still says Dummy, stop and fix this phase first; everything after depends on it.

## Phase 2: Fix the project foundation

The export contains stub copies of Unity packages. Replace them with the real packages:
1. Delete the stub folders under `Assets\Scripts\` for: Unity.RenderPipelines.* (URP),
   Unity.TextMeshPro, Cinemachine, Unity.InputSystem, Unity.Addressables, Unity.Timeline,
   DOTween, Unity.Splines, Unity.Mathematics, Unity.Burst, Unity.Collections.
2. Install the real ones with Package Manager at the versions the game used (URP 17.3.x for
   Unity 6.3, Cinemachine 2.x since the game uses `CinemachineVirtualCamera`, TextMeshPro, etc.).
   Check that scene references survive the swap (components like Cinemachine cameras or TMP text
   shouldn't turn into "Missing script"). If they do, the stub and package GUIDs differ and the
   references need remapping; a small editor script can rewrite the GUIDs in the scene files.
3. Remove VR/online-only code paths: Oculus.*, Meta.XR.*, Unity.XR.*, Photon*, PlayFab, LIV,
   Unity.Services.* (leave the game's own classes that reference them as stubs for now).
4. Assign the URP asset in Graphics settings. Materials then use URP, as in the original.

## Phase 3: Symbols for the decompiler

1. From `base.apk`: `lib\arm64-v8a\libil2cpp.so` and `assets\bin\Data\Managed\Metadata\global-metadata.dat`.
2. Build Il2CppDumper from the open pull request that adds metadata v35–v39:
   https://github.com/Perfare/Il2CppDumper/pull/903 (`dotnet build -c Release`).
   Run it on the two files → `script.json`, `il2cpp.h`, `dump.cs`, `ghidra_with_struct.py`.
   (Alternative: Cpp2IL, which supports v39 since pre-release 21.)
3. `dump.cs` is the full map: every class, field, method signature and **address**.

## Phase 4: Ghidra

1. New project → import `libil2cpp.so` (AArch64, little endian) → auto-analyse (can take hours).
2. Script Manager → run `ghidra_with_struct.py` with `script.json` and `il2cpp.h`. Functions get
   real names (`Mighty.Balls.GolfBall$$OnCollisionEnter`) and typed structs.
3. Optional: install GhidraMCP so the local Claude can query functions directly instead of
   copy-pasting.

## Phase 5: Translate the gameplay, class by class

For each class: decompile every method in Ghidra → translate to C# → write it into the matching
stub file in the export (keep namespace, class name and fields identical) → compile → test.

Order (each builds on the last):
1. **Ball physics**: `Ball`, `Mighty.Balls.GolfBall`, `GolfBallPhysicsModifier`,
   `BallLaunchSettings`, `BallPhysicsMatieralSettings`, `BallMinimumSpeed`, `BallSpeedLimiter`,
   `SurfaceManager`, `Surface`, `InPlaySurface`, `KeepAliveTrigger`
2. **Putting**: `Mighty.Modifiers.ToolModifiers.Putter`, `PutterView`, `ToolManager`
3. **Holes and scoring**: `Hole`, `Cup`, `Mighty.Holes.MightyCup`, `HoleComplete`, `HoleSelector`,
   `GolfStandardActivity`, `GolfScoring`, `GolfTurns`, `Course`, `CourseData`
4. **Course mechanics**: `BallInPipe`, `BallTeleporter`, `BallPortal`, `MightyBouncinessTrigger`,
   `BallConstantForce`, `MightyConstantForce`, `MovingObject`, `RotatingObject`,
   `WaypointMover`, `WaypointAnimationMover`, `SineMover`, `WindTrigger`, `Mighty.Switchables.*`
5. **Lost balls**: `LostBall`, `ScaleUpLostBall`, `LostBallTray*`
6. **Touch controls**: the game already has a mobile/AR mode. Translate `Mighty.Mobile.DragController`,
   `TouchDragLIne`, `Mighty.AR.FollowBallCamera`, `ARRadialMenuToggleTouchPuttButton`, and use
   them instead of writing new controls. Add first-person walking (the one non-original part).
7. **Course loading, saving, menus**: `GameManager`, `CourseMenu`, `LocalSaveManager`,
   `MightyDataManager` (skip cloud saves, IAP, multiplayer).

Engine calls in the pseudocode (Rigidbody, Physics, Transform…) map straight back to Unity API calls,
so most methods translate cleanly. IL2CPP runtime helpers (null checks, `il2cpp_codegen_*`, class
init) are noise and get dropped.

## Phase 6: Prove it matches (optional, for "1:1")

Hook the original game on the Quest with Frida (frida-gadget injected into a repacked APK; no root
needed) and log inputs/outputs of key functions while playing: e.g. putter swing speed in → ball
velocity out, cup sink checks, bounce responses. Feed the same inputs to the translated code and
compare. Fix any differences.

## Phase 7: Look

1. Rewrite the custom shaders in URP Shader Graph or HLSL from the material properties
   (e.g. sand: `_SandColor`, `_TexDots`, `_TexNoise`, sun rim light, sunlight boost, height fog).
   AssetRipper Premium's shader decompiler may shortcut this.
2. Re-bake lighting in Unity (the original ASTC HDR lightmaps can't be converted yet).
3. Compare screenshots side by side with the Quest and tune.

## Phase 8: Android build

Switch to Android, IL2CPP, ARM64, landscape; Vulkan + GLES3. Build and install over USB.
Then repeat Phase 1 (bundles only) for the other courses.

## Effort

Weeks of steady work. Phases 1–4 are setup (a few days); Phase 5 is most of the work.
The result is working code that behaves like the original, not the studio's exact source.
