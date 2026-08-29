# Running Blueprint Client on Linux / ChromeOS

There is no `.exe` involved here. On ChromeOS you run the launcher as a Python
script inside the Linux development environment (Crostini), which is a normal
ChromeOS feature under **Settings → About ChromeOS → Developers**.

## Setup

Open the **Terminal** app, then paste these one at a time:

```sh
sudo apt update
sudo apt install -y python3 python3-tk git
git clone https://github.com/chicken22123/project-genesis.git
cd project-genesis/blueprint-client
./run.sh
```

`python3-tk` matters - the container ships Python without tkinter, and the app
will not start without it.

## Making it a launcher icon

Typing `./run.sh` every time gets old. To get a real ChromeOS app icon:

```sh
./install-chromeos-app.sh
```

"Blueprint Client" then shows up in the ChromeOS launcher (the circle at the
bottom-left) under Linux apps, within a few seconds - nothing needs restarting.
Right-click it there to pin it to the shelf.

What that script does: writes `~/.local/share/applications/blueprint-client.desktop`
and drops a 256x256 PNG into `~/.local/share/icons`. ChromeOS watches that
applications folder and mirrors anything in it into the launcher. The entry
points at `run.sh` by absolute path, so leave the project where it is - if you
move the folder, run the script again.

To remove it:

```sh
rm ~/.local/share/applications/blueprint-client.desktop
rm ~/.local/share/icons/hicolor/256x256/apps/blueprint-client.png
```

## You also need the Modrinth App

Blueprint Client is a front-end for instances the Modrinth App manages. It reads
Modrinth's own data folder and launches what it finds there; it does not
download Minecraft itself. So the Modrinth App has to be installed **inside the
same Linux container**, with at least one instance in it, or Blueprint Client
will start and then tell you it cannot find the data folder.

On Linux it looks for that folder in:

- `~/.local/share/ModrinthApp`
- `~/.config/ModrinthApp`

and the same two paths under the names `com.modrinth.theseus` and `modrinth`.
If yours lives elsewhere, set `modrinth_data_dir` on the Settings page.

## What differs from Windows

Everything that matters works. One thing genuinely differs:

- **Deleting a mod is permanent.** The Recycle Bin is a Windows shell feature,
  so `recycle.py` reports itself unavailable and the confirmation says the
  delete cannot be undone. It is telling you the truth - back up anything you
  care about.

## Before you spend an evening on it

- **Managed Chromebooks.** School and work devices usually have the Linux
  environment disabled by policy, and it cannot be turned on from the device.
- **ARM Chromebooks.** The Modrinth App publishes x86_64 Linux builds. On a
  MediaTek or Snapdragon Chromebook this gets considerably harder.
- **RAM.** Minecraft inside the Linux container on a 4 GB Chromebook is not a
  good time. 8 GB is the realistic floor.
