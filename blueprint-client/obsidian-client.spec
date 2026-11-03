# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller build for Obsidian Client.

Run ``build.cmd``, or by hand:

    py -m PyInstaller --noconfirm obsidian-client.spec

Produces ``dist/Obsidian Client/Obsidian Client.exe``. This is a onedir
build on purpose: a onefile exe re-unpacks the whole Tcl/Tk runtime into a
temp directory on every launch, which is a visible delay before the window
appears, and it is the variant Windows Defender's heuristics flag hardest.
Zip the ``dist/Obsidian Client`` folder to hand it out.

The launcher imports nothing outside the standard library, so there are no
hidden imports to declare - only the icon has to be carried along.
"""

import os

# PyInstaller sets SPECPATH to the directory holding this file.
HERE = SPECPATH

a = Analysis(
    [os.path.join(HERE, "app.py")],
    pathex=[HERE],
    binaries=[],
    datas=[
        (os.path.join(HERE, "app_icon.ico"), "."),
        # The window icon on any platform Tk will not hand a .ico to.
        (os.path.join(HERE, "app_icon.png"), "."),
    ],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="Obsidian Client",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    # A Tk app: without this a console window sits behind the UI for the
    # whole session.
    console=False,
    icon=os.path.join(HERE, "app_icon.ico"),
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="Obsidian Client",
)
