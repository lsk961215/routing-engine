@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\servers-windows.ps1" stop
set "STOP_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %STOP_RESULT%
