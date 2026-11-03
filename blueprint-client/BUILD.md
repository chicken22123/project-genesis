# Building the Obsidian Client .exe

Windows only. PyInstaller does not cross-compile, so a Windows executable has
to be built on Windows. To run the launcher on Linux or ChromeOS instead, see
[RUNNING-ON-LINUX.md](RUNNING-ON-LINUX.md) - no build step is needed there.

## The short version

Double-click `build.cmd`, or from a terminal in this folder:

```cmd
build.cmd
```

The result is `dist\Obsidian Client\Obsidian Client.exe`. Ship the whole
`dist\Obsidian Client` folder (zip it) - the exe needs the `_internal`
folder beside it.

## A desktop shortcut for your own PC

Double-click `make-shortcut.cmd`. It puts **Obsidian Client** on your desktop
and in the Start menu with the crystal icon, and deletes any old Blueprint
Client shortcuts it finds there.

The shortcut runs `app.py` from this folder (through `pythonw`, so no console
window), which means it always opens whatever you last `git pull`ed - no
rebuilding. To point it at the built exe instead, run
`make-shortcut.cmd -UseExe` from a terminal.

If you move this folder, run it again.

## Doing it by hand

```cmd
py -m pip install pyinstaller
py -m PyInstaller --noconfirm obsidian-client.spec
```

The settings live in `obsidian-client.spec`: a windowed (no console) onedir
build named "Obsidian Client", with `app_icon.ico` used both as the exe
icon and as bundled data for the window/taskbar icon.

The launcher imports nothing outside the standard library, so there is nothing
to install first and no hidden imports to declare.

## Where the app keeps its files

`app.py` resolves two separate directories, because a frozen build unpacks
itself somewhere temporary:

- **Assets** (`app_icon.ico`) come out of the bundle.
- **`blueprint_instance.json` and `launch.log`** are written beside the exe, or
  in `%APPDATA%\ObsidianClient` when the install directory is read-only (an
  install under `Program Files`, for example).

Running `app.py` as a script is unchanged - both still sit next to the source.

## Things you will run into

- **SmartScreen** shows "Windows protected your PC" for any unsigned exe. Only
  an authenticode signing certificate removes that prompt; without one, users
  click *More info* then *Run anyway*.
- **Antivirus false positives** happen with PyInstaller, and much more often
  with `--onefile` than with the onedir build configured here.
- **Want a real installer** instead of a zip? Point [Inno Setup](https://jrsoftware.org/isinfo.php)
  at the `dist\Obsidian Client` folder.
