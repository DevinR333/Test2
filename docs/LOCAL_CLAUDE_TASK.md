# Task for Claude Code on the user's Windows PC

You are continuing a port of Walkabout Mini Golf (Mighty Coconut). The user bought the
game and is porting it for personal use. The goal is a near 1:1 Android phone build with
touch controls instead of VR motion controls. Read `docs/PORTING_PLAN.md` in
https://github.com/DevinR333/Test2 (branch `claude/walkabout-golf-android-recompile-ei9o9o`)
for the full plan.

## Rules from the user

- Do the work yourself. Run commands yourself, and don't hand the user manual steps
  unless there's no way around it. The user is often on a laggy phone remote desktop.
- If the user has to click something, give exact click-by-click steps.
- **Never commit or push game files to the GitHub repo. It is public.** That covers
  bundles, the APK, exports, decompiled output, the `.cs` script stubs and translated
  game code. Only original tools and scripts go in the repo.

## What already exists on this PC

| Path | What |
|---|---|
| `C:\decomp\ghidra_12.1.4_PUBLIC` | Ghidra 12.1.4 (JDK 21 is installed) |
| `C:\decomp\walkabout.gpr` | Ghidra project. It holds `libil2cpp.so` (ARM64, Unity 6000.3.9f1, IL2CPP, metadata v39), and auto-analysis is finished. |
| `C:\decomp\il2cpp_out` | Il2CppDumper (v39 fork) output: `script.json`, `il2cpp.h`, `dump.cs`, `stringliteral.json`, `DummyDll\` |
| `C:\decomp\scripts\ApplyIl2CppDump.java` | Java port of Il2CppDumper's `ghidra_with_struct.py`, because the `.py` is Python 2 and won't run in Ghidra 12. Source: `tools/ghidra/ApplyIl2CppDump.java` in the repo. |
| `C:\decomp\apply_names_log.txt` | Script log from the last run, which **threw an exception** |
| `C:\tt_export5\ExportedProject` | AssetRipper 2.0.0 Unity export of the Tourist Trap course. It has the real class, field and method declarations with **empty method bodies** (for example `Ball : GolfBall`). |
| `C:\tt_struct` | Extracted base.apk plus bundles that AssetRipper used |

## Step 1: get the names and types into the Ghidra project

1. Read `C:\decomp\apply_names_log.txt` and find the exception.
2. Fix `tools/ghidra/ApplyIl2CppDump.java`. Commit and push the fix to the repo branch; this
   file is original code, so it may go in the repo. Copy the fixed file to `C:\decomp\scripts\`.
   - If parsing `il2cpp.h` is what fails, that's a known pain point in Ghidra. Try parsing it
     with args such as `-D_MSC_VER` or none, or skip the header and apply only names. Names
     matter most; signatures and types are a bonus.
3. Make sure Ghidra is closed, then run:
   `set GHIDRA_HEADLESS_MAXMEM=16G` and
   `C:\decomp\ghidra_12.1.4_PUBLIC\support\analyzeHeadless.bat C:\decomp walkabout -process libil2cpp.so -noanalysis -scriptPath C:\decomp\scripts -postScript ApplyIl2CppDump.java C:\decomp\il2cpp_out -scriptlog C:\decomp\apply_names_log.txt`
4. Repeat until the log ends with `Script finished!`.

## Step 2: export decompiled code for the game's own classes

Write a headless Java GhidraScript that decompiles every function whose name belongs to
the game's classes, using `DecompInterface`. Skip `UnityEngine.*`, `System.*`, `Unity.*`,
`TMPro.*` and other third-party namespaces. Use `dump.cs` to find out which classes are in
`Assembly-CSharp` and the game's other assemblies. Write the output to
`C:\decomp\decompiled\<Namespace>\<Class>.c` with one file per class, and put the RVA in a
comment above each method. This gives you plain text to translate from, so GhidraMCP is
optional. Set up GhidraMCP only if you want interactive lookups.

## Step 3: translate into the exported project

For each class, fill in the empty method bodies in the matching `.cs` file under
`C:\tt_export5\ExportedProject\Assets\Scripts\...`.

- Keep the class, namespace and field names exactly as they are, so the scenes stay linked.
- Translate IL2CPP idioms back to C#. Calls to `*_TypeInfo`, `il2cpp_codegen_*`,
  `MethodInfo*` parameters and null-check helpers should become normal C#.
- Do gameplay first: ball physics, putting, hole, cup and scoring, then lost balls, then
  course and level flow and menus.
- For touch controls, translate the game's own mobile and AR classes:
  `Mighty.Mobile.DragController`, `TouchDragLIne`, `Mighty.AR.FollowBallCamera` and
  `ARRadialMenuToggleTouchPuttButton`.
- After each batch, compile in Unity: open the project, or run Unity in `-batchmode` and
  check `Editor.log`. Keep it compiling.
- Track progress in `C:\decomp\progress.md`, with each class marked as done, partial or todo,
  so the work can continue across sessions.

## Step 4: build

Use the existing `MiniGolfMobile` editor tools (`Assets/MiniGolfMobile/Editor/PhoneBuild.cs`)
or a batchmode build to make `C:\walkabout\MiniGolf.apk`. The settings are IL2CPP, ARM64
and landscape.
