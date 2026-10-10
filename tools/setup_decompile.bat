@echo off
title Set up decompile tools
powershell -NoProfile -ExecutionPolicy Bypass -Command "iwr -UseBasicParsing 'https://raw.githubusercontent.com/DevinR333/Test2/claude/walkabout-golf-android-recompile-ei9o9o/tools/setup_decompile.ps1' -OutFile $env:TEMP\setup_decompile.ps1; & $env:TEMP\setup_decompile.ps1"
pause
