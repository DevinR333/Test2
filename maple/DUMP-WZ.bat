@echo off
setlocal
cd /d "%~dp0"
set "WZ=C:\msclassicv83\MapleStory"
if not "%~1"=="" set "WZ=%~1"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
echo Reading the structure of your WZ files (names, positions, sizes - no pictures or sounds)...
call gradlew.bat -q desktop:wzdump "-PwzDir=%WZ%"
if errorlevel 1 (
  echo FAILED - scroll up for the reason.
  pause
  exit /b 1
)
echo.
echo Done. Send wz-structure.txt from this folder.
explorer /select,"%cd%\wz-structure.txt"
pause
