@echo off
rem Builds OraclesOne.apk on Windows: double-click this file.
rem
rem Needs Git and Python 3 installed (it checks and tells you where to get them) and an internet
rem connection. Everything else (Java, the Android SDK and NDK, SDL, the oracles-disasm data) is
rem downloaded into .buildtools and disasm next to this file. The first run takes 15-30 minutes;
rem later runs reuse the downloads.
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "ROOT=%CD%"
set "BT=%ROOT%\.buildtools"
set "DISASM_COMMIT=21c924afdc8a13225e849214479d5113da2ec226"
set "CMDLINE_TOOLS=commandlinetools-win-11076708_latest.zip"

echo === Oracles One APK builder ===
echo Building in %ROOT%
if not "%ROOT:~60%"=="" (
  echo.
  echo WARNING: this folder's path is long. The Android build can fail on Windows with long paths.
  echo If it does, move this folder somewhere short like C:\oracles and run this again.
  echo.
)
if not exist "%BT%" mkdir "%BT%"

rem --- Git ---------------------------------------------------------------------------------
where git >nul 2>nul || (
  echo Git is not installed. Install it from https://git-scm.com/download/win and run this again.
  goto :fail
)

rem --- Python 3 --------------------------------------------------------------------------
set "PY="
py -3 --version >nul 2>nul && set "PY=py -3"
if not defined PY python --version >nul 2>nul && set "PY=python"
if not defined PY (
  echo Python 3 is not installed. Install it from https://www.python.org/downloads/
  echo ^(tick "Add python.exe to PATH"^) and run this again.
  goto :fail
)

rem --- Java 17 (downloaded if needed) ------------------------------------------------------
if not exist "%BT%\jdk\bin\java.exe" (
  echo Downloading Java 17...
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%BT%\jdk.zip'" || goto :fail
  powershell -NoProfile -Command "Expand-Archive -Force '%BT%\jdk.zip' '%BT%\jdk-unzip'" || goto :fail
  for /d %%d in ("%BT%\jdk-unzip\*") do move "%%d" "%BT%\jdk" >nul
  rmdir /s /q "%BT%\jdk-unzip"
  del "%BT%\jdk.zip"
)
set "JAVA_HOME=%BT%\jdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem --- Android SDK, NDK and CMake (downloaded if needed) ------------------------------------
set "SDK=%BT%\android-sdk"
set "SDKMANAGER=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDKMANAGER%" (
  echo Downloading the Android command-line tools...
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://dl.google.com/android/repository/%CMDLINE_TOOLS%' -OutFile '%BT%\cmdline-tools.zip'" || goto :fail
  powershell -NoProfile -Command "Expand-Archive -Force '%BT%\cmdline-tools.zip' '%SDK%\cmdline-tools-unzip'" || goto :fail
  mkdir "%SDK%\cmdline-tools" 2>nul
  move "%SDK%\cmdline-tools-unzip\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul
  rmdir /s /q "%SDK%\cmdline-tools-unzip"
  del "%BT%\cmdline-tools.zip"
)
echo Installing Android SDK parts (accepting their licences)...
(for /l %%i in (1,1,30) do @echo y) | "%SDKMANAGER%" --sdk_root="%SDK%" --licenses >nul
call "%SDKMANAGER%" --sdk_root="%SDK%" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1" "platform-tools" || goto :fail

rem --- The game data, from the oracles-disasm disassembly ------------------------------------
if not exist "%ROOT%\disasm\ages.s" (
  echo Downloading oracles-disasm...
  if exist "%ROOT%\disasm" rmdir /s /q "%ROOT%\disasm"
  git init -q "%ROOT%\disasm" || goto :fail
  git -C "%ROOT%\disasm" fetch -q --depth 1 https://github.com/Stewmath/oracles-disasm %DISASM_COMMIT% || goto :fail
  git -C "%ROOT%\disasm" checkout -q FETCH_HEAD || goto :fail
)
if not exist "%BT%\venv\Scripts\python.exe" (
  %PY% -m venv "%BT%\venv" || goto :fail
)
"%BT%\venv\Scripts\python.exe" -m pip install -q pillow || goto :fail
echo Building the game data...
if not exist "%ROOT%\assets" mkdir "%ROOT%\assets"
"%BT%\venv\Scripts\python.exe" "%ROOT%\tools\extract_world.py" "%ROOT%\disasm" "%ROOT%\assets" || goto :fail
"%BT%\venv\Scripts\python.exe" "%ROOT%\tools\extract_sprites.py" "%ROOT%\disasm" "%ROOT%\assets" || goto :fail
"%BT%\venv\Scripts\python.exe" "%ROOT%\tools\extract_objects.py" "%ROOT%\disasm" "%ROOT%\assets" || goto :fail
"%BT%\venv\Scripts\python.exe" "%ROOT%\tools\extract_audio.py" "%ROOT%\disasm" "%ROOT%\assets" || goto :fail

rem --- The APK -------------------------------------------------------------------------------
set "SDKFWD=%SDK:\=/%"
> "%ROOT%\android\local.properties" echo sdk.dir=%SDKFWD%
echo Building the APK...
pushd "%ROOT%\android"
call gradlew.bat assembleRelease --no-daemon || (popd & goto :fail)
popd
copy /y "%ROOT%\android\app\build\outputs\apk\release\app-release.apk" "%ROOT%\OraclesOne.apk" >nul || goto :fail

echo.
echo Done: %ROOT%\OraclesOne.apk
echo Copy it to your handheld or phone and open it to install
echo ^(allow installing from unknown sources when Android asks^).
pause
exit /b 0

:fail
echo.
echo The build stopped because of the error above.
pause
exit /b 1
