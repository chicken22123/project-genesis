@echo off
REM Builds the Blueprint Client Fabric mod into build\libs\.
REM Needs JDK 21 on PATH - Gradle itself is downloaded by the wrapper.
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java was not found. Install JDK 21 from https://adoptium.net
    echo and pick "Set JAVA_HOME" plus "Add to PATH" in the installer.
    exit /b 1
)

call gradlew.bat build
if errorlevel 1 exit /b 1

echo.
echo Done. The mod is in:
echo   "%~dp0build\libs"
echo Use the .jar WITHOUT "-sources" in its name.
