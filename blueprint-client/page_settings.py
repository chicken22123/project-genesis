"""The Settings page: everything that used to mean hand-editing the JSON file.

Values map one-to-one onto ``blueprint_instance.json``; blank or unticked means
"let Modrinth decide", which is what the launcher already did with a missing key.
"""

import os
import sys
import tkinter as tk
from tkinter import filedialog

import theme

# The two places the page has to name a real path, which differ per platform.
if sys.platform.startswith("win"):
    _JAVA_NAME = "java.exe"
    _DATA_HINT = "%APPDATA%\\ModrinthApp"
elif sys.platform == "darwin":
    _JAVA_NAME = "the java binary"
    _DATA_HINT = "~/Library/Application Support/ModrinthApp"
else:
    _JAVA_NAME = "the java binary"
    _DATA_HINT = "~/.local/share/ModrinthApp"

MIN_MEMORY_MB = 1024
MAX_MEMORY_MB = 16384
MEMORY_STEP_MB = 512
DEFAULT_MEMORY_MB = 4096


class SettingsPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=theme.BG_TOP, bd=0, highlightthickness=0)
        self.app = app

        self.username = tk.StringVar()
        self.auto_memory = tk.BooleanVar(value=True)
        self.memory = tk.IntVar(value=DEFAULT_MEMORY_MB)
        self.custom_size = tk.BooleanVar(value=False)
        self.width = tk.StringVar()
        self.height = tk.StringVar()
        self.java = tk.StringVar()
        self.java_args = tk.StringVar()
        self.data_dir = tk.StringVar()
        self.fallback = tk.BooleanVar(value=False)
        self.modrinth_exe = tk.StringVar()

        self._build_header()
        body = theme.frame(self)
        body.pack(fill="both", expand=True, padx=22)
        self._build_game_panel(body)
        self._build_advanced_panel(body)
        self._build_footer()

        self.load_from_config()

    # --------------------------------------------------------------- layout

    def _build_header(self):
        header = theme.frame(self)
        header.pack(fill="x", padx=22, pady=(18, 12))
        titles = theme.frame(header)
        titles.pack(side="left", fill="x", expand=True)
        theme.heading(titles, "Settings").pack(anchor="w")
        theme.label(
            titles, f"Saved to {os.path.basename(self.app.config_store.path)}",
            size=9, fg=theme.MUTED, bg=theme.BG_TOP,
        ).pack(anchor="w", pady=(2, 0))

    def _build_game_panel(self, parent):
        panel = theme.panel(parent)
        panel.pack(side="left", fill="both", expand=True, padx=(0, 14))

        theme.eyebrow(panel, "Game").pack(anchor="w", padx=18, pady=(14, 12))

        self._field(panel, "Offline username", self.username,
                    "Used only when Modrinth has no signed-in account.")

        theme.separator(panel).pack(fill="x", padx=18, pady=(4, 12))

        theme.eyebrow(panel, "Memory").pack(anchor="w", padx=18)
        theme.checkbutton(
            panel, "Use Modrinth's memory setting", self.auto_memory, self._sync_memory
        ).pack(anchor="w", padx=18, pady=(6, 2))

        memory_row = theme.frame(panel, bg=theme.PANEL_BG)
        memory_row.pack(fill="x", padx=18, pady=(0, 4))
        self.memory_scale = theme.scale(
            memory_row, self.memory, MIN_MEMORY_MB, MAX_MEMORY_MB, MEMORY_STEP_MB,
            command=lambda _value: self._sync_memory_label(),
        )
        self.memory_scale.pack(side="left", fill="x", expand=True)
        self.memory_label = theme.label(
            memory_row, "", size=10, weight="bold", fg=theme.ACCENT_LIGHT, width=9, anchor="e"
        )
        self.memory_label.pack(side="right", padx=(10, 0))

        theme.separator(panel).pack(fill="x", padx=18, pady=(8, 12))

        theme.eyebrow(panel, "Window size").pack(anchor="w", padx=18)
        theme.checkbutton(
            panel, "Start with a custom window size", self.custom_size, self._sync_size
        ).pack(anchor="w", padx=18, pady=(6, 4))

        size_row = theme.frame(panel, bg=theme.PANEL_BG)
        size_row.pack(anchor="w", padx=18, pady=(0, 16))
        self.width_entry = theme.entry(size_row, self.width, width=7)
        self.width_entry.pack(side="left")
        theme.label(size_row, "x", size=10, fg=theme.MUTED).pack(side="left", padx=6)
        self.height_entry = theme.entry(size_row, self.height, width=7)
        self.height_entry.pack(side="left")

    def _build_advanced_panel(self, parent):
        panel = theme.panel(parent)
        panel.pack(side="left", fill="both", expand=True)

        theme.eyebrow(panel, "Advanced").pack(anchor="w", padx=18, pady=(14, 12))

        self._field(panel, "Java executable", self.java,
                    "Blank uses the runtime Modrinth installed.",
                    browse=lambda: self._browse_file(self.java, f"Select {_JAVA_NAME}"))
        self._field(panel, "Extra Java arguments", self.java_args,
                    "Separated by spaces, for example -XX:+UseG1GC.")
        self._field(panel, "Modrinth data folder", self.data_dir,
                    f"Blank finds {_DATA_HINT} automatically.",
                    browse=lambda: self._browse_dir(self.data_dir, "Select the Modrinth data folder"))

        theme.separator(panel).pack(fill="x", padx=18, pady=(4, 12))

        theme.eyebrow(panel, "If a launch fails").pack(anchor="w", padx=18)
        theme.checkbutton(
            panel, "Open the Modrinth App as a fallback", self.fallback, self._sync_fallback
        ).pack(anchor="w", padx=18, pady=(6, 6))

        exe_row = theme.frame(panel, bg=theme.PANEL_BG)
        exe_row.pack(fill="x", padx=18, pady=(0, 16))
        self.exe_entry = theme.entry(exe_row, self.modrinth_exe, width=24)
        self.exe_entry.pack(side="left", fill="x", expand=True)
        self.exe_button = theme.button(
            exe_row, "...",
            lambda: self._browse_file(self.modrinth_exe, "Select the Modrinth App"),
        )
        self.exe_button.pack(side="left", padx=(8, 0))

    def _build_footer(self):
        footer = theme.frame(self)
        footer.pack(fill="x", padx=22, pady=(14, 18))
        theme.button(footer, "Save Changes", self.save, kind="primary").pack(side="left")
        theme.button(footer, "Revert", self.load_from_config).pack(side="left", padx=(8, 0))
        self.status = theme.label(footer, "", size=9, fg=theme.MUTED, bg=theme.BG_TOP, anchor="e")
        self.status.pack(side="right", fill="x", expand=True)

    def _field(self, parent, caption, variable, hint="", browse=None):
        theme.eyebrow(parent, caption).pack(anchor="w", padx=18)
        row = theme.frame(parent, bg=theme.PANEL_BG)
        row.pack(fill="x", padx=18, pady=(6, 2))
        widget = theme.entry(row, variable, width=24)
        widget.pack(side="left", fill="x", expand=True)
        if browse:
            theme.button(row, "...", browse).pack(side="left", padx=(8, 0))
        if hint:
            theme.label(
                parent, hint, size=8, fg=theme.MUTED, anchor="w", justify="left", wraplength=300
            ).pack(anchor="w", padx=18, pady=(0, 12))
        return widget

    # ------------------------------------------------------------- enabling

    def _sync_memory(self):
        auto = self.auto_memory.get()
        self.memory_scale.configure(state="disabled" if auto else "normal")
        self._sync_memory_label()

    def _sync_memory_label(self):
        if self.auto_memory.get():
            self.memory_label.configure(text="Modrinth", fg=theme.MUTED)
        else:
            self.memory_label.configure(text=f"{self.memory.get()} MB", fg=theme.ACCENT_LIGHT)

    def _sync_size(self):
        state = "normal" if self.custom_size.get() else "disabled"
        self.width_entry.configure(state=state)
        self.height_entry.configure(state=state)

    def _sync_fallback(self):
        enabled = self.fallback.get()
        self.exe_entry.configure(state="normal" if enabled else "disabled")
        theme.set_button_enabled(self.exe_button, enabled)

    # ----------------------------------------------------------- load + save

    def on_show(self):
        self.load_from_config()

    def load_from_config(self):
        config = self.app.config_store

        self.username.set(config.get("offline_username", "Player"))

        memory = int(config.get("memory_max_mb", 0) or 0)
        self.auto_memory.set(memory <= 0)
        self.memory.set(_clamp(memory or DEFAULT_MEMORY_MB, MIN_MEMORY_MB, MAX_MEMORY_MB))

        width = int(config.get("width", 0) or 0)
        height = int(config.get("height", 0) or 0)
        self.custom_size.set(bool(width and height))
        self.width.set(str(width or 854))
        self.height.set(str(height or 480))

        self.java.set(config.get("java_executable", ""))
        args = config.get("extra_java_args", [])
        self.java_args.set(" ".join(args) if isinstance(args, list) else str(args))
        self.data_dir.set(config.get("modrinth_data_dir", ""))
        self.fallback.set(bool(config.get("fallback_to_modrinth_app", False)))
        self.modrinth_exe.set(config.get("modrinth_executable", ""))

        self._sync_memory()
        self._sync_size()
        self._sync_fallback()
        self._set_status("", theme.MUTED)

    def save(self):
        username = self.username.get().strip() or "Player"
        self.username.set(username)

        width = height = 0
        if self.custom_size.get():
            width = _to_int(self.width.get())
            height = _to_int(self.height.get())
            if width < 320 or height < 240:
                self._set_status("Window size must be at least 320 x 240.", theme.ERROR)
                return

        java = self.java.get().strip()
        if java and not os.path.exists(java):
            self._set_status(f"Saved, but {java} does not exist yet.", theme.ERROR)

        values = {
            "offline_username": username,
            "memory_max_mb": 0 if self.auto_memory.get() else int(self.memory.get()),
            "width": width,
            "height": height,
            "java_executable": java,
            "extra_java_args": self.java_args.get().split(),
            "modrinth_data_dir": self.data_dir.get().strip(),
            "fallback_to_modrinth_app": bool(self.fallback.get()),
            "modrinth_executable": self.modrinth_exe.get().strip(),
        }

        error = self.app.config_store.update(values)
        if error:
            self._set_status(error, theme.ERROR)
        elif not (java and not os.path.exists(java)):
            self._set_status("Settings saved.", theme.OK)

    # -------------------------------------------------------------- browsing

    def _browse_file(self, variable, title):
        path = filedialog.askopenfilename(
            parent=self, title=title, initialdir=_start_dir(variable.get()),
            # A *.exe filter hides every java binary on Linux and macOS,
            # where the thing being picked has no extension at all.
            filetypes=(
                [("Programs", "*.exe"), ("All files", "*.*")]
                if sys.platform.startswith("win")
                else [("All files", "*.*")]
            ),
        )
        if path:
            variable.set(os.path.normpath(path))

    def _browse_dir(self, variable, title):
        path = filedialog.askdirectory(
            parent=self, title=title, initialdir=_start_dir(variable.get())
        )
        if path:
            variable.set(os.path.normpath(path))

    def _set_status(self, message, color):
        self.status.configure(text=message, fg=color)


def _clamp(value, low, high):
    return max(low, min(high, value))


def _to_int(text):
    try:
        return int(str(text).strip())
    except ValueError:
        return 0


def _start_dir(current):
    if current and os.path.isdir(current):
        return current
    if current and os.path.isfile(current):
        return os.path.dirname(current)
    return os.path.expanduser("~")
