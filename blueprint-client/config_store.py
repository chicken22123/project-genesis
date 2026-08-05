"""Read and write blueprint_instance.json.

The launcher used to be configured by hand-editing the JSON file. The Settings
and Instances pages write to it instead, so the file is now loaded once, kept in
memory, and saved atomically. Keys the launcher does not know about are left
untouched so a hand-added setting survives a save from the UI.
"""

import json
import os
import tempfile

DEFAULTS = {
    "instance_name": "",
    "instance_path": "",
    "version_id": "",
    "modrinth_data_dir": "",
    "java_executable": "",
    "memory_max_mb": 0,
    "extra_java_args": [],
    "offline_username": "Player",
    "width": 0,
    "height": 0,
    "fallback_to_modrinth_app": False,
    "modrinth_executable": "",
}


class ConfigStore:
    def __init__(self, path):
        self.path = path
        self.data = dict(DEFAULTS)
        self.error = ""
        self._listeners = []
        self.load()

    # -------------------------------------------------------------- reading

    def load(self):
        self.error = ""
        stored = {}
        if os.path.isfile(self.path):
            try:
                with open(self.path, "r", encoding="utf-8") as handle:
                    stored = json.load(handle)
            except (OSError, ValueError) as exc:
                self.error = f"Could not read blueprint_instance.json: {exc}"
                stored = {}
        if not isinstance(stored, dict):
            self.error = "blueprint_instance.json is not a JSON object; using defaults."
            stored = {}

        self.data = dict(DEFAULTS)
        self.data.update(stored)
        return self.data

    def get(self, key, default=None):
        value = self.data.get(key, DEFAULTS.get(key, default))
        return default if value is None else value

    def as_launch_config(self):
        """A copy for modrinth_launcher, with empty values dropped.

        The launcher treats a missing key and an empty string differently in a
        few places, and 0 means "let Modrinth decide" for memory and window size.
        """
        config = {}
        for key, value in self.data.items():
            if value in ("", None, 0, [], False):
                continue
            config[key] = value
        return config

    # -------------------------------------------------------------- writing

    def update(self, values, notify=True):
        """Merge ``values`` in and save. Returns an error string, or ""."""
        self.data.update(values)
        error = self.save()
        if notify:
            self.notify()
        return error

    def save(self):
        payload = json.dumps(self.data, indent=2) + "\n"
        directory = os.path.dirname(os.path.abspath(self.path)) or "."
        handle = None
        temp_path = ""
        try:
            # Write beside the real file, then swap it in, so a crash mid-write
            # cannot leave a half-written config behind.
            fd, temp_path = tempfile.mkstemp(prefix=".blueprint-", suffix=".json", dir=directory)
            handle = os.fdopen(fd, "w", encoding="utf-8")
            handle.write(payload)
            handle.close()
            handle = None
            os.replace(temp_path, self.path)
            self.error = ""
            return ""
        except OSError as exc:
            if handle is not None:
                handle.close()
            if temp_path and os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:
                    pass
            self.error = f"Could not save settings: {exc}"
            return self.error

    # ------------------------------------------------------------ listeners

    def subscribe(self, callback):
        """Called after any save so other pages can refresh themselves."""
        self._listeners.append(callback)

    def notify(self):
        for callback in list(self._listeners):
            callback(self.data)
