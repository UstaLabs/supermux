@echo off
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\Windows\Temp\auth-smoke.ps1 > C:\Windows\Temp\auth-smoke-console.txt 2>&1
echo EXIT=%ERRORLEVEL%>>C:\Windows\Temp\auth-smoke-console.txt
