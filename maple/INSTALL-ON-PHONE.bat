@echo off
setlocal
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "MapleV83.apk" (
  echo Run BUILD-APK.bat first.
  pause
  exit /b 1
)
set /p VER=<VERSION
echo Installing MapleV83.apk version %VER%.
echo Plug in your phone with USB debugging on.
echo Removing any older copy first (this also clears its old save)...
"%ADB%" uninstall com.mapleoffline.v83 >nul 2>nul
"%ADB%" install "MapleV83.apk"
if errorlevel 1 (
  echo INSTALL FAILED - see the message above.
) else (
  echo Installed version %VER%. Open "Maple v83" on the phone.
)
pause
