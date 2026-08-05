"""Blueprint Client: a desktop launcher for the Minecraft files Modrinth manages.

The window is a sidebar plus four pages - Play, Instances, Mods and Settings.
Anything that reads the disk or the Modrinth database runs on a worker thread
and comes back through ``self.events``, so the UI never blocks while a launch,
a folder scan or a jar read is in progress.
"""

import os
import queue
import shutil
import subprocess
import threading
import traceback
import tkinter as tk
from collections import deque

import modrinth_data
import modrinth_launcher
import theme
from applog import RotatingLog
from config_store import ConfigStore
from loading_screen import LoadingScreen
from page_instances import InstancesPage
from page_mods import ModsPage
from page_play import PlayPage
from page_settings import SettingsPage

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "blueprint_instance.json")
LOG_PATH = os.path.join(HERE, "launch.log")

APP_VERSION = "1.1.0"

LOG = RotatingLog(LOG_PATH)


def _log(message):
    """Append a timestamped line to launch.log so a launch can be diagnosed later."""
    LOG.write(message)


def _redacted_command_text(command):
    """The java command line, with --accessToken's value blanked out for the log."""
    parts = []
    redact_next = False
    for part in command:
        if redact_next:
            parts.append("***")
            redact_next = False
        else:
            parts.append(part)
        if part == "--accessToken":
            redact_next = True
    return " ".join(f'"{part}"' if " " in part else part for part in parts)


class Sidebar(tk.Frame):
    """Brand block, the instance currently selected, and the page switcher."""

    def __init__(self, parent, app, pages):
        super().__init__(parent, bg=theme.PANEL_BG, bd=0, highlightthickness=0, width=212)
        self.pack_propagate(False)
        self.app = app

        brand = theme.frame(self, bg=theme.PANEL_BG)
        brand.pack(fill="x", padx=20, pady=(24, 0))
        row = theme.frame(brand, bg=theme.PANEL_BG)
        row.pack(fill="x")
        theme.logo_badge(row, "B", size=38).pack(side="left")
        words = theme.frame(row, bg=theme.PANEL_BG)
        words.pack(side="left", padx=(10, 0))
        # Stacked, because the two words together are wider than the sidebar.
        theme.label(words, "BLUEPRINT", size=13, weight="bold", fg=theme.ACCENT).pack(anchor="w")
        theme.label(words, "CLIENT", size=13, weight="bold", fg=theme.ACCENT_LIGHT).pack(anchor="w")
        theme.label(brand, "Desktop launcher", size=9, fg=theme.MUTED).pack(anchor="w", pady=(8, 0))

        card = theme.panel(self, bg=theme.PANEL_ALT)
        card.pack(fill="x", padx=20, pady=(20, 18))
        theme.eyebrow(card, "Playing", bg=theme.PANEL_ALT).pack(anchor="w", padx=14, pady=(12, 4))
        self.instance_label = theme.label(
            card, "Loading...", size=11, weight="bold", bg=theme.PANEL_ALT,
            anchor="w", justify="left", wraplength=150,
        )
        self.instance_label.pack(anchor="w", padx=14)
        self.version_label = theme.label(
            card, "", size=9, fg=theme.MUTED, bg=theme.PANEL_ALT, anchor="w",
            justify="left", wraplength=150,
        )
        self.version_label.pack(anchor="w", padx=14, pady=(2, 12))

        theme.separator(self).pack(fill="x", padx=20, pady=(0, 14))

        self.buttons = {}
        for key, caption in pages:
            widget = theme.nav_button(
                self, caption, lambda name=key: self.app.show_page(name),
                icon=theme.NAV_ICONS.get(key, ""),
            )
            widget.pack(fill="x", padx=10, pady=2)
            self.buttons[key] = widget

        footer = theme.frame(self, bg=theme.PANEL_BG)
        footer.pack(side="bottom", fill="x", padx=20, pady=16)
        theme.label(footer, f"v{APP_VERSION}", size=8, fg=theme.MUTED).pack(anchor="w")

    def select(self, name):
        for key, widget in self.buttons.items():
            theme.set_nav_selected(widget, key == name)

    def set_summary(self, summary):
        self.instance_label.configure(text=summary.get("instance", "-"))
        loader = (summary.get("loader") or "vanilla").title()
        self.version_label.configure(text=f"{summary.get('version', '-')} · {loader}")


class BlueprintApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Blueprint Client")
        self.root.geometry("1020x680")
        self.root.minsize(900, 600)
        self.root.configure(bg=theme.BG_TOP)
        theme.apply_ttk_theme(root)

        self.log_path = LOG_PATH
        self.config_store = ConfigStore(CONFIG_PATH)
        self.events = queue.Queue()
        self.game_process = None
        self.current_page = ""

        body = theme.frame(root)
        body.pack(fill="both", expand=True)

        page_names = [
            ("play", "Play"),
            ("instances", "Instances"),
            ("mods", "Mods"),
            ("settings", "Settings"),
        ]
        self.sidebar = Sidebar(body, self, page_names)
        self.sidebar.pack(side="left", fill="y")
        theme.separator(body, bg=theme.PANEL_BORDER).pack(side="left", fill="y")

        self.content = theme.frame(body)
        self.content.pack(side="left", fill="both", expand=True)

        self.pages = {
            "play": PlayPage(self.content, self),
            "instances": InstancesPage(self.content, self),
            "mods": ModsPage(self.content, self),
            "settings": SettingsPage(self.content, self),
        }
        self.play = self.pages["play"]

        self.loading = LoadingScreen(root)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.config_store.subscribe(self._on_config_changed)

        self.show_page("play")
        if self.config_store.error:
            self.play.append(self.config_store.error, "error")

        self.play.append(f"Blueprint Client {APP_VERSION} ready.", "accent")
        self.refresh_summary()
        self.root.after(80, self._pump_events)

    # ----------------------------------------------------------- navigation

    def show_page(self, name):
        if name == self.current_page:
            return
        for key, page in self.pages.items():
            if key == name:
                page.pack(fill="both", expand=True)
            else:
                page.pack_forget()

        self.current_page = name
        self.sidebar.select(name)
        on_show = getattr(self.pages[name], "on_show", None)
        if on_show:
            on_show()

    # -------------------------------------------------------------- threads

    def run_async(self, work, on_done):
        """Run ``work()`` off the UI thread; ``on_done(result, error)`` runs on it."""

        def runner():
            try:
                self._post("async", on_done, work(), None)
            except Exception as exc:  # surfaced to the page that asked for the work
                self._post("async", on_done, None, exc)

        threading.Thread(target=runner, daemon=True).start()

    def _post(self, kind, *payload):
        self.events.put((kind, payload))

    def _pump_events(self):
        """Deliver worker-thread events on the Tk thread."""
        try:
            while True:
                kind, payload = self.events.get_nowait()
                getattr(self, f"_on_{kind}")(*payload)
        except queue.Empty:
            pass
        finally:
            self.root.after(80, self._pump_events)

    def _on_async(self, on_done, result, error):
        on_done(result, error)

    # -------------------------------------------------------------- summary

    def refresh_summary(self):
        """Reload the instance shown in the sidebar and on the Play page."""
        config = dict(self.config_store.data)

        def work():
            instance = modrinth_data.resolve_instance(config)
            return {
                "instance": instance.get("name") or "Unnamed instance",
                "path": instance.get("path") or "",
                "game_version": instance.get("game_version") or "",
                "version": config.get("version_id")
                or instance.get("game_version")
                or "Minecraft",
                "loader": instance.get("loader") or "vanilla",
                "mods": modrinth_data.count_mods(instance.get("path", "")),
            }

        self.run_async(work, self._on_summary)

    def _on_summary(self, summary, error):
        if error is not None:
            summary = {
                "instance": "No instance", "path": "", "game_version": "", "version": "-",
                "loader": "-", "mods": 0,
            }
            self.play.append(str(error), "error")
        self.play.set_summary(summary)
        self.sidebar.set_summary(summary)

    def _on_config_changed(self, _config):
        modrinth_data.invalidate()
        self.refresh_summary()
        for page in self.pages.values():
            handler = getattr(page, "on_config_changed", None)
            if handler:
                handler()

    # ------------------------------------------------------------- launching

    def game_running(self):
        return self.game_process is not None and self.game_process.poll() is None

    def launch_game(self):
        if self.game_running():
            self.play.set_stage("Minecraft is already running.", 100, "Running")
            return

        self.play.set_button("Launching...", False)
        self.loading.show("Reading your Modrinth game files...")
        self.play.set_stage("Reading your Modrinth game files...", 4, "Launching")
        self.play.append("--- Launch requested ---", "accent")

        _log("=== Launch requested ===")
        threading.Thread(target=self._launch_worker, daemon=True).start()

    def stop_game(self):
        if not self.game_running():
            return
        _log("=== Stop requested ===")
        self.play.append("Stopping Minecraft...", "accent")
        self.game_process.terminate()

    def _launch_worker(self):
        """Build the java command and start the game off the UI thread."""
        config = self.config_store.as_launch_config()

        def report_stage(percent, message):
            _log(f"stage {percent}%: {message}")
            self._post("stage", percent, message)

        try:
            process, plan = modrinth_launcher.launch_minecraft(config, progress=report_stage)
        except modrinth_launcher.LaunchError as exc:
            _log(f"launch failed: {exc}")
            self._post("failed", str(exc))
            return
        except Exception as exc:  # unexpected, but the UI still needs to recover
            _log(f"unexpected launch error: {exc}\n{traceback.format_exc()}")
            self._post("failed", f"Unexpected launch error: {exc}")
            return

        _log(f"java: {plan.java}")
        _log(f"version: {plan.version_id}")
        _log(f"game dir: {plan.cwd}")
        for warning in plan.warnings:
            _log(f"warning: {warning}")
        _log(f"command: {_redacted_command_text(plan.command)}")
        _log(f"started pid={process.pid}")

        self._post("started", process, plan)
        self._watch_game(process)

    def _watch_game(self, process):
        """Mirror the game output into the console and keep the tail for crashes."""
        tail = deque(maxlen=40)
        if process.stdout:
            for line in process.stdout:
                line = line.rstrip()
                tail.append(line)
                _log(f"[game] {line}")
                self._post("output", line)
        code = process.wait()
        _log(f"game exited with code {code}")
        self._post("exited", code, list(tail))

    # ---------------------------------------------------------------- events

    def _on_stage(self, percent, message):
        self.play.set_stage(message, percent, "Launching")
        self.play.append(f"[{percent:3d}%] {message}", "accent")

    def _on_output(self, line):
        self.play.append(line)

    def _on_started(self, process, plan):
        self.game_process = process
        note = plan.warnings[0] if plan.warnings else f"Playing as {plan.account['username']}"
        self.play.set_button("Game running", False)
        self.play.set_stage(f"Minecraft {plan.version_id} is starting. {note}", 100, "Running")
        self.play.append(f"Java:    {plan.java}", "muted")
        self.play.append(f"Version: {plan.version_id}", "muted")
        self.play.append(f"Folder:  {plan.cwd}", "muted")
        for warning in plan.warnings:
            self.play.append(f"warning: {warning}", "error")
        self.play.append(f"Started Minecraft (pid {process.pid}).", "ok")
        self.loading.hide_after(1600)

    def _on_exited(self, code, tail):
        self.game_process = None
        self.play.set_button("Launch Game", True)

        if code == 0:
            self.play.set_stage("Minecraft closed.", 0, "Ready")
            self.play.append("Minecraft closed.", "ok")
            return

        reason = next((line for line in reversed(tail) if line.strip()), "")
        message = f"Minecraft exited with code {code}."
        if reason:
            message += f" {reason[:180]}"
        self.play.set_stage(message, 0, "Error", error=True)
        self.play.append(message, "error")
        self.loading.hide_after(3500)

    def _on_failed(self, message):
        self.play.set_stage(message, 20, "Error", button_text="Try Again", error=True)
        self.play.append(message, "error")
        self.loading.hide_after(4500)
        self._maybe_open_modrinth_app()

    # ---------------------------------------------------------- last resort

    def _maybe_open_modrinth_app(self):
        """Opt-in fallback: only opens Modrinth if the config asks for it."""
        config = self.config_store.data
        if not config.get("fallback_to_modrinth_app"):
            return

        exe_path = self._find_modrinth_executable(config.get("modrinth_executable", ""))
        if not exe_path:
            return

        try:
            subprocess.Popen(
                [exe_path],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except OSError:
            pass

    def _find_modrinth_executable(self, configured_path=""):
        if configured_path and os.path.exists(configured_path):
            return configured_path

        candidates = [
            os.path.expandvars(r"%LOCALAPPDATA%\Modrinth App\Modrinth App.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\modrinth-app\modrinth-app.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\Modrinth App\modrinth-app.exe"),
            os.path.expandvars(r"%ProgramFiles%\Modrinth App\modrinth-app.exe"),
        ]
        for path in candidates:
            if os.path.exists(path):
                return path

        for alias in ["modrinth-app", "Modrinth App", "modrinth"]:
            found = shutil.which(alias)
            if found:
                return found
        return None

    def _on_close(self):
        # The game keeps running on its own; only stop watching it.
        self.root.destroy()


def main():
    _log("--- Blueprint Client starting ---")
    root = tk.Tk()
    BlueprintApp(root)
    root.mainloop()
    _log("--- Blueprint Client closed ---")


if __name__ == "__main__":
    main()
