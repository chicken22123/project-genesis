"""The Instances page: pick which Modrinth instance the launcher starts.

The launcher used to run whatever ``blueprint_instance.json`` pointed at. This
page lists every instance the Modrinth App knows about, shows what is in it, and
writes the choice back to the config file.
"""

import os
import tkinter as tk
from datetime import datetime

import modrinth_data
import theme

AUTO_VERSION = "Auto (detect from the instance)"


class InstancesPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=theme.BG_TOP, bd=0, highlightthickness=0)
        self.app = app
        self.instances = []
        self.versions = []
        self.loaded = False
        self.loading = False

        self._build_header()
        body = theme.frame(self)
        body.pack(fill="both", expand=True, padx=22, pady=(0, 18))
        self._build_list(body)
        self._build_details(body)

        self.status = theme.label(self, "", size=9, fg=theme.MUTED, bg=theme.BG_TOP, anchor="w")
        self.status.pack(fill="x", padx=24, pady=(0, 14))

    # --------------------------------------------------------------- layout

    def _build_header(self):
        header = theme.frame(self)
        header.pack(fill="x", padx=22, pady=(18, 12))
        titles = theme.frame(header)
        titles.pack(side="left", fill="x", expand=True)
        theme.heading(titles, "Instances").pack(anchor="w")
        self.subtitle = theme.label(
            titles, "Reading the Modrinth App folder...", size=9, fg=theme.MUTED, bg=theme.BG_TOP
        )
        self.subtitle.pack(anchor="w", pady=(2, 0))
        theme.button(header, "Refresh", lambda: self.load(refresh=True)).pack(side="right")

    def _build_list(self, parent):
        panel = theme.panel(parent, width=380)
        panel.pack(side="left", fill="both", padx=(0, 14))
        panel.pack_propagate(False)

        theme.eyebrow(panel, "Detected instances").pack(anchor="w", padx=16, pady=(14, 8))

        holder = theme.frame(panel, bg=theme.PANEL_BG)
        holder.pack(fill="both", expand=True, padx=16, pady=(0, 16))
        self.table = theme.rows(
            holder,
            [
                ("mark", "", 22, "center"),
                ("name", "Instance", 170, "w"),
                ("version", "Version", 70, "e"),
                ("loader", "Loader", 70, "e"),
            ],
            height=15,
        )
        scroll = theme.scrollbar(holder, self.table.yview)
        self.table.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.table.pack(side="left", fill="both", expand=True)
        self.table.bind("<<TreeviewSelect>>", lambda _event: self._show_selected())
        self.table.bind("<Double-Button-1>", lambda _event: self.use_selected())

    def _build_details(self, parent):
        panel = theme.panel(parent)
        panel.pack(side="left", fill="both", expand=True)

        theme.eyebrow(panel, "Details").pack(anchor="w", padx=18, pady=(14, 10))

        grid = theme.frame(panel, bg=theme.PANEL_BG)
        grid.pack(fill="x", padx=18)
        grid.columnconfigure(1, weight=1)

        self.fields = {}
        rows = [
            ("name", "Name"),
            ("game_version", "Game version"),
            ("loader", "Loader"),
            ("mods", "Mods"),
            ("last_played", "Last played"),
            ("path", "Folder"),
        ]
        for row, (key, caption) in enumerate(rows):
            theme.eyebrow(grid, caption).grid(row=row, column=0, sticky="w", pady=(0, 9))
            value = theme.label(
                grid, "-", size=10, anchor="w", justify="left",
                wraplength=250 if key == "path" else 0,
            )
            value.grid(row=row, column=1, sticky="w", padx=(14, 0), pady=(0, 9))
            self.fields[key] = value

        theme.separator(panel).pack(fill="x", padx=18, pady=(6, 14))

        theme.eyebrow(panel, "Version manifest").pack(anchor="w", padx=18)
        self.version_var = tk.StringVar(value=AUTO_VERSION)
        self.version_box = theme.combobox(panel, self.version_var, [AUTO_VERSION])
        self.version_box.pack(anchor="w", padx=18, pady=(6, 4))
        theme.label(
            panel,
            "Auto matches the instance's game version and loader. Pick one to override it.",
            size=8, fg=theme.MUTED, anchor="w", justify="left", wraplength=330,
        ).pack(anchor="w", padx=18)

        actions = theme.frame(panel, bg=theme.PANEL_BG)
        actions.pack(fill="x", padx=18, pady=(16, 16))
        self.use_button = theme.button(actions, "Use This Instance", self.use_selected, kind="primary")
        self.use_button.pack(side="left")
        theme.button(actions, "Open Folder", self.open_folder).pack(side="left", padx=(8, 0))

    # ----------------------------------------------------------------- data

    def on_show(self):
        if not self.loaded and not self.loading:
            self.load()

    def load(self, refresh=False):
        if self.loading:
            return
        self.loading = True
        self._set_status("Reading the Modrinth App folder...", theme.MUTED)
        config = dict(self.app.config_store.data)
        self.app.run_async(lambda: modrinth_data.overview(config, refresh), self._on_loaded)

    def _on_loaded(self, result, error):
        self.loading = False
        if error is not None:
            self.instances = []
            self.table.delete(*self.table.get_children())
            self._set_status(str(error), theme.ERROR)
            self.subtitle.configure(text="No Modrinth data folder was read.")
            return

        self.loaded = True
        self.instances = result["instances"]
        self.versions = result["versions"]
        account = result["account"] or "no account signed in"
        self.subtitle.configure(
            text=f"{result['data_dir']}  ·  {len(self.instances)} instances  ·  {account}"
        )
        self._fill_list()
        self._set_status("", theme.MUTED)

    def _fill_list(self):
        self.table.delete(*self.table.get_children())
        selected_row = ""

        for index, instance in enumerate(self.instances):
            if not instance.get("exists"):
                state = "error"
            elif instance.get("selected"):
                state = "accent"
            else:
                state = "muted" if not instance.get("loader") else ""

            row = self.table.insert(
                "", "end", iid=str(index),
                values=(
                    "●" if instance.get("selected") else "",
                    instance.get("name") or "?",
                    instance.get("game_version") or "?",
                    (instance.get("loader") or "vanilla").title(),
                ),
                tags=theme.zebra_tag(index, state) if state else theme.zebra_tag(index),
            )
            if instance.get("selected"):
                selected_row = row

        if self.instances:
            target = selected_row or "0"
            self.table.selection_set(target)
            self.table.see(target)
            self._show_selected()

    # -------------------------------------------------------------- details

    def _selected_instance(self):
        selection = self.table.selection()
        if not selection:
            return None
        index = int(selection[0])
        return self.instances[index] if index < len(self.instances) else None

    def _show_selected(self):
        instance = self._selected_instance()
        if not instance:
            return

        self.fields["name"].configure(text=instance.get("name") or "-")
        self.fields["game_version"].configure(text=instance.get("game_version") or "unknown")
        self.fields["loader"].configure(text=(instance.get("loader") or "vanilla").title())
        self.fields["mods"].configure(text=f"{instance.get('mod_count', 0)} enabled")
        self.fields["last_played"].configure(text=_friendly_date(instance.get("last_played")))

        path = instance.get("path") or "no folder recorded"
        self.fields["path"].configure(
            text=path, fg=theme.TEXT if instance.get("exists") else theme.ERROR
        )

        options = [AUTO_VERSION] + modrinth_data.matching_versions(self.versions, instance)
        self.version_box.configure(values=options)
        stored = self.app.config_store.get("version_id", "")
        if instance.get("selected") and stored in options:
            self.version_var.set(stored)
        else:
            self.version_var.set(AUTO_VERSION)

    # -------------------------------------------------------------- actions

    def use_selected(self):
        instance = self._selected_instance()
        if not instance:
            return
        if not instance.get("exists"):
            self._set_status(
                "That instance folder is missing. Open Modrinth once so it can repair it.",
                theme.ERROR,
            )
            return

        version = self.version_var.get()
        error = self.app.config_store.update(
            {
                "instance_name": instance.get("name") or "",
                "instance_path": instance.get("path") or "",
                "version_id": "" if version == AUTO_VERSION else version,
            }
        )
        if error:
            self._set_status(error, theme.ERROR)
            return

        for other in self.instances:
            other["selected"] = other is instance
        self._fill_list()
        self._set_status(f"Blueprint will now launch {instance.get('name')}.", theme.OK)

    def open_folder(self):
        instance = self._selected_instance()
        path = (instance or {}).get("path") or ""
        if not path or not os.path.isdir(path):
            self._set_status("That instance has no folder on disk.", theme.ERROR)
            return
        try:
            os.startfile(path)  # noqa: S606 - opening the user's own game folder
        except (OSError, AttributeError) as exc:
            self._set_status(f"Could not open the folder: {exc}", theme.ERROR)

    def _set_status(self, message, color):
        self.status.configure(text=message, fg=color)


def _friendly_date(value):
    """last_played is a unix timestamp in current Modrinth builds, ISO text before."""
    if value in (None, ""):
        return "never"
    try:
        return datetime.fromtimestamp(float(value)).strftime("%d %b %Y, %H:%M")
    except (TypeError, ValueError, OSError, OverflowError):
        return str(value).replace("T", " ")[:16]
