import json
import os
import queue
import shutil
import subprocess
import threading
import tkinter as tk
from collections import deque
from tkinter import ttk

import modrinth_launcher
from loading_screen import LoadingScreen

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "blueprint_instance.json")


class BlueprintLauncherApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Blueprint Client 1.21.11")
        self.root.geometry("860x560")
        self.root.minsize(760, 500)
        self.root.configure(bg="#071319")

        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("TFrame", background="#071319")
        style.configure("Card.TFrame", background="#0d1f2b", borderwidth=1, relief="solid")
        style.configure("Sidebar.TFrame", background="#08141a", borderwidth=0)
        style.configure("TLabel", background="#071319", foreground="#f3fbff", font=("Segoe UI", 10))
        style.configure("Title.TLabel", font=("Segoe UI", 18, "bold"))
        style.configure("Subtitle.TLabel", font=("Segoe UI", 10), foreground="#8fb0bf")
        style.configure("Accent.TButton", background="#59f2a2", foreground="#02131b", font=("Segoe UI", 11, "bold"))
        style.map("Accent.TButton", background=[("active", "#73f8b3")])
        style.configure("Nav.TButton", background="#08141a", foreground="#f3fbff", font=("Segoe UI", 10))
        style.map("Nav.TButton", background=[("active", "#132c38")])
        style.configure("TProgressbar", background="#59f2a2", troughcolor="#122433", thickness=10)

        self.game_process = None
        self.events = queue.Queue()

        self.build_ui()
        self.loading = LoadingScreen(self.root)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(80, self._pump_events)

    def build_ui(self):
        main = ttk.Frame(self.root, padding=20)
        main.pack(fill="both", expand=True)
        main.grid_columnconfigure(0, weight=1)
        main.grid_rowconfigure(1, weight=1)

        sidebar = ttk.Frame(main, style="Sidebar.TFrame", padding=18)
        sidebar.grid(row=0, column=0, rowspan=2, sticky="nsw")
        sidebar.grid_propagate(False)
        sidebar.configure(width=240)

        ttk.Label(sidebar, text="Blueprint Client", style="Title.TLabel").pack(anchor="w")
        ttk.Label(sidebar, text="Desktop launcher", style="Subtitle.TLabel").pack(anchor="w", pady=(4, 18))

        ttk.Label(sidebar, text="Profile", foreground="#8fb0bf").pack(anchor="w")
        ttk.Label(sidebar, text="Explorer", font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(2, 0))
        ttk.Label(sidebar, text="Offline • No mods", foreground="#8fb0bf").pack(anchor="w", pady=(2, 18))

        for label in ["Play", "Installations", "Settings", "News"]:
            btn = ttk.Button(sidebar, text=label, style="Nav.TButton", width=18)
            btn.pack(anchor="w", pady=3)

        content = ttk.Frame(main, padding=(0, 0, 0, 0))
        content.grid(row=0, column=1, sticky="nsew")
        content.grid_columnconfigure(0, weight=1)

        header = ttk.Frame(content)
        header.grid(row=0, column=0, sticky="ew", pady=(0, 20))
        ttk.Label(header, text="Minecraft", foreground="#8fb0bf").pack(anchor="w")
        ttk.Label(header, text="Blueprint Client 1.21.11", style="Title.TLabel").pack(anchor="w")
        ttk.Label(header, text="A polished desktop launcher for clean vanilla play.", style="Subtitle.TLabel").pack(anchor="w")

        hero = ttk.Frame(content, style="Card.TFrame", padding=20)
        hero.grid(row=1, column=0, sticky="ew", pady=(0, 16))
        hero.grid_columnconfigure(0, weight=1)
        ttk.Label(hero, text="Latest release", foreground="#8fb0bf").grid(row=0, column=0, sticky="w")
        ttk.Label(hero, text="Launch Minecraft directly", font=("Segoe UI", 14, "bold")).grid(row=1, column=0, sticky="w", pady=(4, 4))
        ttk.Label(
            hero,
            text="Starts the game straight from your Modrinth files. The Modrinth app never opens.",
            foreground="#8fb0bf",
        ).grid(row=2, column=0, sticky="w")
        self.play_button = ttk.Button(hero, text="Launch Game", style="Accent.TButton", command=self.launch_game)
        self.play_button.grid(row=0, column=1, rowspan=3, sticky="e")

        stats = ttk.Frame(content)
        stats.grid(row=2, column=0, sticky="ew", pady=(0, 16))
        stats.grid_columnconfigure(0, weight=1)
        stats.grid_columnconfigure(1, weight=1)
        stats.grid_columnconfigure(2, weight=1)

        self.create_stat_card(stats, 0, "Version", "1.21.11", "Release-ready")
        self.create_stat_card(stats, 1, "Mode", "Vanilla", "No mods required")
        self.create_stat_card(stats, 2, "Status", "Ready", "Clean startup")

        bottom = ttk.Frame(content)
        bottom.grid(row=3, column=0, sticky="ew")
        bottom.grid_columnconfigure(0, weight=1)

        log_frame = ttk.Frame(bottom, style="Card.TFrame", padding=16)
        log_frame.grid(row=0, column=0, sticky="ew")
        ttk.Label(log_frame, text="Launch log").pack(anchor="w")
        self.progress = ttk.Progressbar(log_frame, mode="determinate", maximum=100, length=420)
        self.progress.pack(fill="x", pady=(8, 6))
        self.status_label = ttk.Label(log_frame, text="Waiting for launch...", foreground="#f3fbff")
        self.status_label.pack(anchor="w")

        self.progress_value = ttk.Label(log_frame, text="0%", foreground="#59f2a2")
        self.progress_value.pack(anchor="e")

        self.status_text = ttk.Label(content, text="Ready", foreground="#59f2a2")
        self.status_text.grid(row=4, column=0, sticky="w", pady=(8, 0))

    def create_stat_card(self, parent, col, title, main, sub):
        card = ttk.Frame(parent, style="Card.TFrame", padding=12)
        card.grid(row=0, column=col, sticky="nsew", padx=6)
        ttk.Label(card, text=title, foreground="#8fb0bf").pack(anchor="w")
        ttk.Label(card, text=main, font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(4, 0))
        ttk.Label(card, text=sub, foreground="#8fb0bf").pack(anchor="w")

    # ------------------------------------------------------------- launching

    def launch_game(self):
        if self.game_process and self.game_process.poll() is None:
            self._set_stage("Minecraft is already running.", 100, "Running")
            return

        self.play_button.configure(state="disabled", text="Launching...")
        self.loading.show("Reading your Modrinth game files...")
        self._set_stage("Reading your Modrinth game files...", 4, "Launching")

        worker = threading.Thread(target=self._launch_worker, daemon=True)
        worker.start()

    def _launch_worker(self):
        """Build the java command and start the game off the UI thread."""
        config = self._load_config()
        try:
            process, plan = modrinth_launcher.launch_minecraft(
                config,
                progress=lambda percent, message: self._post("stage", percent, message),
            )
        except modrinth_launcher.LaunchError as exc:
            self._post("failed", str(exc))
            return
        except Exception as exc:  # unexpected, but the UI still needs to recover
            self._post("failed", f"Unexpected launch error: {exc}")
            return

        self._post("started", process, plan)
        self._watch_game(process)

    def _watch_game(self, process):
        """Keep the tail of the game log so a crash can explain itself."""
        tail = deque(maxlen=40)
        if process.stdout:
            for line in process.stdout:
                tail.append(line.rstrip())
        code = process.wait()
        self._post("exited", code, list(tail))

    def _post(self, kind, *payload):
        self.events.put((kind, payload))

    def _pump_events(self):
        """Deliver worker-thread events on the Tk thread."""
        try:
            while True:
                kind, payload = self.events.get_nowait()
                handler = getattr(self, f"_on_{kind}")
                handler(*payload)
        except queue.Empty:
            pass
        finally:
            self.root.after(80, self._pump_events)

    def _on_stage(self, percent, message):
        self._set_stage(message, percent, "Launching")

    def _on_started(self, process, plan):
        self.game_process = process
        note = plan.warnings[0] if plan.warnings else f"Playing as {plan.account['username']}"
        self._set_stage(f"Minecraft {plan.version_id} is starting. {note}", 100, "Running")
        self.play_button.configure(state="disabled", text="Game running")
        self.loading.hide_after(1600)

    def _on_exited(self, code, tail):
        self.game_process = None
        self.play_button.configure(state="normal", text="Launch Game")

        if code == 0:
            self._set_stage("Minecraft closed.", 0, "Ready")
            return

        reason = next((line for line in reversed(tail) if line.strip()), "")
        message = f"Minecraft exited with code {code}."
        if reason:
            message += f" {reason[:180]}"
        self._set_stage(message, 0, "Error", error=True)
        self.loading.hide_after(3500)

    def _on_failed(self, message):
        self._set_stage(message, 20, "Error", button_text="Try Again", error=True)
        self.loading.hide_after(4500)
        self._maybe_open_modrinth_app()

    def _set_stage(self, message, percent, state, button_text=None, error=False):
        """Update the launcher panel and the loading screen from one place."""
        self.status_label.configure(text=message)
        self.progress['value'] = percent
        self.progress_value.configure(text=f"{percent}%")
        self.status_text.configure(text=state)

        if button_text:
            self.play_button.configure(state="normal", text=button_text)

        if error:
            self.loading.set_error(message)
        else:
            self.loading.set_progress(percent, message)

    # ---------------------------------------------------------- last resort

    def _maybe_open_modrinth_app(self):
        """Opt-in fallback: only opens Modrinth if the config asks for it."""
        config = self._load_config()
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

    # --------------------------------------------------------------- config

    def _load_config(self):
        if not os.path.exists(CONFIG_PATH):
            return {}
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                return json.load(fh)
        except (OSError, ValueError):
            return {}

    def _on_close(self):
        # The game keeps running on its own; only stop watching it.
        self.root.destroy()


def main():
    root = tk.Tk()
    BlueprintLauncherApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
