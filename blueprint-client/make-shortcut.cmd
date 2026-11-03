@echo off
REM Double-click this to put an Obsidian Client shortcut on your desktop and in
REM the Start menu, and remove the old Blueprint Client ones.
REM
REM   make-shortcut.cmd           shortcut runs the code in this folder
REM   make-shortcut.cmd -UseExe   shortcut runs the built exe from build.cmd
REM
REM Windows refuses to run .ps1 files by double-click, so this hands the script
REM to PowerShell with that check skipped for this one run.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-shortcut.ps1" %*
echo.
pause
