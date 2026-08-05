"""The launch.log file, with a size cap.

Every line of Minecraft's own output is mirrored into launch.log, so the file
grows for as long as the launcher is used - it had reached 5.5 MB before this
was added. The log rotates once it passes ``max_bytes`` and keeps a couple of
older files, which is enough history to explain a crash that happened earlier.

The game watcher thread and the Tk thread both write, so writes take a lock.
"""

import os
import threading
from datetime import datetime

MAX_BYTES = 2 * 1024 * 1024
BACKUPS = 2


class RotatingLog:
    def __init__(self, path, max_bytes=MAX_BYTES, backups=BACKUPS):
        self.path = path
        self.max_bytes = max_bytes
        self.backups = backups
        self._lock = threading.Lock()

    def write(self, message):
        """Append one timestamped line. Logging must never break a launch."""
        line = f"[{datetime.now().isoformat(timespec='seconds')}] {message}\n"
        with self._lock:
            try:
                self._rotate_if_needed()
                with open(self.path, "a", encoding="utf-8") as handle:
                    handle.write(line)
            except OSError:
                pass

    def _rotate_if_needed(self):
        try:
            if os.path.getsize(self.path) < self.max_bytes:
                return
        except OSError:
            return

        # launch.log.1 becomes .2, launch.log becomes .1, oldest is dropped.
        oldest = f"{self.path}.{self.backups}"
        if os.path.exists(oldest):
            os.remove(oldest)
        for index in range(self.backups - 1, 0, -1):
            source = f"{self.path}.{index}"
            if os.path.exists(source):
                os.replace(source, f"{self.path}.{index + 1}")
        os.replace(self.path, f"{self.path}.1")

    def size(self):
        try:
            return os.path.getsize(self.path)
        except OSError:
            return 0
