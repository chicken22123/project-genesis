"""Obsidian Client: a desktop launcher for the Minecraft files Modrinth manages.

The window is a top bar of tabs over four pages - Play, Instances, Mods and
Settings.
Anything that reads the disk or the Modrinth database runs on a worker thread
and comes back through ``self.events``, so the UI never blocks while a launch,
a folder scan or a jar read is in progress.
"""

import os
import queue
import shutil
import subprocess
import sys
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


def _bundle_dir():
    """Where the read-only assets live.

    A PyInstaller build unpacks them into its own directory, which is not where
    ``__file__`` points, so the icon has to be looked up separately from the
    files the launcher writes.
    """
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.executable)))
    return os.path.dirname(os.path.abspath(__file__))


def _data_dir():
    """Where the config and the log live - somewhere that outlives the run.

    A onefile build unpacks into a temp directory that Windows deletes on exit,
    so a config written beside ``__file__`` would be gone by the next launch.
    Beside the exe is the findable place for it; an install under Program Files
    is read-only, and falls back to %APPDATA%.
    """
    if not getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(__file__))

    beside_exe = os.path.dirname(os.path.abspath(sys.executable))
    if os.access(beside_exe, os.W_OK):
        return beside_exe

    roaming = os.environ.get("APPDATA") or os.path.expanduser("~")
    fallback = os.path.join(roaming, "ObsidianClient")
    try:
        os.makedirs(fallback, exist_ok=True)
    except OSError:
        return beside_exe
    return fallback


BUNDLE_DIR = _bundle_dir()
DATA_DIR = _data_dir()
CONFIG_PATH = os.path.join(DATA_DIR, "blueprint_instance.json")
LOG_PATH = os.path.join(DATA_DIR, "launch.log")
ICON_PATH = os.path.join(BUNDLE_DIR, "app_icon.ico")
# The same mark as a PNG, for the platforms Tk will not hand a .ico to and for
# the ChromeOS launcher entry.
ICON_PNG_PATH = os.path.join(BUNDLE_DIR, "app_icon.png")

APP_VERSION = "1.2.0"

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


class TopBar(tk.Canvas):
    """The crystal logo, the page tabs and the selected instance, in one strip.

    A canvas rather than widgets so the selected tab can carry a glowing
    underline and the logo can be drawn instead of loaded from a file.
    """

    HEIGHT = 62

    def __init__(self, parent, app, pages):
        super().__init__(
            parent, height=self.HEIGHT, bg=theme.BG_TOP, highlightthickness=0, bd=0
        )
        self.app = app
        self.pages = pages
        self.selected = pages[0][0]
        self.hover = None
        self.summary = {}
        self._tabs = {}

        self.bind("<Configure>", lambda _event: self.redraw())
        self.bind("<Motion>", self._on_motion)
        self.bind("<Leave>", lambda _event: self._set_hover(None))
        self.bind("<Button-1>", self._on_click)

    def select(self, name):
        self.selected = name
        self.redraw()

    def set_summary(self, summary):
        self.summary = dict(summary)
        self.redraw()

    def _tab_at(self, x, y):
        for key, (x0, y0, x1, y1) in self._tabs.items():
            if x0 <= x <= x1 and y0 <= y <= y1:
                return key
        return None

    def _on_motion(self, event):
        key = self._tab_at(event.x, event.y)
        self.configure(cursor="hand2" if key else "")
        self._set_hover(key)

    def _set_hover(self, key):
        if key != self.hover:
            self.hover = key
            self.redraw()

    def _on_click(self, event):
        key = self._tab_at(event.x, event.y)
        if key:
            self.app.show_page(key)

    def redraw(self):
        self.delete("all")
        width, height = self.winfo_width(), self.HEIGHT
        if width <= 1:
            return
        mid = height / 2

        theme.draw_gem(self, 36, mid, 30)
        first, second = theme.BRAND_WORDS
        word_font = theme.font(13, "bold")
        x = 62
        self.create_text(x, mid, text=first, anchor="w", font=word_font, fill=theme.ACCENT_HOVER)
        x += word_font.measure(first + " ")
        self.create_text(x, mid, text=second, anchor="w", font=word_font, fill=theme.MUTED)
        x += word_font.measure(second) + 28
        self.create_line(x, mid - 12, x, mid + 12, fill=theme.PANEL_BORDER)
        x += 30

        tab_font = theme.font(10, "bold")
        self._tabs = {}
        for key, caption in self.pages:
            label = caption.upper()
            tab_width = tab_font.measure(label)
            selected = key == self.selected
            if selected:
                colour = theme.ACCENT_HOVER
            elif key == self.hover:
                colour = theme.TEXT
            else:
                colour = theme.MUTED
            self.create_text(x, mid, text=label, anchor="w", font=tab_font, fill=colour)
            if selected:
                # A soft wide stroke under a crisp one reads as a glow.
                underline = height - 4
                self.create_line(
                    x - 6, underline, x + tab_width + 6, underline, width=6,
                    fill=theme.blend(theme.BG_TOP, theme.ACCENT, 0.18),
                )
                self.create_line(
                    x, underline, x + tab_width, underline, width=2, fill=theme.ACCENT_HOVER
                )
            self._tabs[key] = (x - 14, 0, x + tab_width + 14, height)
            x += tab_width + 34

        right = width - 24
        version = f"v{APP_VERSION}"
        pill_font = theme.font(8, "bold")
        pill_width = pill_font.measure(version) + 20
        theme.rounded_rect(
            self, right - pill_width, mid - 11, right, mid + 11, radius=11,
            fill=theme.TRACK, outline=theme.PANEL_BORDER,
        )
        self.create_text(right - pill_width / 2, mid, text=version, font=pill_font, fill=theme.MUTED)
        right -= pill_width + 16

        if self.summary and right - x > 140:
            loader = (self.summary.get("loader") or "vanilla").title()
            line = f"{self.summary.get('instance', '-')}  ·  {self.summary.get('version', '-')} {loader}"
            line_font = theme.font(9, "bold")
            line = theme.fit_text(line, line_font, right - x - 30)
            self.create_text(right, mid, text=line, anchor="e", font=line_font, fill=theme.ACCENT_LIGHT)
            dot_x = right - line_font.measure(line) - 12
            found = bool(self.summary.get("path"))
            self.create_oval(
                dot_x - 3, mid - 3, dot_x + 3, mid + 3, outline="",
                fill=theme.OK if found else theme.MUTED,
            )

        self.create_line(0, height - 1, width, height - 1, fill=theme.PANEL_BORDER)


