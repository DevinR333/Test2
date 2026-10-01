@echo off
setlocal
cd /d "%~dp0"
set "WZ=C:\msclassicv83\MapleStory"
if not "%~1"=="" set "WZ=%~1"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
echo Starting the PC version (output is also saved to play-log.txt)...
call gradlew.bat desktop:run "-PwzDir=%WZ%" %MAPLE_EXTRA% > play-log.txt 2>&1
type play-log.txt
echo.
echo If something went wrong, send play-log.txt (and crash-report.log if it exists) from this folder.
pause
