@echo off
rem Same as PLAY-ON-PC.bat but without sound and vsync, for PCs where the game window crashes.
set "MAPLE_EXTRA=-Psafe"
call "%~dp0PLAY-ON-PC.bat" %*
