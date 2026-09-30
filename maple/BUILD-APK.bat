@echo off
setlocal
cd /d "%~dp0"
title Build Maple v83 APK

rem ---- Where are your .wz files? (default C:\msclass, or pass a folder / drag it onto this file)
set "WZ=C:\msclass"
if not "%~1"=="" set "WZ=%~1"

call :findjava || goto :fail

if not exist "%WZ%\Map.wz" (
  echo Could not find Map.wz in %WZ%
  set /p "WZ=Drag your MapleStory folder into this window and press Enter: "
)
set "WZ=%WZ:"=%"
if not exist "%WZ%\Map.wz" (
  echo Still no Map.wz in "%WZ%".
  goto :fail
)

set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%\platforms" (
  echo Android SDK not found at %SDK%
  echo Open Android Studio once so it installs the SDK, then run this again.
  goto :fail
)

echo.
echo Building the APK with the game files from: %WZ%
echo This packs about 1.9 GB of game data, so the first build takes a few minutes.
echo.
call gradlew.bat android:assembleDebug "-PwzDir=%WZ%"
if errorlevel 1 goto :fail

copy /y "android\build\outputs\apk\debug\android-debug.apk" "MapleV83.apk" >nul
echo.
echo ============================================================
echo  DONE:  %cd%\MapleV83.apk
echo  Install it: plug in your phone and run INSTALL-ON-PHONE.bat,
echo  or copy MapleV83.apk to the phone and tap it.
echo ============================================================
explorer /select,"%cd%\MapleV83.apk"
pause
exit /b 0

:findjava
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" exit /b 0
for %%J in ("%ProgramFiles%\Android\Android Studio\jbr" "%LOCALAPPDATA%\Programs\Android Studio\jbr" "%ProgramFiles%\Android\Android Studio\jre") do (
  if exist "%%~J\bin\java.exe" (
    set "JAVA_HOME=%%~J"
    exit /b 0
  )
)
where java >nul 2>nul && exit /b 0
echo Java not found. Install Android Studio (it includes Java), then run this again.
exit /b 1

:fail
echo.
echo BUILD FAILED - scroll up for the reason.
pause
exit /b 1
