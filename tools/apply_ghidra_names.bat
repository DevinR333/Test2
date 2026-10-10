@echo off
setlocal
rem Applies Il2CppDumper's names/types/signatures to the Ghidra project C:\decomp\walkabout
rem without touching the Ghidra window (headless). Close Ghidra before running.

set "RAW=https://raw.githubusercontent.com/DevinR333/Test2/claude/walkabout-golf-android-recompile-ei9o9o/tools/ghidra"
set "GHIDRA="
for /d %%G in (C:\decomp\ghidra_*_PUBLIC) do set "GHIDRA=%%G"
if not defined GHIDRA (
    echo Could not find Ghidra in C:\decomp. Run setup_decompile.bat first.
    pause & exit /b 1
)
if not exist C:\decomp\walkabout.gpr (
    echo Could not find the Ghidra project C:\decomp\walkabout.gpr
    pause & exit /b 1
)
if not exist C:\decomp\il2cpp_out\script.json (
    echo Could not find C:\decomp\il2cpp_out\script.json
    pause & exit /b 1
)
if exist C:\decomp\walkabout.lock (
    echo Ghidra still has the project open. Close ALL Ghidra windows, then press a key.
    pause
)

if not exist C:\decomp\scripts mkdir C:\decomp\scripts
curl -sSfL -o C:\decomp\scripts\ApplyIl2CppDump.java "%RAW%/ApplyIl2CppDump.java"
if errorlevel 1 (
    echo Download failed. Check the internet connection and run this again.
    pause & exit /b 1
)

echo.
echo Applying names and types. This can take 30-90 minutes. Leave this window open.
echo.
set "GHIDRA_HEADLESS_MAXMEM=16G"
call "%GHIDRA%\support\analyzeHeadless.bat" C:\decomp walkabout -process libil2cpp.so -noanalysis ^
    -scriptPath C:\decomp\scripts -postScript ApplyIl2CppDump.java C:\decomp\il2cpp_out ^
    -scriptlog C:\decomp\apply_names_log.txt

echo.
findstr /c:"Script finished!" C:\decomp\apply_names_log.txt >nul
if errorlevel 1 (
    echo FAILED. Send Claude a screenshot of this window.
) else (
    echo DONE. Open Ghidra, open libil2cpp.so, and look in Symbol Tree - Functions.
)
pause
