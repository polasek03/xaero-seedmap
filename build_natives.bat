@echo off
if not exist cubiomes\generator.h (
    echo ERROR: cubiomes submodule not found. Run: git submodule update --init
    exit /b 1
)

set DLL=src\main\resources\natives\windows\cubiomes.dll
del "%DLL%" 2>nul

:: Snapshot all running cmd/mintty PIDs before we start
powershell -noprofile -command "(Get-Process -Name cmd,mintty -EA SilentlyContinue).Id -join ' ' | Out-File '%TEMP%\cubiomes_pids_before.txt' -Encoding ASCII"

start "" C:\msys64\msys2_shell.cmd -ucrt64 -defterm -no-start -here -c "bash build_natives.sh"

echo Building...
set TIMEOUT=120
:WAIT
timeout /t 1 /nobreak >nul
if exist "%DLL%" goto SUCCESS
set /a TIMEOUT-=1
if %TIMEOUT% gtr 0 goto WAIT
echo Build timed out.
exit /b 1

:SUCCESS
:: Kill any cmd/mintty processes that weren't running before we started
powershell -noprofile -command "$b = ((gc '%TEMP%\cubiomes_pids_before.txt') -split ' ' | Where-Object {$_}); Get-Process -Name cmd,mintty -EA SilentlyContinue | Where-Object { $b -notcontains [string]$_.Id } | Stop-Process -Force -EA SilentlyContinue"
echo Build successful: %DLL%
