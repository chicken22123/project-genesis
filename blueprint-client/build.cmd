@echo off
REM Builds Blueprint Client into dist\Blueprint Client\Blueprint Client.exe
REM Windows only - PyInstaller cannot cross-compile a Windows exe elsewhere.
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if errorlevel 1 (
    echo The "py" launcher was not found. Install Python 3 from python.org
    echo and tick "Add Python to PATH", then run this again.
    exit /b 1
)

echo Installing/updating PyInstaller...
py -m pip install --upgrade pyinstaller
if errorlevel 1 exit /b 1

REM Start clean, so a removed file cannot linger in the output.
if exist "build" rmdir /s /q "build"
if exist "dist" rmdir /s /q "dist"

py -m PyInstaller --noconfirm blueprint-client.spec
if errorlevel 1 exit /b 1

echo.
echo Done. The app is at:
echo   "%~dp0dist\Blueprint Client\Blueprint Client.exe"
echo Ship the whole "Blueprint Client" folder, not just the exe.
