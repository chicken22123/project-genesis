; Inno Setup script for Obsidian Client.
;
; Build it with build-installer.cmd, which runs build.cmd first and then
; this script with the version taken from app.py:
;
;     ISCC /DAppVersion=1.2.0 installer\obsidian-client.iss
;
; The result is dist\ObsidianClient-Setup-<version>.exe - one file that
; installs the launcher the way the Modrinth App installs: into the user's own
; programs folder, with no admin prompt, a desktop and Start menu shortcut, and
; an entry in Settings > Apps to uninstall it.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define AppName "Obsidian Client"
#define AppExe "Obsidian Client.exe"

[Setup]
; Never change this id. It is how Windows recognises a newer installer as an
; upgrade of the same app instead of installing a second copy beside it.
AppId={{56677709-6BEE-4520-A711-A170340CAFD7}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppName}
; Per-user, like Modrinth: no admin prompt, and the folder stays writable,
; which the launcher needs for its config and launch.log.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Ask where to install only the first time; upgrades go to the same place.
DisableDirPage=auto
OutputDir=..\dist
OutputBaseFilename=ObsidianClient-Setup-{#AppVersion}
SetupIconFile=..\app_icon.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
; Installing an update while the launcher is open: close it first.
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Put a shortcut on the &desktop"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\dist\{#AppName}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Open {#AppName} now"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Files the launcher writes after installing, so uninstalling leaves nothing.
Type: files; Name: "{app}\launch.log*"
Type: files; Name: "{app}\blueprint_instance.json"
Type: dirifempty; Name: "{app}"
