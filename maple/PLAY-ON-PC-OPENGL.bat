@echo off
rem Same as PLAY-ON-PC.bat but draws with plain OpenGL instead of ANGLE/DirectX.
rem Use this at the PC itself, or over Remote Desktop once the "Use hardware graphics adapters
rem for all Remote Desktop Services sessions" policy is enabled.
set "MAPLE_EXTRA=-Pgl=opengl"
call "%~dp0PLAY-ON-PC.bat" %*
