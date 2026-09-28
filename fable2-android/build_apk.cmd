@echo off
rem Double-click to build Fable2.apk from your own Fable 2 ISO.
rem Pass an ISO path to skip the file picker:  build_apk.cmd "D:\Fable2.iso"
rem See README.md for what this does.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_apk.ps1" %*
echo.
pause
