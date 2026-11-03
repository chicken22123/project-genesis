@echo off
REM Builds dist\ObsidianClient-Setup-<version>.exe - an installer that sets up
REM Obsidian Client like the Modrinth App: desktop and Start menu shortcuts,
REM and an entry in Settings > Apps to uninstall it. Double-click the result to
REM install it on this PC, or send that one file to friends.
REM
REM Needs Python (for build.cmd) and Inno Setup 6, which is free. If Inno Setup
REM is missing this offers to install it with winget.
setlocal
cd /d "%~dp0"

call :find_iscc
if not defined ISCC (
    echo Inno Setup 6 makes the installer, and it is not installed yet.
    where winget >nul 2>nul
    if errorlevel 1 (
        echo Get it free from https://jrsoftware.org/isdl.php then run this again.
        goto :fail
    )
    choice /C YN /M "Install Inno Setup now with winget"
    if errorlevel 2 goto :fail
    winget install --id JRSoftware.InnoSetup -e --accept-package-agreements --accept-source-agreements
    call :find_iscc
)
if not defined ISCC (
    echo Inno Setup still was not found. Open a new terminal and run this again.
    goto :fail
)

REM Step 1: the app itself, into dist\Obsidian Client\.
call build.cmd
if errorlevel 1 goto :fail

REM Step 2: wrap it in an installer, stamped with the version from app.py.
set "VERSION="
for /f "tokens=2 delims==" %%a in ('findstr /b /c:"APP_VERSION = " app.py') do set "VERSION=%%a"
set "VERSION=%VERSION: =%"
set "VERSION=%VERSION:"=%"

echo.
echo Making the installer for version %VERSION%...
"%ISCC%" /Q "/DAppVersion=%VERSION%" "installer\obsidian-client.iss"
if errorlevel 1 goto :fail

echo.
echo Done. Your installer is:
echo   "%~dp0dist\ObsidianClient-Setup-%VERSION%.exe"
echo Double-click it to install Obsidian Client, or send that file to friends.
echo.
REM Pause so a double-clicked window stays open long enough to read this.
pause
exit /b 0

:fail
echo.
echo The installer was not made - the messages above say why.
pause
exit /b 1

:find_iscc
set "ISCC="
for %%p in (
    "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
    "%ProgramFiles%\Inno Setup 6\ISCC.exe"
    "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
) do if exist %%p set "ISCC=%%~p"
if not defined ISCC for /f "delims=" %%p in ('where ISCC.exe 2^>nul') do set "ISCC=%%p"
exit /b 0
