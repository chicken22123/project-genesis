"""The Play page: one full-window scene, with the launch dock at its foot.

Everything above the console is a single canvas: a spotlight, drifting
particles, a slowly turning obsidian crystal with lights orbiting it, the
title, and the dock that holds the instance, the launch status and the
buttons. Every few seconds a glint runs across the crystal's faces and a
shine travels through the second word of the title. Clickable parts of the
canvas are tracked as hotspots.

Tk canvases have no transparency, so nothing here is a see-through overlay:
every effect recolours its own items against the light it sits on.

The console lives in a drawer under the scene. It stays shut until asked for,
and opens by itself when a launch fails so the reason is never hidden.

Effects move and recolour canvas items that already exist instead of
redrawing every frame, and stop whenever the page is hidden, minimised or
covered by the loading screen.
"""

import math
import os
import random
import subprocess
import sys
import time
import tkinter as tk

import theme

MAX_CONSOLE_LINES = 2000

FRAME_MS = 33          # ~30 fps while the Play page is on screen
IDLE_MS = 250          # how often to check back while it is not
PARTICLE_COUNT = 46
GLINT_SWEEP_S = 1.4    # how long a glint takes to cross the crystal
GLINT_PERIOD_S = 6.0   # one glint (and one title shine) every this many seconds
PULSE_PERIOD_S = 2.4   # one breath of the launch button's glow
SPIN_RAD_S = 0.32      # crystal rotation speed
ORBIT_RAD_S = 0.9      # the light circling the crystal
SPOT_MAX = 0.065       # brightness of the spotlight's core

DOCK_HEIGHT = 96
DOCK_MARGIN = 24
DRAWER_HEIGHT = 220

STATE_COLORS = {
    "Ready": theme.OK,
    "Running": theme.OK,
    "Launching": theme.ACCENT_LIGHT,
    "Error": theme.ERROR,
}

# An octahedron stretched tall, so it reads as a crystal rather than a die.
# Faces are oriented at runtime (see _face_normal), so their winding here
# does not matter.
_GEM_VERTICES = (
    (0.0, -1.45, 0.0),   # top point
    (1.0, 0.0, 0.0),
    (0.0, 0.0, 1.0),
    (-1.0, 0.0, 0.0),
    (0.0, 0.0, -1.0),
    (0.0, 1.15, 0.0),    # bottom point
)
_GEM_FACES = (
    (0, 1, 2), (0, 2, 3), (0, 3, 4), (0, 4, 1),
    (5, 1, 2), (5, 2, 3), (5, 3, 4), (5, 4, 1),
)
# Key light from the upper left, in front of the scene (the viewer looks along
# +z), and a weaker fill from the lower right so the underside never goes black.
_LIGHT = (-0.52, -0.70, -0.49)
_FILL_LIGHT = (0.45, 0.55, -0.70)


class PlayPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=theme.BG_TOP, bd=0, highlightthickness=0)
        self.app = app

        self.status_message = "Waiting for launch..."
        self.percent = 0
        self.state_label = "Ready"
        self.button_text = "Launch"
        self.button_enabled = True
        self.error = False
        self.summary = {
            "instance": "Loading...", "game_version": "", "version": "-",
            "loader": "vanilla", "mods": 0, "path": "",
        }

        self._hotspots = {}
        self._hover = None

        # Effects. Particle positions are fractions of the scene so a resize
        # does not bunch them up; canvas item ids are re-made on every redraw.
        self._rng = random.Random()
        self._particles = [self._spawn_particle(anywhere=True) for _ in range(PARTICLE_COUNT)]
        self._particle_ids = []
        self._letter_ids = []
        self._ring_ids = []
        self._gem_ids = []
        self._orbit_ids = []
        self._glint_id = None
        self._bar = (0, 0, 0)
        self._scene = (0, 0, 0, 0)
        self._spot = (0.0, 0.0, 1.0, 1.0)
        self._gem = (0.0, 0.0, 0.0)
        self._orbit = (0.0, 0.0, 0.0, 0.0)
        self._clock = 0.0
        self._last_tick = time.monotonic()

        # height=1 so the canvas never forces the window taller; pack decides.
        self.canvas = tk.Canvas(self, bg=theme.BG_TOP, highlightthickness=0, bd=0, height=1)
        self.drawer = theme.frame(self, bg=theme.BG_TOP)
        self.log_open = False
        self._build_drawer()
        self.canvas.pack(fill="both", expand=True)

        self.canvas.bind("<Configure>", lambda _event: self.redraw())
        self.canvas.bind("<Motion>", self._on_motion)
        self.canvas.bind("<Leave>", lambda _event: self._set_hover(None))
        self.canvas.bind("<Button-1>", self._on_click)

        self.after(FRAME_MS, self._tick)

    # ---------------------------------------------------------------- drawer

    def _build_drawer(self):
        panel = theme.panel(self.drawer)
        panel.pack(fill="both", expand=True, padx=DOCK_MARGIN, pady=(0, 18))
        panel.configure(height=DRAWER_HEIGHT)
        panel.pack_propagate(False)

        head = theme.frame(panel, bg=theme.PANEL_BG)
        head.pack(fill="x", padx=16, pady=(12, 8))
        theme.eyebrow(head, "Launch log").pack(side="left")
        theme.button(head, "Close", lambda: self.set_log_open(False)).pack(side="right")
        theme.button(head, "Open log file", self._open_log_file).pack(side="right", padx=(0, 8))
        theme.button(head, "Clear", self.clear_console).pack(side="right", padx=(0, 8))
        theme.button(head, "Copy", self._copy_console).pack(side="right", padx=(0, 8))
        self.follow = tk.BooleanVar(value=True)
        theme.checkbutton(head, "Follow output", self.follow).pack(side="right", padx=(0, 14))

        body = theme.frame(panel, bg=theme.PANEL_BG)
        body.pack(fill="both", expand=True, padx=16, pady=(0, 14))
        self.text = theme.text(body)
        scroll = theme.scrollbar(body, self.text.yview)
        self.text.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.text.pack(side="left", fill="both", expand=True)

    def set_log_open(self, value):
        if value == self.log_open:
            return
        self.log_open = value
        if value:
            self.drawer.pack(side="bottom", fill="x", before=self.canvas)
            if self.follow.get():
                self.text.see("end")
        else:
            self.drawer.pack_forget()
        self.redraw()

    # --------------------------------------------------------------- console

    def append(self, line, tag="muted"):
        self.text.configure(state="normal")
        self.text.insert("end", line + "\n", tag)

        # Minecraft is chatty; keeping every line would eventually stall the UI.
        line_count = int(self.text.index("end-1c").split(".")[0])
        if line_count > MAX_CONSOLE_LINES:
            self.text.delete("1.0", f"{line_count - MAX_CONSOLE_LINES}.0")

        self.text.configure(state="disabled")
        if self.follow.get():
            self.text.see("end")

    def clear_console(self):
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.configure(state="disabled")

    def _copy_console(self):
        body = self.text.get("1.0", "end-1c")
        self.clipboard_clear()
        self.clipboard_append(body)
        self.append("Console copied to the clipboard.", "accent")

    def _open_log_file(self):
        path = self.app.log_path
        if not os.path.isfile(path):
            self.append("No launch.log has been written yet.", "muted")
            return
        try:
            os.startfile(path)  # noqa: S606 - opening the user's own log file
        except (OSError, AttributeError):
            # os.startfile is Windows-only, so everywhere else lands here and
            # notepad.exe was never going to work. Hand the file to whatever
            # the desktop uses instead.
            opener = "open" if sys.platform == "darwin" else "xdg-open"
            try:
                subprocess.Popen([opener, path])
            except OSError:
                self.append(f"Could not open a viewer. The log is at {path}", "muted")

    def _open_instance_folder(self):
        path = self.summary.get("path") or ""
        if not path or not os.path.isdir(path):
            self.append("The instance folder could not be found.", "error")
            self.set_log_open(True)
            return
        try:
            os.startfile(path)  # noqa: S606 - opening the user's own game folder
        except AttributeError:
            opener = "open" if sys.platform == "darwin" else "xdg-open"
            try:
                subprocess.Popen([opener, path])
            except OSError as exc:
                self.append(f"Could not open the folder: {exc}", "error")
        except OSError as exc:
            self.append(f"Could not open the folder: {exc}", "error")

    # ------------------------------------------------------------------ state

    def set_stage(self, message, percent, state, button_text=None, error=False):
        self.status_message = message
        self.percent = percent
        self.state_label = state
        self.error = error
        if button_text:
            self.button_enabled = True
            self.button_text = button_text
        if error:
            # A failure should never hide behind a closed drawer.
            self.set_log_open(True)
        self.redraw()

    def set_button(self, text, enabled):
        # The app still says "Launch Game"; the dock button has room for one word.
        self.button_text = "Launch" if text == "Launch Game" else text
        self.button_enabled = enabled
        self.redraw()

    def set_summary(self, summary):
        self.summary.update(summary)
        self.redraw()

    # --------------------------------------------------------------- pointer

    def _hotspot_at(self, x, y):
        for name, (x0, y0, x1, y1, enabled) in self._hotspots.items():
            if enabled and x0 <= x <= x1 and y0 <= y <= y1:
                return name
        return None

    def _on_motion(self, event):
        name = self._hotspot_at(event.x, event.y)
        self.canvas.configure(cursor="hand2" if name else "")
        self._set_hover(name)

    def _set_hover(self, name):
        if name != self._hover:
            self._hover = name
            self.redraw()

    def _on_click(self, event):
        name = self._hotspot_at(event.x, event.y)
        if name == "launch":
            self.app.launch_game()
        elif name == "stop":
            self.app.stop_game()
        elif name == "log":
            self.set_log_open(not self.log_open)
        elif name == "change":
            self.app.show_page("instances")
        elif name == "mods":
            self.app.show_page("mods")
        elif name == "folder":
            self._open_instance_folder()

    # --------------------------------------------------------------- drawing

    def redraw(self):
        c = self.canvas
        c.delete("all")
        self._hotspots = {}
        self._particle_ids, self._letter_ids, self._ring_ids = [], [], []
        self._gem_ids, self._orbit_ids, self._glint_id = [], [], None

        width, height = c.winfo_width(), c.winfo_height()
        if width <= 1 or height <= 1:
            return
        self._scene = (0, 0, width, height)

        # Back to front.
        self._draw_backdrop(width, height)
        self._make_particles()
        self._make_crystal(width, height)
        self._draw_title(width, height)
        self._draw_dock(width, height)
        self._animate()

    def _draw_backdrop(self, width, height):
        c = self.canvas
        # The page itself is flat; the spotlight starts from exactly that
        # colour, so its outer edge has nothing to show against.
        cx, cy = self._crystal_centre(width, height)
        rx_max, ry_max = width * 0.36, height * 0.46
        self._spot = (cx, cy, rx_max, ry_max)
        steps = 24
        for index in range(steps):
            k = index / (steps - 1)
            rx, ry = rx_max * (1.0 - 0.86 * k), ry_max * (1.0 - 0.80 * k)
            c.create_oval(
                cx - rx, cy - ry, cx + rx, cy + ry, outline="",
                fill=theme.blend(theme.BG_TOP, theme.ACCENT, SPOT_MAX * k * k),
            )

        # A faint floor reflection under the crystal.
        size = self._crystal_size(width, height)
        floor_y = cy + size * 1.55
        for index, mix in enumerate((0.015, 0.03, 0.05)):
            rx, ry = size * (1.5 - 0.4 * index), size * (0.16 - 0.04 * index)
            c.create_oval(
                cx - rx, floor_y - ry, cx + rx, floor_y + ry, outline="",
                fill=theme.blend(theme.BG_TOP, theme.ACCENT, self._spot_mix(cx, floor_y) + mix),
            )

    def _spot_mix(self, x, y):
        """How bright the spotlight is at (x, y), as a mix toward ACCENT.

        Anything drawn over the spotlight starts from this, so it is always
        lighter than what is behind it - the closest Tk gets to blending.
        """
        cx, cy, rx_max, ry_max = self._spot
        dx, dy = (x - cx) / rx_max, (y - cy) / ry_max
        reach = math.sqrt(dx * dx + dy * dy)
        if reach >= 1.0:
            return 0.0
        k = min(1.0, (1.0 - reach) / 0.84)
        return SPOT_MAX * k * k

    def _crystal_centre(self, width, height):
        dock_top = height - DOCK_MARGIN - DOCK_HEIGHT
        return width * 0.73, dock_top * 0.47

    def _crystal_size(self, width, height):
        dock_top = height - DOCK_MARGIN - DOCK_HEIGHT
        return max(28.0, min(dock_top * 0.27, width * 0.13))

    def _draw_title(self, width, height):
        c = self.canvas
        dock_top = height - DOCK_MARGIN - DOCK_HEIGHT
        left = 48
        column = max(260.0, width * 0.52 - left)

        first, second = theme.BRAND_WORDS
        size = max(26, min(60, int(dock_top / 7.2)))
        while size > 26 and theme.font(size, "bold").measure(first) > column:
            size -= 2
        big = theme.font(size, "bold")

        block = size * 2.4 + 120              # title lines, eyebrow, tagline, pills
        top = max(28.0, (dock_top - block) / 2)

        loader = (self.summary.get("loader") or "vanilla").upper()
        version = self.summary.get("game_version") or self.summary.get("version") or ""
        eyebrow = f"MINECRAFT {version}".strip() + f"   ·   {loader}"
        c.create_rectangle(left, top + 6, left + 18, top + 8, fill=theme.ACCENT, outline="")
        c.create_text(
            left + 28, top + 7, anchor="w", font=theme.font(9, "bold"), fill=theme.MUTED,
            text=eyebrow,
        )

        y = top + 26 + size * 0.62
        c.create_text(left - 2, y, text=first, anchor="w", font=big, fill=theme.ACCENT_HOVER)
        y += size * 1.18
        # One item per letter, so a shine can travel through the word.
        for index, letter in enumerate(second):
            x = left - 2 + big.measure(second[:index])
            item = c.create_text(
                x, y, text=letter, anchor="w", font=big,
                fill=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, 0.42),
            )
            self._letter_ids.append(item)

        y += size * 0.62 + 24
        c.create_text(
            left, y, anchor="w", font=theme.font(11), fill=theme.MUTED,
            text="Your instances and your mods, one click from the game.",
        )

        # Quick actions as outline pills.
        y += 38
        x = left
        pill_font = theme.font(9, "bold")
        for name, caption in (("mods", "Manage mods"), ("folder", "Open game folder")):
            pill_width = pill_font.measure(caption) + 32
            hovered = self._hover == name
            theme.rounded_rect(
                c, x, y - 15, x + pill_width, y + 15, radius=15,
                fill=theme.TRACK if hovered else "",
                outline=theme.ACCENT_LIGHT if hovered else theme.PANEL_BORDER,
            )
            c.create_text(
                x + pill_width / 2, y, text=caption, font=pill_font,
                fill=theme.ACCENT_HOVER if hovered else theme.ACCENT_LIGHT,
            )
            self._hotspots[name] = (x, y - 15, x + pill_width, y + 15, True)
            x += pill_width + 10

    def _draw_dock(self, width, height):
        c = self.canvas
        x0, x1 = DOCK_MARGIN, width - DOCK_MARGIN
        y1 = height - DOCK_MARGIN
        y0 = y1 - DOCK_HEIGHT
        mid = (y0 + y1) / 2

        theme.rounded_rect(c, x0, y0, x1, y1, radius=16, fill=theme.PANEL_BG, outline=theme.PANEL_BORDER)
        c.create_line(
            x0 + 20, y0 + 1, x1 - 20, y0 + 1,
            fill=theme.blend(theme.PANEL_BORDER, theme.ACCENT, 0.22),
        )

        # Instance: crystal tile, name, details, and a way to change it.
        tile = 56
        tx0, ty0 = x0 + 20, mid - tile / 2
        theme.rounded_rect(
            c, tx0, ty0, tx0 + tile, ty0 + tile, radius=12,
            fill=theme.TRACK, outline=theme.PANEL_BORDER,
        )
        theme.draw_gem(c, tx0 + tile / 2, ty0 + tile / 2, tile * 0.58)

        text_x = tx0 + tile + 16
        text_width = 190
        name_font = theme.font(13, "bold")
        c.create_text(
            text_x, mid - 20, anchor="w", font=name_font, fill=theme.TEXT,
            text=theme.fit_text(self.summary.get("instance", "-"), name_font, text_width),
        )
        loader = (self.summary.get("loader") or "vanilla").title()
        detail = f"{self.summary.get('version', '-')}  ·  {loader}  ·  {self.summary.get('mods', 0)} mods"
        detail_font = theme.font(9)
        c.create_text(
            text_x, mid + 1, anchor="w", font=detail_font, fill=theme.MUTED,
            text=theme.fit_text(detail, detail_font, text_width),
        )
        link_font = theme.font(9, "bold")
        link = "Change instance  ›"
        hovered = self._hover == "change"
        c.create_text(
            text_x, mid + 22, anchor="w", font=link_font, text=link,
            fill=theme.ACCENT_HOVER if hovered else theme.ACCENT_LIGHT,
        )
        self._hotspots["change"] = (
            text_x, mid + 13, text_x + link_font.measure(link), mid + 31, True
        )

        # Buttons, laid out from the right edge.
        button_h = 56
        by0, by1 = mid - button_h / 2, mid + button_h / 2
        launch_x1 = x1 - 20
        launch_x0 = launch_x1 - 210
        stop_x1 = launch_x0 - 10
        stop_x0 = stop_x1 - 86
        log_x1 = stop_x0 - 10
        log_x0 = log_x1 - 64
        self._draw_launch_button(launch_x0, by0, launch_x1, by1)
        self._draw_stop_button(stop_x0, by0, stop_x1, by1)
        self._draw_ghost_button("log", "LOG", log_x0, by0, log_x1, by1, active=self.log_open)

        # Status and progress in whatever room is left between the two.
        sx0, sx1 = text_x + text_width + 24, log_x0 - 24
        if sx1 - sx0 >= 120:
            self._draw_status(sx0, sx1, mid)

    def _draw_launch_button(self, x0, y0, x1, y1):
        c = self.canvas
        hovered = self._hover == "launch"
        if not self.button_enabled:
            fill, text_colour = theme.DISABLED_FILL, theme.MUTED
        elif hovered:
            fill, text_colour = theme.ACCENT_HOVER, theme.BG_TOP
        else:
            fill, text_colour = theme.ACCENT, theme.BG_TOP

        if self.button_enabled:
            # Halo rings; _animate makes them breathe from their base mix.
            for spread, mix in ((11, 0.09), (7, 0.18), (4, 0.38)):
                ring = theme.rounded_rect(
                    c, x0 - spread, y0 - spread, x1 + spread, y1 + spread,
                    radius=13 + spread, fill="", outline=theme.PANEL_BG,
                )
                self._ring_ids.append((ring, mix))

        theme.rounded_rect(c, x0, y0, x1, y1, radius=13, fill=fill, outline="")
        label = self.button_text.upper()
        label_font = theme.font(13, "bold")
        offset = 9 if self.button_enabled else 0
        c.create_text(
            (x0 + x1) / 2 + offset, (y0 + y1) / 2,
            text=label, font=label_font, fill=text_colour,
        )
        if self.button_enabled:
            # A small play triangle ahead of the word.
            tx = (x0 + x1) / 2 + offset - label_font.measure(label) / 2 - 20
            ty = (y0 + y1) / 2
            c.create_polygon(tx, ty - 7, tx + 12, ty, tx, ty + 7, fill=text_colour, outline="")
        self._hotspots["launch"] = (x0, y0, x1, y1, self.button_enabled)

    def _draw_stop_button(self, x0, y0, x1, y1):
        c = self.canvas
        running = self.app.game_running()
        hovered = self._hover == "stop"
        if not running:
            fill, colour, line = "", theme.MUTED, theme.PANEL_BORDER
        elif hovered:
            fill, colour, line = theme.STOP_RED_HOVER, theme.BG_TOP, ""
        else:
            fill, colour, line = theme.STOP_RED, theme.BG_TOP, ""
        theme.rounded_rect(c, x0, y0, x1, y1, radius=13, fill=fill, outline=line)
        c.create_text((x0 + x1) / 2, (y0 + y1) / 2, text="STOP", font=theme.font(10, "bold"), fill=colour)
        self._hotspots["stop"] = (x0, y0, x1, y1, running)

    def _draw_ghost_button(self, name, label, x0, y0, x1, y1, active=False):
        c = self.canvas
        hovered = self._hover == name
        theme.rounded_rect(
            c, x0, y0, x1, y1, radius=13,
            fill=theme.SELECT_BG if active else (theme.TRACK if hovered else ""),
            outline=theme.ACCENT_LIGHT if (hovered or active) else theme.PANEL_BORDER,
        )
        c.create_text(
            (x0 + x1) / 2, (y0 + y1) / 2, text=label, font=theme.font(10, "bold"),
            fill=theme.ACCENT_HOVER if (hovered or active) else theme.ACCENT_LIGHT,
        )
        self._hotspots[name] = (x0, y0, x1, y1, True)

    def _draw_status(self, x0, x1, mid):
        c = self.canvas
        colour = theme.ERROR if self.error else STATE_COLORS.get(self.state_label, theme.ACCENT_LIGHT)
        c.create_oval(x0, mid - 18, x0 + 8, mid - 10, fill=colour, outline="")
        state_font = theme.font(10, "bold")
        c.create_text(x0 + 16, mid - 14, anchor="w", text=self.state_label.upper(),
                      font=state_font, fill=colour)
        c.create_text(x1, mid - 14, anchor="e", text=f"{int(self.percent)}%",
                      font=state_font, fill=theme.ACCENT_LIGHT)

        message_font = theme.font(9)
        c.create_text(
            x0, mid + 20, anchor="w", font=message_font,
            fill=theme.ERROR if self.error else theme.MUTED,
            text=theme.fit_text(self.status_message, message_font, x1 - x0),
        )

        bar_y = mid + 4
        c.create_line(x0, bar_y, x1, bar_y, fill=theme.TRACK, width=4, capstyle=tk.ROUND)
        filled = (x1 - x0) * max(0.0, min(100.0, float(self.percent))) / 100.0
        if filled > 1:
            fill = theme.ERROR if self.error else theme.ACCENT_LIGHT
            c.create_line(x0, bar_y, x0 + filled, bar_y, fill=fill, width=4, capstyle=tk.ROUND)
            if self.state_label == "Launching" and not self.error:
                self._glint_id = c.create_line(
                    x0, bar_y, x0, bar_y, fill=theme.ACCENT_HOVER, width=4, capstyle=tk.ROUND
                )
                self._bar = (x0, filled, bar_y)

    # --------------------------------------------------------------- effects

    def _spawn_particle(self, anywhere=False):
        rng = self._rng
        return {
            "u": rng.random(),                                              # across, 0..1
            "v": rng.random() if anywhere else 1.0 + rng.random() * 0.15,   # down, 0..1
            "speed": rng.uniform(0.018, 0.06),     # scene-heights per second
            "drift": rng.uniform(-0.008, 0.008),   # sideways wander
            "size": rng.choice((1.0, 1.0, 1.5, 1.5, 2.0, 2.5)),
            "glow": rng.uniform(0.12, 0.5),        # peak brightness
            "twinkle": rng.uniform(1.0, 3.0),      # radians per second
            "phase": rng.uniform(0.0, math.tau),
        }

    def _make_particles(self):
        for _ in self._particles:
            self._particle_ids.append(
                self.canvas.create_oval(0, 0, 0, 0, fill=theme.BG_TOP, outline="")
            )

    def _make_crystal(self, width, height):
        c = self.canvas
        cx, cy = self._crystal_centre(width, height)
        size = self._crystal_size(width, height)
        self._gem = (cx, cy, size)

        # An orbit ring, split so its far half passes behind the crystal and
        # its near half in front.
        rx, ry = size * 2.0, size * 0.42
        oy = cy + size * 0.18
        self._orbit = (cx, oy, rx, ry)
        self._orbit_back = c.create_arc(
            cx - rx, oy - ry, cx + rx, oy + ry, start=0, extent=180, style="arc",
            outline=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, 0.20),
        )
        self._gem_ids = [
            c.create_polygon(0, 0, 0, 0, 0, 0, outline="", fill=theme.BG_TOP)
            for _ in _GEM_FACES
        ]
        self._orbit_front = c.create_arc(
            cx - rx, oy - ry, cx + rx, oy + ry, start=180, extent=180, style="arc",
            outline=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, 0.55),
        )
        # Two lights riding the ring, half a turn apart.
        self._orbit_ids = [
            c.create_oval(0, 0, 0, 0, outline="", fill=theme.ACCENT_HOVER),
            c.create_oval(0, 0, 0, 0, outline="", fill=theme.ACCENT_LIGHT),
        ]

    def _tick(self):
        now = time.monotonic()
        dt = min(0.1, now - self._last_tick)
        self._last_tick = now

        loading = getattr(self.app, "loading", None)
        if not self.winfo_viewable() or (loading is not None and loading.visible):
            # Off screen: keep the loop alive but spend nothing on it.
            self.after(IDLE_MS, self._tick)
            return

        self._clock += dt
        for index, particle in enumerate(self._particles):
            particle["v"] -= particle["speed"] * dt
            particle["u"] += particle["drift"] * dt
            if particle["v"] < -0.05 or not -0.05 < particle["u"] < 1.05:
                self._particles[index] = self._spawn_particle()
        self._animate()
        self.after(FRAME_MS, self._tick)

    def _animate(self):
        """Move and recolour the effect items for the current moment."""
        c = self.canvas
        sx0, sy0, sx1, sy1 = self._scene
        if sx1 <= sx0:
            return
        t = self._clock

        for item, particle in zip(self._particle_ids, self._particles):
            x = sx0 + (sx1 - sx0) * particle["u"]
            y = sy0 + (sy1 - sy0) * particle["v"]
            edge = max(0.0, min(1.0, particle["v"] * 4, (1.0 - particle["v"]) * 4))
            shine = 0.55 + 0.45 * math.sin(t * particle["twinkle"] + particle["phase"])
            r = particle["size"]
            c.coords(item, x - r, y - r, x + r, y + r)
            mix = self._spot_mix(x, y) + particle["glow"] * shine * edge
            c.itemconfigure(item, fill=theme.blend(theme.BG_TOP, theme.ACCENT, mix))

        glint = _glint_position(t)
        self._animate_title(glint)
        self._animate_crystal(t, glint)

        pulse = 0.5 + 0.5 * math.sin(t * math.tau / PULSE_PERIOD_S)
        strength = (1.3 if self._hover == "launch" else 0.55) + 0.45 * pulse
        for ring, mix in self._ring_ids:
            c.itemconfigure(
                ring, outline=theme.blend(theme.PANEL_BG, theme.ACCENT, min(1.0, mix * strength))
            )

        if self._glint_id is not None:
            bx0, filled, by = self._bar
            span = max(14.0, min(60.0, filled * 0.3))
            head = bx0 - span + (filled + span) * ((t * 0.7) % 1.0)
            start, end = max(bx0, head), min(bx0 + filled, head + span)
            if end - start > 1:
                c.coords(self._glint_id, start, by, end, by)
                c.itemconfigure(self._glint_id, state="normal")
            else:
                c.itemconfigure(self._glint_id, state="hidden")

    def _animate_title(self, glint):
        """A shine running left to right through the second word."""
        if not self._letter_ids:
            return
        count = len(self._letter_ids)
        centre = None if glint is None else -1.5 + glint * (count + 2.0)
        for index, item in enumerate(self._letter_ids):
            mix = 0.42
            if centre is not None:
                mix += 0.55 * math.exp(-((index - centre) / 1.1) ** 2)
            self.canvas.itemconfigure(
                item, fill=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, min(1.0, mix))
            )

    def _animate_crystal(self, t, glint):
        if not self._gem_ids:
            return
        c = self.canvas
        cx, cy, size = self._gem
        cy += math.sin(t * 1.1) * size * 0.06          # a slow float
        spin = t * SPIN_RAD_S
        tilt = 0.28 + 0.05 * math.sin(t * 0.5)

        cos_s, sin_s = math.cos(spin), math.sin(spin)
        cos_t, sin_t = math.cos(tilt), math.sin(tilt)
        rotated = []
        for x, y, z in _GEM_VERTICES:
            x, z = x * cos_s + z * sin_s, -x * sin_s + z * cos_s      # turn about y
            y, z = y * cos_t - z * sin_t, y * sin_t + z * cos_t      # tip toward the viewer
            rotated.append((x, y, z))

        for item, face in zip(self._gem_ids, _GEM_FACES):
            a, b, d = (rotated[i] for i in face)
            normal = _face_normal(a, b, d)
            if normal[2] >= 0:          # facing away from the viewer
                c.itemconfigure(item, state="hidden")
                continue
            key = max(0.0, sum(n * l for n, l in zip(normal, _LIGHT)))
            fill_light = max(0.0, sum(n * l for n, l in zip(normal, _FILL_LIGHT)))
            # Dark glassy faces under bright edges: obsidian, not grey plastic.
            shade = 0.07 + 0.36 * key ** 1.5 + 0.10 * fill_light
            outline = 0.42 + 0.46 * key
            if glint is not None:
                # The glint is a band sweeping left to right across the stone.
                face_x = (a[0] + b[0] + d[0]) / 3
                sweep = -1.6 + glint * 3.2
                flash = math.exp(-((face_x - sweep) / 0.32) ** 2)
                shade += 0.22 * flash
                outline += 0.35 * flash
            fill = theme.blend(theme.BG_TOP, theme.ACCENT, min(1.0, shade))
            edge = theme.blend(theme.BG_TOP, theme.ACCENT, min(1.0, outline))
            c.coords(item, *(coord for v in (a, b, d)
                             for coord in (cx + v[0] * size, cy + v[1] * size)))
            c.itemconfigure(item, state="normal", fill=fill, outline=edge)

        # The two lights on the ring: behind the crystal on the far half.
        ox, oy, rx, ry = self._orbit
        for index, item in enumerate(self._orbit_ids):
            angle = t * ORBIT_RAD_S + math.pi * index
            x, y = ox + rx * math.cos(angle), oy + ry * math.sin(angle)
            r = 3.0 if index == 0 else 2.0
            c.coords(item, x - r, y - r, x + r, y + r)
            if math.sin(angle) < 0:
                c.tag_lower(item, self._gem_ids[0])
            else:
                c.tag_raise(item, self._orbit_front)


def _face_normal(a, b, d):
    """Unit outward normal of a triangle on a shape centred at the origin."""
    u = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
    v = (d[0] - a[0], d[1] - a[1], d[2] - a[2])
    n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
    centroid = ((a[0] + b[0] + d[0]) / 3, (a[1] + b[1] + d[1]) / 3, (a[2] + b[2] + d[2]) / 3)
    if sum(n_i * c_i for n_i, c_i in zip(n, centroid)) < 0:
        n = (-n[0], -n[1], -n[2])        # point it outward, whatever the winding
    length = math.sqrt(sum(n_i * n_i for n_i in n)) or 1.0
    return (n[0] / length, n[1] / length, n[2] / length)


def _glint_position(t):
    """0..1 while a glint is crossing, None while waiting for the next one."""
    progress = (t % GLINT_PERIOD_S) / GLINT_SWEEP_S
    return _ease_in_out(progress) if progress <= 1.0 else None


def _ease_in_out(t):
    """Smoothstep: slow at both ends, so a glint glides rather than snaps."""
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)
