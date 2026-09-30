@echo off
setlocal
cd /d "%~dp0"
set "WZ=C:\msclassicv83\MapleStory"
if not "%~1"=="" set "WZ=%~1"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
call gradlew.bat desktop:run "-PwzDir=%WZ%"
pause
