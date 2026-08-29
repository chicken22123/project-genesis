#!/usr/bin/env bash
# Start Blueprint Client on Linux (including ChromeOS's Linux container).
# Windows users want launch_app.bat instead.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is not installed. Run: sudo apt install python3 python3-tk" >&2
    exit 1
fi

# tkinter is a separate package on Debian/Ubuntu, and the container ChromeOS
# sets up does not include it.
if ! python3 -c "import tkinter" >/dev/null 2>&1; then
    echo "Python is missing tkinter. Run: sudo apt install python3-tk" >&2
    exit 1
fi

exec python3 app.py
