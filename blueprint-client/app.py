import json
import os
import shutil
import subprocess
import sys
import tkinter as tk
import webbrowser
from tkinter import ttk
from urllib.parse import quote

from loading_screen import LoadingScreen

CONFIG_PATH = os.path.join(os.path.dirname(__file__), "blueprint_instance.json")


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

        self.build_ui()
        self.loading = LoadingScreen(self.root)

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
        ttk.Label(hero, text="Launch through Modrinth", font=("Segoe UI", 14, "bold")).grid(row=1, column=0, sticky="w", pady=(4, 4))
        ttk.Label(hero, text="Using Modrinth as the launcher path for a clean Minecraft experience.", foreground="#8fb0bf").grid(row=2, column=0, sticky="w")
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

    def launch_game(self):
        self.play_button.configure(state="disabled", text="Launching...")
        self.loading.show("Looking for Minecraft...")
        self._set_stage("Looking for Minecraft...", 5, "Launching")

        self.root.after(300, self._try_launch_real_minecraft)

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

    def _try_launch_real_minecraft(self):
        config = self._load_config()
        instance_name = config.get("instance_name", "Blueprint")
        modrinth_exe = config.get("modrinth_executable", "")

        exe_path = self._find_modrinth_executable(modrinth_exe)
        if exe_path:
            self._set_stage("Found Modrinth. Preparing your profile...", 35, "Launching")
            try:
                instance_id = self._resolve_instance_id(instance_name)
                if instance_id:
                    self._launch_modrinth_instance(exe_path, instance_name, instance_id)
                    return

                subprocess.Popen([exe_path], shell=False)
                self._set_stage(
                    f"Opened Modrinth. Please start the '{instance_name}' profile manually.",
                    100,
                    "Ready",
                    button_text="Open Again",
                )
                self.loading.hide_after(2500)
                return
            except Exception as exc:
                self._set_stage(
                    f"Could not open Modrinth: {exc}",
                    25,
                    "Error",
                    button_text="Try Again",
                    error=True,
                )
                self.loading.hide_after(3500)
                return

        self._set_stage(
            "Modrinth app was not found on this system.",
            25,
            "Not found",
            button_text="Try Again",
            error=True,
        )
        self.loading.hide_after(3500)

    def _find_modrinth_executable(self, configured_path=""):
        candidates = []
        if configured_path and os.path.exists(configured_path):
            return configured_path
        if configured_path:
            candidates.append(configured_path)

        candidates.extend([
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\modrinth-app\modrinth-app.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\Modrinth App\modrinth-app.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Programs\Modrinth\modrinth-app.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WindowsApps\Modrinth.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WindowsApps\modrinth-app.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WindowsApps\ModrinthApp.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WindowsApps\modrinthapp.exe"),
            os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WindowsApps\Modrinth.exe"),
            os.path.expandvars(r"%ProgramFiles%\Modrinth App\modrinth-app.exe"),
            os.path.expandvars(r"%ProgramFiles(x86)%\Modrinth App\modrinth-app.exe"),
            os.path.expandvars(r"%ProgramFiles%\Modrinth\modrinth-app.exe"),
            os.path.expandvars(r"%ProgramFiles(x86)%\Modrinth\modrinth-app.exe"),
            os.path.expandvars(r"%USERPROFILE%\AppData\Local\Programs\Modrinth App\modrinth-app.exe"),
            os.path.expandvars(r"%USERPROFILE%\AppData\Local\Programs\modrinth-app\modrinth-app.exe"),
            os.path.expandvars(r"%USERPROFILE%\AppData\Local\Microsoft\WindowsApps\Modrinth.exe"),
            os.path.expandvars(r"%USERPROFILE%\AppData\Local\Microsoft\WindowsApps\modrinth-app.exe"),
        ])

        for path in candidates:
            if os.path.exists(path):
                return path

        for alias in ["modrinth-app", "modrinth", "Modrinth", "ModrinthApp", "modrinthapp"]:
            found = shutil.which(alias)
            if found:
                return found
        return None

    def _find_instance_path(self, instance_name):
        candidates = []
        if instance_name:
            candidates.extend([
                os.path.expandvars(rf"%APPDATA%\modrinth\instances\{instance_name}"),
                os.path.expandvars(rf"%LOCALAPPDATA%\modrinth\instances\{instance_name}"),
                os.path.expandvars(rf"%LOCALAPPDATA%\Programs\Modrinth App\instances\{instance_name}"),
                os.path.expandvars(rf"%LOCALAPPDATA%\Programs\modrinth-app\instances\{instance_name}"),
                os.path.expandvars(rf"%LOCALAPPDATA%\Programs\Modrinth\instances\{instance_name}"),
                os.path.expandvars(rf"%USERPROFILE%\AppData\Roaming\modrinth\instances\{instance_name}"),
            ])

        for path in candidates:
            if os.path.isdir(path):
                return path
        return ""

    def _launch_modrinth_instance(self, exe_path, instance_name, instance_id):
        self._set_stage(f"Opening Modrinth and selecting '{instance_name}'...", 70, "Launching")

        try:
            subprocess.Popen([exe_path], shell=False, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

        def send_launch_request():
            encoded_id = quote(instance_id, safe=':')
            launch_uri = f"modrinth://launch/instance/{encoded_id}"
            try:
                subprocess.Popen([exe_path, launch_uri], shell=False, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self._set_stage(
                    f"Opened Modrinth and requested '{instance_name}'.",
                    100,
                    "Launched",
                    button_text="Open Again",
                )
                self.loading.hide_after(2500)
            except Exception as exc:
                self._set_stage(
                    f"Could not request the Modrinth profile: {exc}",
                    25,
                    "Error",
                    button_text="Try Again",
                    error=True,
                )
                self.loading.hide_after(3500)

        self.root.after(2500, lambda: self._set_stage("Waiting for Modrinth to wake up...", 88, "Launching"))
        self.root.after(5000, send_launch_request)

    def _resolve_instance_id(self, instance_name):
        if not instance_name:
            return None

        cleaned = instance_name.strip()
        if cleaned == "Fabric 26.2":
            return "legacy:Fabric 26.2"

        if cleaned.startswith("legacy:"):
            return cleaned

        return None

    def _load_config(self):
        if not os.path.exists(CONFIG_PATH):
            return {}
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                return json.load(fh)
        except Exception:
            return {}


def main():
    root = tk.Tk()
    BlueprintLauncherApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
