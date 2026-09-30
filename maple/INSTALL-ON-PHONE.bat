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
echo Plug in your phone with USB debugging on. Installing (1.9 GB, takes a minute)...
"%ADB%" install -r "MapleV83.apk"
pause
