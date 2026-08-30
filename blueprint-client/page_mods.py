"""The Mods page: add, remove and toggle the mods in the selected instance.

Mods are turned off the same way every other launcher does it, by renaming the
jar to ``.jar.disabled``, so a change made here is respected by the Modrinth App
as well. Removing sends the jar to the Recycle Bin rather than destroying it.
"""

import os
import tkinter as tk
from tkinter import filedialog, messagebox

import blueprint_mod
import modrinth_data
import recycle
import theme


class ModsPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=theme.BG_TOP, bd=0, highlightthickness=0)
        self.app = app
        self.mods = []
        self.instance = {}
        self.loaded = False
        self.loading = False
        self.building = False
        self._pending_added = []
        self._keep_status = False

        self._build_header()
        self._build_list()

        self.status = theme.label(self, "", size=9, fg=theme.MUTED, bg=theme.BG_TOP, anchor="w")
        self.status.pack(fill="x", padx=24, pady=(0, 14))

    # --------------------------------------------------------------- layout

    def _build_header(self):
        header = theme.frame(self)
        header.pack(fill="x", padx=22, pady=(18, 12))

        titles = theme.frame(header)
        titles.pack(side="left", fill="x", expand=True)
        theme.heading(titles, "Mods").pack(anchor="w")
        self.subtitle = theme.label(
            titles, "Reading the instance...", size=9, fg=theme.MUTED, bg=theme.BG_TOP,
            anchor="w", justify="left",
        )
        self.subtitle.pack(anchor="w", pady=(2, 0))

        theme.button(header, "Refresh", lambda: self.load(refresh=True)).pack(side="right")
        theme.button(header, "Open Folder", self.open_folder).pack(side="right", padx=(0, 8))

    def _build_list(self):
        panel = theme.panel(self)
        panel.pack(fill="both", expand=True, padx=22, pady=(0, 14))

        head = theme.frame(panel, bg=theme.PANEL_BG)
        head.pack(fill="x", padx=18, pady=(14, 8))
        theme.eyebrow(head, "Installed mods").pack(side="left")
        self.summary = theme.label(head, "", size=9, fg=theme.MUTED)
        self.summary.pack(side="right")

        holder = theme.frame(panel, bg=theme.PANEL_BG)
        holder.pack(fill="both", expand=True, padx=18)
        self.table = theme.rows(
            holder,
            [
                ("state", "", 44, "center"),
                ("name", "Mod", 190, "w"),
                ("version", "Version", 120, "w"),
                ("file", "File", 230, "w"),
                ("size", "Size", 64, "e"),
            ],
            height=14,
        )
        # Extended: several mods can be enabled, disabled or removed together.
        self.table.configure(selectmode="extended")
        scroll = theme.scrollbar(holder, self.table.yview)
        self.table.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.table.pack(side="left", fill="both", expand=True)
        self.table.bind("<<TreeviewSelect>>", lambda _event: self._sync_buttons())
        self.table.bind("<Double-Button-1>", lambda _event: self.toggle_selected())
        self.table.bind("<Return>", lambda _event: self.toggle_selected())
        self.table.bind("<Delete>", lambda _event: self.remove_selected())

        actions = theme.frame(panel, bg=theme.PANEL_BG)
        actions.pack(fill="x", padx=18, pady=(12, 16))
        theme.button(actions, "Add Mods...", self.add_mods, kind="primary").pack(side="left")
        self.blueprint_button = theme.button(
            actions, "Install Blueprint Mod", self.install_blueprint_mod, width=20
        )
        self.blueprint_button.pack(side="left", padx=(8, 0))
        self.toggle_button = theme.button(actions, "Disable", self.toggle_selected, width=10)
        self.toggle_button.pack(side="left", padx=(8, 0))
        self.remove_button = theme.button(
            actions, "Remove", self.remove_selected, kind="danger", width=10
        )
        self.remove_button.pack(side="left", padx=(8, 0))
        theme.label(
            actions, "Double-click toggles · Delete removes", size=9, fg=theme.MUTED
        ).pack(side="left", padx=(12, 0))

    # ----------------------------------------------------------------- data

    def on_show(self):
        if not self.loaded and not self.loading:
            self.load()

    def on_config_changed(self):
        self.loaded = False
        if self.winfo_ismapped():
            self.load()

    def load(self, refresh=False, keep_status=False):
        """Reload the list. ``keep_status`` protects an add/remove message from
        being wiped by the reload that follows it."""
        if self.loading:
            return
        self.loading = True
        self._keep_status = keep_status
        if not keep_status:
            self._set_status("Reading the mods folder...", theme.MUTED)
        config = dict(self.app.config_store.data)

        def work():
            instance = modrinth_data.resolve_instance(config)
            return {"instance": instance, "mods": modrinth_data.list_mods(instance.get("path", ""))}

        self.app.run_async(work, self._on_loaded)

    def _on_loaded(self, result, error):
        self.loading = False
        if error is not None:
            self.mods = []
            self.table.delete(*self.table.get_children())
            self.subtitle.configure(text="No instance could be read.")
            self._set_status(str(error), theme.ERROR)
            return

        self.loaded = True
        self.instance = result["instance"]
        self.mods = result["mods"]

        folder = modrinth_data.mods_dir(self.instance.get("path", ""))
        self.subtitle.configure(text=f"{self.instance.get('name', '?')}  ·  {folder}")
        self._fill_list()
        if not self._keep_status:
            self._set_status("", theme.MUTED)
        self._keep_status = False

    def _fill_list(self):
        self.table.delete(*self.table.get_children())
        for index, mod in enumerate(self.mods):
            self.table.insert(
                "", "end", iid=str(index),
                values=self._row_values(mod),
                tags=theme.zebra_tag(index) if mod["enabled"] else theme.zebra_tag(index, "muted"),
            )

        if self.mods:
            self.table.selection_set("0")
        self._update_summary()
        self._sync_buttons()

    @staticmethod
    def _row_values(mod):
        return (
            "ON" if mod["enabled"] else "OFF",
            mod["name"],
            mod["version"] or "-",
            mod["filename"],
            modrinth_data.human_size(mod["size"]),
        )

    def _update_summary(self):
        if not self.mods:
            self.summary.configure(text="no mods found")
            return
        enabled = sum(1 for mod in self.mods if mod["enabled"])
        self.summary.configure(text=f"{len(self.mods)} jars · {enabled} enabled")

    # -------------------------------------------------------------- actions

    def _selected_mods(self):
        """[(index, mod), ...] for every highlighted row, in list order."""
        picked = []
        for row in self.table.selection():
            index = int(row)
            if index < len(self.mods):
                picked.append((index, self.mods[index]))
        return sorted(picked)

    def _sync_buttons(self):
        picked = self._selected_mods()
        theme.set_button_enabled(self.toggle_button, bool(picked))
        theme.set_button_enabled(self.remove_button, bool(picked))
        # With a mixed selection, the button offers to turn everything on.
        turning_off = bool(picked) and all(mod["enabled"] for _index, mod in picked)
        self.toggle_button.configure(text="Disable" if turning_off else "Enable")

    def toggle_selected(self):
        picked = self._selected_mods()
        if not picked:
            return

        want_enabled = not all(mod["enabled"] for _index, mod in picked)
        changed, failed = 0, ""

        for index, mod in picked:
            if mod["enabled"] == want_enabled:
                continue
            try:
                new_path = modrinth_data.set_mod_enabled(mod["path"], want_enabled)
            except modrinth_data.LaunchError as exc:
                failed = str(exc)
                continue

            mod["path"] = new_path
            mod["filename"] = os.path.basename(new_path)
            mod["enabled"] = want_enabled
            self.table.item(
                str(index),
                values=self._row_values(mod),
                tags=theme.zebra_tag(index) if want_enabled else theme.zebra_tag(index, "muted"),
            )
            changed += 1

        self._update_summary()
        self._sync_buttons()
        self.app.refresh_summary()

        if failed:
            self._set_status(failed, theme.ERROR)
        elif changed:
            state = "enabled" if want_enabled else "disabled"
            what = picked[0][1]["name"] if changed == 1 else f"{changed} mods"
            self._set_status(f"{what} {state}. Restart Minecraft for it to take effect.", theme.OK)

    # ------------------------------------------------------- add and remove

    def install_blueprint_mod(self):
        """Build the Blueprint mod from the source beside the launcher, and install it.

        The point of the button is that this is otherwise a JDK, a Gradle
        invocation and a copy into a folder six levels deep in AppData.
        """
        if self.building:
            return
        if not self.instance.get("path"):
            self._set_status("Pick an instance on the Instances page first.", theme.ERROR)
            return
        if not blueprint_mod.available():
            self._set_status(blueprint_mod.describe(), theme.ERROR)
            return

        building = blueprint_mod.needs_build()
        self.building = True
        theme.set_button_enabled(self.blueprint_button, False)
        self._set_status(
            "Building the mod - the first build fetches Minecraft and takes a few minutes..."
            if building
            else "Installing the mod...",
            theme.MUTED,
        )

        instance_path = self.instance.get("path", "")

        def work():
            jar = blueprint_mod.ensure_jar()
            added, _conflicts, errors = modrinth_data.install_mods(
                instance_path, [jar], replace=True
            )
            return jar, added, errors

        self.app.run_async(work, self._on_blueprint_installed)

    def _on_blueprint_installed(self, result, error):
        self.building = False
        theme.set_button_enabled(self.blueprint_button, True)

        if isinstance(error, blueprint_mod.BuildError):
            # The end of the build log is where Gradle says what it disliked.
            messagebox.showerror(
                "The mod did not build",
                f"{error}\n\n{error.output}" if error.output else str(error),
                parent=self,
            )
            self._set_status(str(error).splitlines()[0], theme.ERROR)
            return
        if error is not None:
            self._set_status(str(error), theme.ERROR)
            return

        _jar, added, errors = result
        if added:
            self.load(refresh=True, keep_status=True)
            self.app.refresh_summary()
        self._report(added, errors, verb="Installed")

    def add_mods(self):
        if not self.instance.get("path"):
            self._set_status("Pick an instance on the Instances page first.", theme.ERROR)
            return

        sources = filedialog.askopenfilenames(
            parent=self,
            title="Add mods to this instance",
            filetypes=[("Minecraft mods", "*.jar"), ("All files", "*.*")],
        )
        if not sources:
            return
        self._install(list(sources), replace=False)

    def _install(self, sources, replace):
        self._set_status(f"Copying {len(sources)} file(s)...", theme.MUTED)
        instance_path = self.instance.get("path", "")
        self.app.run_async(
            lambda: modrinth_data.install_mods(instance_path, sources, replace),
            self._on_installed,
        )

    def _on_installed(self, result, error):
        if error is not None:
            self._pending_added = []
            self._set_status(str(error), theme.ERROR)
            return

        added, conflicts, errors = result
        # A replace pass runs as a second install, so names are collected across
        # both and the list is only reloaded once everything has been copied.
        self._pending_added.extend(added)

        if conflicts:
            names = ", ".join(os.path.basename(path) for path in conflicts[:3])
            more = f" and {len(conflicts) - 3} more" if len(conflicts) > 3 else ""
            if messagebox.askyesno(
                "Already installed",
                f"{names}{more} is already in this instance.\n\nReplace it?",
                parent=self,
            ):
                self._install(conflicts, replace=True)
                return

        done = self._pending_added
        self._pending_added = []
        if done:
            self.load(refresh=True, keep_status=True)
            self.app.refresh_summary()
        self._report(done, errors, verb="Added")

    def remove_selected(self):
        picked = self._selected_mods()
        if not picked:
            return

        names = ", ".join(mod["name"] for _index, mod in picked[:3])
        more = f" and {len(picked) - 3} more" if len(picked) > 3 else ""
        where = "the Recycle Bin" if recycle.available() else "nowhere - this cannot be undone"
        if not messagebox.askyesno(
            "Remove mods",
            f"Remove {names}{more} from this instance?\n\nThe file goes to {where}.",
            parent=self,
            icon="warning",
        ):
            return

        paths = [mod["path"] for _index, mod in picked]
        self._set_status(f"Removing {len(paths)} mod(s)...", theme.MUTED)
        self.app.run_async(lambda: modrinth_data.remove_mods(paths), self._on_removed)

    def _on_removed(self, result, error):
        if error is not None:
            self._set_status(str(error), theme.ERROR)
            return

        removed, errors = result
        if removed:
            self.load(refresh=True, keep_status=True)
            self.app.refresh_summary()
        self._report(removed, errors, verb="Removed")

    def _report(self, done, errors, verb):
        if errors:
            first_name, reason = errors[0]
            extra = f" (+{len(errors) - 1} more)" if len(errors) > 1 else ""
            self._set_status(f"{first_name}: {reason}{extra}", theme.ERROR)
        elif done:
            what = done[0] if len(done) == 1 else f"{len(done)} mods"
            self._set_status(f"{verb} {what}. Restart Minecraft to load the change.", theme.OK)

    def open_folder(self):
        folder = modrinth_data.mods_dir(self.instance.get("path", ""))
        if not folder or not os.path.isdir(folder):
            self._set_status("This instance has no mods folder yet.", theme.ERROR)
            return
        try:
            os.startfile(folder)  # noqa: S606 - opening the user's own mods folder
        except (OSError, AttributeError) as exc:
            self._set_status(f"Could not open the folder: {exc}", theme.ERROR)

    def _set_status(self, message, color):
        self.status.configure(text=message, fg=color)
