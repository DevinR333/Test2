@echo off
title Pull Walkabout from Quest
setlocal

rem ADB comes with Android Studio's SDK
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "OUT=C:\walkabout"

if not exist "%ADB%" (
  echo ADB was not found at:
  echo   %ADB%
  echo.
  echo In Android Studio: More Actions / Tools - SDK Manager - SDK Tools tab
  echo tick "Android SDK Platform-Tools", click Apply, then run this again.
  pause
  exit /b
)

echo Looking for your Quest...
"%ADB%" get-state >nul 2>nul
if errorlevel 1 (
  echo.
  echo Quest not found. Check that:
  echo   - Developer Mode is on in the Meta Horizon phone app
  echo   - the Quest is plugged in with a data USB cable
  echo   - you pressed "Allow" on the USB debugging prompt INSIDE the headset
  echo.
  "%ADB%" devices
  pause
  exit /b
)
echo Quest connected.
echo.

set "PKG="
for /f "usebackq tokens=2 delims=:" %%p in (`"%ADB%" shell "pm list packages | grep -i walk"`) do if not defined PKG set "PKG=%%p"
if not defined PKG (
  echo Could not find Walkabout on the Quest. Is it installed?
  pause
  exit /b
)
echo Found game: %PKG%
echo.

if not exist "%OUT%" mkdir "%OUT%"

echo Copying the app files...
for /f "usebackq tokens=2 delims=:" %%a in (`"%ADB%" shell pm path %PKG%`) do "%ADB%" pull %%a "%OUT%"
echo.

echo Copying game data (can take several minutes)...
"%ADB%" pull /sdcard/Android/obb/%PKG% "%OUT%\obb"
"%ADB%" pull /sdcard/Android/data/%PKG% "%OUT%\data"
echo.

echo Done. Opening %OUT% ...
explorer "%OUT%"
pause