class ObsidianApp:
    def __init__(self, root):
        self.root = root
        self.root.title(theme.BRAND_NAME)
        self.root.geometry("1020x680")
        self.root.minsize(900, 600)
        self.root.configure(bg=theme.BG_TOP)
        theme.apply_ttk_theme(root)

        # The same mark the desktop shortcut uses, so the window and the taskbar
        # match instead of showing Tk's default feather.
        # iconbitmap only accepts .ico, and only on Windows. Everywhere else
        # Tk reads the PNG through PhotoImage - which needs the reference kept,
        # or it is collected and the window goes back to Tk's feather.
        icon_set = False
        if os.path.isfile(ICON_PATH):
            try:
                self.root.iconbitmap(default=ICON_PATH)
                icon_set = True
            except tk.TclError:
                pass
        if not icon_set and os.path.isfile(ICON_PNG_PATH):
            try:
                self._icon_image = tk.PhotoImage(file=ICON_PNG_PATH)
                self.root.iconphoto(True, self._icon_image)
            except tk.TclError:
                pass

        self.log_path = LOG_PATH
        self.config_store = ConfigStore(CONFIG_PATH)
        self.events = queue.Queue()
        self.game_process = None
        self.current_page = ""

        page_names = [
            ("play", "Play"),
            ("instances", "Instances"),
            ("mods", "Mods"),
            ("settings", "Settings"),
        ]
        self.topbar = TopBar(root, self, page_names)
        self.topbar.pack(fill="x")

        self.content = theme.frame(root)
        self.content.pack(fill="both", expand=True)

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

        self.play.append(f"{theme.BRAND_NAME} {APP_VERSION} ready.", "accent")
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
        self.topbar.select(name)
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
        """Reload the instance shown in the top bar and on the Play page."""
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
        self.topbar.set_summary(summary)

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
    _log(f"--- {theme.BRAND_NAME} starting ---")
    # className becomes the window's WM_CLASS, which is how the ChromeOS
    # launcher (and any Linux shelf) ties the open window to its icon.
    root = tk.Tk(className="ObsidianClient")
    ObsidianApp(root)
    root.mainloop()
    _log(f"--- {theme.BRAND_NAME} closed ---")


if __name__ == "__main__":
    main()
