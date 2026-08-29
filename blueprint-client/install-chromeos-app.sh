#!/usr/bin/env bash
# Put Blueprint Client in the ChromeOS launcher, so it starts from an icon
# instead of a terminal command. Also works on any Linux desktop.
#
# ChromeOS watches ~/.local/share/applications and mirrors what it finds there
# into the launcher within a few seconds - there is nothing to restart.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/256x256/apps"
DESKTOP="$APPS/blueprint-client.desktop"

mkdir -p "$APPS" "$ICONS"
install -m 644 "$HERE/blueprint_icon.png" "$ICONS/blueprint-client.png"
chmod +x "$HERE/run.sh"

# Exec has to be an absolute path: the launcher does not run this from the
# project folder, and run.sh cd's to its own directory once started.
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Blueprint Client
GenericName=Minecraft Launcher
Comment=Launch the Minecraft instances the Modrinth App manages
Exec=$HERE/run.sh
Icon=blueprint-client
Terminal=false
Categories=Game;
StartupWMClass=BlueprintClient
EOF
chmod 644 "$DESKTOP"

update-desktop-database "$APPS" >/dev/null 2>&1 || true

echo "Installed."
echo
echo "  Look for \"Blueprint Client\" in the ChromeOS launcher (the circle at the"
echo "  bottom-left). It appears under Linux apps within a few seconds. Right-click"
echo "  it there to pin it to the shelf."
echo
echo "  To remove it again:"
echo "    rm \"$DESKTOP\" \"$ICONS/blueprint-client.png\""
