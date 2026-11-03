# Building the Obsidian Client .exe

Windows only. PyInstaller does not cross-compile, so a Windows executable has
to be built on Windows. To run the launcher on Linux or ChromeOS instead, see
[RUNNING-ON-LINUX.md](RUNNING-ON-LINUX.md) - no build step is needed there.

## The short version: an installer

Double-click `build-installer.cmd`. It builds the app and wraps it in

    dist\ObsidianClient-Setup-1.2.0.exe

which installs Obsidian Client the way the Modrinth App installs: no admin
prompt, a desktop shortcut, a Start menu entry, and an entry in
**Settings > Apps** to uninstall it. Running a newer Setup over an old install
upgrades it in place and keeps your settings.

That one file is also what to send friends - no zip, nothing to extract. They
double-click it, click through SmartScreen's *More info > Run anyway* (the
app is not code-signed), and it is on their desktop.

The installer is made with [Inno Setup 6](https://jrsoftware.org/isinfo.php),
which is free. If it is missing, `build-installer.cmd` offers to install it
with winget. The installer's settings are in `installer\obsidian-client.iss`.

## Just the app, without an installer

Double-click `build.cmd`. The result is
`dist\Obsidian Client\Obsidian Client.exe`, which runs from that folder as it
is. To share it this way, zip the whole `dist\Obsidian Client` folder - the
exe needs the `_internal` folder beside it.

## A shortcut to the code, for working on it

If you are changing the code, `make-shortcut.cmd` is quicker than rebuilding
the installer each time. It puts **Obsidian Client** on your desktop and in the
Start menu with the crystal icon, and deletes any old Blueprint Client
shortcuts it finds there.

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
