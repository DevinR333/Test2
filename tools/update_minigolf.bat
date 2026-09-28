@echo off
title Update Mini Golf code
setlocal

rem Unity project to update (the Tourist Trap export). Drag a different ExportedProject folder onto this file to use that instead.
set "PROJECT=C:\tt_export3\ExportedProject"
if not "%~1"=="" set "PROJECT=%~1"

if not exist "%PROJECT%\Assets" (
  echo Can't find the Unity project at:
  echo   %PROJECT%
  echo Drag your ExportedProject folder onto this file instead.
  pause
  exit /b
)

echo Close Unity before continuing if it is open... (or leave it open, it will reload)
set "WORK=%TEMP%\minigolf_update"
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%"

echo Downloading the latest Mini Golf code...
curl -L -s -o "%WORK%\code.zip" "https://github.com/DevinR333/Test2/archive/refs/heads/claude/walkabout-golf-android-recompile-ei9o9o.zip"
if errorlevel 1 (
  echo Download failed. Check your internet connection.
  pause
  exit /b
)
tar -xf "%WORK%\code.zip" -C "%WORK%"

set "SRC="
for /d %%d in ("%WORK%\Test2-*") do set "SRC=%%d\unity\MiniGolfMobile"
if not exist "%SRC%\Runtime" (
  echo The download didn't contain the code. Tell Claude.
  pause
  exit /b
)

echo Removing old copies (including duplicates)...
for /d /r "%PROJECT%\Assets" %%d in (MiniGolfMobile*) do (
  if exist "%%d\Runtime" (
    if exist "%%d\Generated" (
      rem keep generated materials/scenes
      for /d %%s in ("%%d\*") do if /i not "%%~nxs"=="Generated" rmdir /s /q "%%s"
      del /q "%%d\*.*" 2>nul
    ) else (
      rmdir /s /q "%%d"
      del /q "%%d.meta" 2>nul
    )
  )
)

echo Installing the new code...
xcopy /e /i /q /y "%SRC%" "%PROJECT%\Assets\MiniGolfMobile" >nul

rmdir /s /q "%WORK%"
echo.
echo Done. In Unity: Mini Golf ^> Fix Everything, then press Play.
pause
