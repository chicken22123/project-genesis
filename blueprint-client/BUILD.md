# Building the Blueprint Client .exe

Windows only. PyInstaller does not cross-compile, so a Windows executable has
to be built on Windows.

## The short version

Double-click `build.cmd`, or from a terminal in this folder:

```cmd
build.cmd
```

The result is `dist\Blueprint Client\Blueprint Client.exe`. Ship the whole
`dist\Blueprint Client` folder (zip it) - the exe needs the `_internal`
folder beside it.

## Doing it by hand

```cmd
py -m pip install pyinstaller
py -m PyInstaller --noconfirm blueprint-client.spec
```

The settings live in `blueprint-client.spec`: a windowed (no console) onedir
build named "Blueprint Client", with `blueprint_icon.ico` used both as the exe
icon and as bundled data for the window/taskbar icon.

The launcher imports nothing outside the standard library, so there is nothing
to install first and no hidden imports to declare.

## Where the app keeps its files

`app.py` resolves two separate directories, because a frozen build unpacks
itself somewhere temporary:

- **Assets** (`blueprint_icon.ico`) come out of the bundle.
- **`blueprint_instance.json` and `launch.log`** are written beside the exe, or
  in `%APPDATA%\BlueprintClient` when the install directory is read-only (an
  install under `Program Files`, for example).

Running `app.py` as a script is unchanged - both still sit next to the source.

## Things you will run into

- **SmartScreen** shows "Windows protected your PC" for any unsigned exe. Only
  an authenticode signing certificate removes that prompt; without one, users
  click *More info* then *Run anyway*.
- **Antivirus false positives** happen with PyInstaller, and much more often
  with `--onefile` than with the onedir build configured here.
- **Want a real installer** instead of a zip? Point [Inno Setup](https://jrsoftware.org/isinfo.php)
  at the `dist\Blueprint Client` folder.
