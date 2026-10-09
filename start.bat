@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\servers-windows.ps1" start
set "SERVER_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %SERVER_RESULT%
