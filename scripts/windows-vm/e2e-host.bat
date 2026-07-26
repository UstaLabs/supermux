@echo off
setlocal EnableExtensions
set REPORT=C:\Windows\Temp\e2e-full.txt
set MUX_HOME=C:\Windows\Temp\supermux-e2e-home
set MUX_STATE_DIR=%MUX_HOME%\state
set MUX_WEB_PORT=18791
set MUX_WEB_PUBLIC_URL=http://127.0.0.1:18791
set MUX_SESSIOND_PATH=C:\tools\run-sessiond.cmd
set BUN=C:\tools\bun-x64-baseline\bun-windows-x64-baseline\bun.exe
set BROKER=C:\supermux-source\apps\desktop\resources\windows-x64\supermux-broker.exe
set STUB=%MUX_HOME%\stubbin
> "%REPORT%" echo start

if exist "%MUX_HOME%" rmdir /s /q "%MUX_HOME%"
mkdir "%MUX_STATE_DIR%" 2>nul
mkdir "%STUB%" 2>nul
> "%STUB%\claude.cmd" echo @echo off
>> "%STUB%\claude.cmd" echo powershell.exe -NoProfile -Command "Start-Sleep -Seconds 600"
set PATH=%STUB%;C:\tools\bun-x64-baseline\bun-windows-x64-baseline;%SystemRoot%\System32;%PATH%
>> "%REPORT%" echo PATH_OK

findstr /C:"detached: true" C:\supermux-source\src\core\sessiond\session-store.ts >nul
if not errorlevel 1 (
  >> "%REPORT%" echo FAIL session-store has detached:true
  goto end
)
>> "%REPORT%" echo SESSION_STORE_OK

if not exist "%BROKER%" ( >> "%REPORT%" echo FAIL missing broker & goto end )
if not exist "%BUN%" ( >> "%REPORT%" echo FAIL missing bun & goto end )
if not exist "%MUX_SESSIOND_PATH%" ( >> "%REPORT%" echo FAIL missing sessiond wrapper & goto end )

REM Start sessiond in background with working redirect
start /B cmd /c ""%BUN%" "C:\supermux-source\src\core\sessiond\main.ts" --state-dir "%MUX_STATE_DIR%" > C:\Windows\Temp\sessiond-e2e.log 2>&1"
ping -n 5 127.0.0.1 >nul
>> "%REPORT%" echo SESSIOND_STARTED

REM Start broker in background - inherits MUX_* and PATH from this bat
start /B cmd /c ""%BROKER%" > C:\Windows\Temp\broker-e2e.log 2>&1"
>> "%REPORT%" echo BROKER_STARTED

powershell -NoLogo -NoProfile -NonInteractive -Command "$d=(Get-Date).AddSeconds(50); $ok=$false; while((Get-Date) -lt $d){ try { $r=Invoke-RestMethod -Uri http://127.0.0.1:18791/host -TimeoutSec 2; if($r.hostId){ Add-Content -Path '%REPORT%' -Value ('HOST_ID='+$r.hostId); $ok=$true; break } } catch {}; Start-Sleep -Milliseconds 400 }; if(-not $ok){ Add-Content -Path '%REPORT%' -Value 'FAIL host not ready'; if(Test-Path C:\Windows\Temp\broker-e2e.log){ Get-Content C:\Windows\Temp\broker-e2e.log -Tail 60 | Add-Content '%REPORT%' }; if(Test-Path C:\Windows\Temp\sessiond-e2e.log){ Get-Content C:\Windows\Temp\sessiond-e2e.log -Tail 40 | Add-Content '%REPORT%' }; exit 2 }"
if errorlevel 1 goto cleanup

powershell -NoLogo -NoProfile -NonInteractive -Command "$pipes=@(Get-ChildItem \\.\pipe\ -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'supermux-*' } | ForEach-Object { $_.Name }); Add-Content -Path '%REPORT%' -Value ('SESSIOND_PIPES='+($pipes -join ',')); if($pipes.Count -eq 0){ Add-Content -Path '%REPORT%' -Value 'FAIL no pipes'; exit 3 }"
if errorlevel 1 goto cleanup

>> "%REPORT%" echo SUPERMUX_WINDOWS_BOOT_OK

cd /d C:\supermux-source
"%BUN%" e2e-term-probe.ts > C:\Windows\Temp\e2e-term-console.txt 2>&1
if exist C:\Windows\Temp\e2e-term.txt type C:\Windows\Temp\e2e-term.txt >> "%REPORT%"
findstr /C:"SUPERMUX_TERM_E2E_OK" C:\Windows\Temp\e2e-term.txt >nul 2>&1
if errorlevel 1 (
  >> "%REPORT%" echo FAIL term probe
  if exist C:\Windows\Temp\e2e-term-console.txt type C:\Windows\Temp\e2e-term-console.txt >> "%REPORT%"
  goto cleanup
)
>> "%REPORT%" echo SUPERMUX_TERM_E2E_OK
>> "%REPORT%" echo SUPERMUX_WINDOWS_E2E_OK

:cleanup
taskkill /F /IM supermux-broker.exe >nul 2>&1
taskkill /F /IM bun.exe >nul 2>&1
>> "%REPORT%" echo DONE
:end
endlocal
