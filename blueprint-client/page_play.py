"""The Play page: the hero panel, the launch buttons and the live console.

The top of the page is drawn on a canvas - a dot texture, a spotlight, drifting
particles, a light sheen that sweeps across now and then, and a launch button
that breathes. Everything below it is real Tk widgets.

The effects are animated by moving and recolouring canvas items that already
exist rather than redrawing the hero every frame, and they stop whenever the
page is hidden, minimised or covered by the loading screen.
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

HERO_TOP = 0
HERO_BOTTOM = 152
CHIP_TOP = 164
CHIP_HEIGHT = 70
CANVAS_HEIGHT = CHIP_TOP + CHIP_HEIGHT

DOT_STEP = 22

FRAME_MS = 33          # ~30 fps while the Play page is on screen
IDLE_MS = 250          # how often to check back while it is not
PARTICLE_COUNT = 28
SHEEN_SWEEP_S = 1.6    # how long the light takes to cross the hero
SHEEN_PERIOD_S = 6.0   # one sweep every this many seconds
PULSE_PERIOD_S = 2.4   # one breath of the launch button's glow
SHEEN_HALF_WIDTHS = (74, 60, 47, 35, 24, 13)
SHEEN_MIXES = (0.010, 0.020, 0.030, 0.040, 0.050, 0.060)

STATE_COLORS = {
    "Ready": theme.OK,
    "Running": theme.OK,
    "Launching": theme.ACCENT_LIGHT,
    "Error": theme.ERROR,
}


class PlayPage(tk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, bg=theme.BG_TOP, bd=0, highlightthickness=0)
        self.app = app

        self.status_message = "Waiting for launch..."
        self.percent = 0
        self.state_label = "Ready"
        self.button_text = "Launch Game"
        self.button_enabled = True
        self.error = False
        self.summary = {
            "instance": "Loading...", "game_version": "", "version": "-",
            "loader": "vanilla", "mods": 0, "path": "",
        }

        self._hover = False
        self._stop_hover = False
        self._play_bbox = (0, 0, 0, 0)
        self._stop_bbox = (0, 0, 0, 0)

        # Effects. Particle positions are kept as fractions of the hero so a
        # resize does not bunch them up; the item ids are re-made on redraw.
        self._rng = random.Random()
        self._particles = [self._spawn_particle(anywhere=True) for _ in range(PARTICLE_COUNT)]
        self._particle_ids = []
        self._sheen_ids = []
        self._ring_ids = []
        self._fx_box = (0, 0, 0, 0)
        self._clock = 0.0
        self._last_tick = time.monotonic()

        self.canvas = tk.Canvas(
            self, bg=theme.BG_TOP, highlightthickness=0, bd=0, height=CANVAS_HEIGHT
        )
        self.canvas.pack(fill="x", padx=22, pady=(18, 0))
        self.canvas.bind("<Configure>", lambda _event: self.redraw())
        self.canvas.bind("<Motion>", self._on_motion)
        self.canvas.bind("<Leave>", lambda _event: self._set_hover(False, False))
        self.canvas.bind("<Button-1>", self._on_click)

        self._build_actions()
        self._build_console()
        self.redraw()
        self.after(FRAME_MS, self._tick)

    # ---------------------------------------------------------- quick actions

    def _build_actions(self):
        row = theme.frame(self)
        row.pack(fill="x", padx=22, pady=(12, 0))
        theme.eyebrow(row, "Quick actions", bg=theme.BG_TOP).pack(side="left", pady=(4, 0))

        theme.button(row, "Open Game Folder", self._open_instance_folder).pack(side="right")
        theme.button(row, "Switch Instance", lambda: self.app.show_page("instances")).pack(
            side="right", padx=(0, 8)
        )
        theme.button(row, "Manage Mods", lambda: self.app.show_page("mods")).pack(
            side="right", padx=(0, 8)
        )

    def _open_instance_folder(self):
        path = self.summary.get("path") or ""
        if not path or not os.path.isdir(path):
            self.append("The instance folder could not be found.", "error")
            return
        try:
            os.startfile(path)  # noqa: S606 - opening the user's own game folder
        except (OSError, AttributeError) as exc:
            self.append(f"Could not open the folder: {exc}", "error")

    # ---------------------------------------------------------------- console

    def _build_console(self):
        panel = theme.panel(self)
        panel.pack(fill="both", expand=True, padx=22, pady=(12, 18))

        head = theme.frame(panel, bg=theme.PANEL_BG)
        head.pack(fill="x", padx=18, pady=(14, 0))
        theme.eyebrow(head, "Launch log").pack(side="left")
        self.state_value = theme.label(
            head, self.state_label, size=9, weight="bold", fg=theme.ACCENT_LIGHT
        )
        self.state_value.pack(side="right")

        self.bar = tk.Canvas(panel, height=8, bg=theme.PANEL_BG, highlightthickness=0, bd=0)
        self.bar.pack(fill="x", padx=18, pady=(10, 0))
        self.bar.bind("<Configure>", lambda _event: self._draw_bar())

        status_row = theme.frame(panel, bg=theme.PANEL_BG)
        status_row.pack(fill="x", padx=18, pady=(10, 8))
        self.status_value = theme.label(status_row, self.status_message, size=10, anchor="w")
        self.status_value.pack(side="left", fill="x", expand=True)
        self.percent_value = theme.label(
            status_row, "0%", size=10, weight="bold", fg=theme.ACCENT_LIGHT
        )
        self.percent_value.pack(side="right")

        body = theme.frame(panel, bg=theme.PANEL_BG)
        body.pack(fill="both", expand=True, padx=18)
        self.text = theme.text(body)
        scroll = theme.scrollbar(body, self.text.yview)
        self.text.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.text.pack(side="left", fill="both", expand=True)

        footer = theme.frame(panel, bg=theme.PANEL_BG)
        footer.pack(fill="x", padx=18, pady=(10, 14))
        self.follow = tk.BooleanVar(value=True)
        theme.checkbutton(footer, "Follow output", self.follow).pack(side="left")
        theme.button(footer, "Open log file", self._open_log_file).pack(side="right")
        theme.button(footer, "Clear", self.clear_console).pack(side="right", padx=(0, 8))
        theme.button(footer, "Copy", self._copy_console).pack(side="right", padx=(0, 8))

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

    # ------------------------------------------------------------------ state

    def set_stage(self, message, percent, state, button_text=None, error=False):
        self.status_message = message
        self.percent = percent
        self.state_label = state
        self.error = error
        if button_text:
            self.button_enabled = True
            self.button_text = button_text

        color = theme.ERROR if error else theme.TEXT
        self.status_value.configure(text=message, fg=color)
        self.percent_value.configure(text=f"{percent}%")
        self.state_value.configure(
            text=state, fg=theme.ERROR if error else theme.ACCENT_LIGHT
        )
        self._draw_bar()
        self.redraw()

    def set_button(self, text, enabled):
        self.button_text = text
        self.button_enabled = enabled
        self.redraw()

    def set_summary(self, summary):
        self.summary.update(summary)
        self.redraw()

    # --------------------------------------------------------------- pointer

    @staticmethod
    def _point_in_bbox(x, y, bbox):
        x0, y0, x1, y1 = bbox
        return x0 <= x <= x1 and y0 <= y <= y1

    def _on_motion(self, event):
        play_inside = self.button_enabled and self._point_in_bbox(event.x, event.y, self._play_bbox)
        stop_inside = self.app.game_running() and self._point_in_bbox(
            event.x, event.y, self._stop_bbox
        )
        self.canvas.configure(cursor="hand2" if (play_inside or stop_inside) else "")
        self._set_hover(play_inside, stop_inside)

    def _set_hover(self, play_value, stop_value):
        if play_value != self._hover or stop_value != self._stop_hover:
            self._hover = play_value
            self._stop_hover = stop_value
            self.redraw()

    def _on_click(self, event):
        if self.button_enabled and self._point_in_bbox(event.x, event.y, self._play_bbox):
            self.app.launch_game()
        elif self.app.game_running() and self._point_in_bbox(event.x, event.y, self._stop_bbox):
            self.app.stop_game()

    # --------------------------------------------------------------- drawing

    def _draw_bar(self):
        self.bar.delete("all")
        width = self.bar.winfo_width()
        if width <= 1:
            return
        self.bar.create_line(4, 4, width - 4, 4, fill=theme.TRACK, width=8, capstyle="round")
        filled = (width - 8) * (self.percent / 100.0)
        if filled > 1:
            color = theme.ERROR if self.error else theme.ACCENT
            self.bar.create_line(4, 4, 4 + filled, 4, fill=color, width=8, capstyle="round")

    def redraw(self):
        canvas = self.canvas
        canvas.delete("all")
        self._particle_ids, self._sheen_ids, self._ring_ids = [], [], []
        width = canvas.winfo_width()
        if width <= 1:
            return
        self._draw_hero(width)
        self._draw_chips(width)
        self._animate()

    def _draw_hero(self, width):
        c = self.canvas
        left, right = 0, width - 1
        theme.rounded_rect(
            c, left, HERO_TOP, right, HERO_BOTTOM, radius=14,
            fill=theme.PANEL_BG, outline=theme.PANEL_BORDER,
        )
        # A brighter hairline along the top edge, the way light catches glass.
        c.create_line(
            left + 18, HERO_TOP + 1, right - 18, HERO_TOP + 1,
            fill=theme.blend(theme.PANEL_BORDER, theme.ACCENT, 0.22),
        )

        # Effects live inside the rounded corners: there is no clipping on a
        # Tk canvas, so anything drawn past this box would spill onto the page.
        self._fx_box = (left + 14, HERO_TOP + 10, right - 14, HERO_BOTTOM - 10)

        # Back to front: spotlight, sweeping sheen, dot texture, particles,
        # then the text and buttons on top of all of it.
        self._draw_hero_glow(left, right)
        self._make_sheen()
        self._draw_hero_dots()
        self._make_particles()

        game_version = self.summary.get("game_version") or self.summary.get("version") or ""
        c.create_rectangle(
            left + 24, HERO_TOP + 27, left + 40, HERO_TOP + 29, fill=theme.ACCENT, outline=""
        )
        c.create_text(
            left + 48, HERO_TOP + 28, text="READY TO PLAY", anchor="w",
            font=theme.font(8, "bold"), fill=theme.MUTED,
        )
        c.create_text(
            left + 24, HERO_TOP + 62, text=f"Launch Minecraft {game_version}".rstrip(),
            anchor="w", font=theme.font(19, "bold"), fill=theme.TEXT,
        )

        loader = (self.summary.get("loader") or "vanilla").title()
        detail = f"{self.summary.get('instance', '-')}   ·   {loader}   ·   {self.summary.get('mods', 0)} mods"
        c.create_text(
            left + 24, HERO_TOP + 92, text=detail,
            anchor="w", font=theme.font(10, "bold"), fill=theme.ACCENT_LIGHT,
        )
        c.create_text(
            left + 24, HERO_TOP + 116, text="Starts straight from your Modrinth files.",
            anchor="w", font=theme.font(9), fill=theme.MUTED,
        )

        self._draw_hero_buttons(right)

    def _draw_hero_glow(self, left, right):
        """A soft spotlight, as concentric ellipses fading out from the centre."""
        c = self.canvas
        x0, y0, x1, y1 = self._fx_box
        cx = left + (right - left) * 0.58
        cy = (y0 + y1) / 2
        rx_max = min(300.0, cx - x0, x1 - cx)
        ry_max = (y1 - y0) / 2
        steps = 22
        for index in range(steps):
            t = index / (steps - 1)            # 0 = outermost, 1 = core
            rx = rx_max * (1.0 - 0.82 * t)
            ry = ry_max * (1.0 - 0.70 * t)
            color = theme.blend(theme.PANEL_BG, theme.ACCENT, 0.012 + 0.075 * t * t)
            c.create_oval(cx - rx, cy - ry, cx + rx, cy + ry, fill=color, outline="")

    def _draw_hero_dots(self):
        """A dot matrix that fades toward the edges - the texture under it all."""
        c = self.canvas
        x0, y0, x1, y1 = self._fx_box
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        half_w, half_h = max(1.0, (x1 - x0) / 2), max(1.0, (y1 - y0) / 2)
        start_x = x0 + ((x1 - x0) % DOT_STEP) / 2
        start_y = y0 + ((y1 - y0) % DOT_STEP) / 2
        y = start_y
        while y <= y1:
            x = start_x
            while x <= x1:
                dx, dy = (x - cx) / half_w, (y - cy) / half_h
                fade = max(0.0, 1.0 - math.sqrt(dx * dx * 0.7 + dy * dy * 0.3))
                if fade > 0.05:
                    color = theme.blend(theme.PANEL_BG, theme.ACCENT_LIGHT, 0.20 * fade)
                    c.create_rectangle(x, y, x + 1, y + 1, fill=color, outline="")
                x += DOT_STEP
            y += DOT_STEP

    def _draw_hero_buttons(self, right):
        c = self.canvas
        play_width, stop_width, gap, height = 168, 92, 10, 50
        play_x1 = right - 24
        play_x0 = play_x1 - play_width
        stop_x1 = play_x0 - gap
        stop_x0 = stop_x1 - stop_width
        y0 = HERO_TOP + (HERO_BOTTOM - HERO_TOP - height) / 2
        y1 = y0 + height
        self._play_bbox = (play_x0, y0, play_x1, y1)
        self._stop_bbox = (stop_x0, y0, stop_x1, y1)

        if not self.button_enabled:
            fill, text_color = theme.DISABLED_FILL, theme.MUTED
        elif self._hover:
            fill, text_color = theme.ACCENT_HOVER, theme.BG_TOP
        else:
            fill, text_color = theme.ACCENT, theme.BG_TOP

        if self.button_enabled:
            # Halo rings, faint far out and brighter close in. _animate makes
            # them breathe; the base mix is kept so it knows how bright each is.
            for spread, mix in ((10, 0.10), (7, 0.20), (4, 0.42)):
                ring = theme.rounded_rect(
                    c, play_x0 - spread, y0 - spread, play_x1 + spread, y1 + spread,
                    radius=11 + spread, fill="", outline=theme.PANEL_BG,
                )
                self._ring_ids.append((ring, mix))

        theme.rounded_rect(c, play_x0, y0, play_x1, y1, radius=11, fill=fill, outline="")
        c.create_text(
            (play_x0 + play_x1) / 2, (y0 + y1) / 2, text=self.button_text.upper(),
            font=theme.font(12, "bold"), fill=text_color,
        )

        if not self.app.game_running():
            stop_fill, stop_color, stop_line = theme.DISABLED_FILL, theme.MUTED, theme.PANEL_BORDER
        elif self._stop_hover:
            stop_fill, stop_color, stop_line = theme.STOP_RED_HOVER, theme.BG_TOP, ""
        else:
            stop_fill, stop_color, stop_line = theme.STOP_RED, theme.BG_TOP, ""

        theme.rounded_rect(
            c, stop_x0, y0, stop_x1, y1, radius=11, fill=stop_fill, outline=stop_line
        )
        c.create_text(
            (stop_x0 + stop_x1) / 2, (y0 + y1) / 2, text="STOP",
            font=theme.font(11, "bold"), fill=stop_color,
        )

    # --------------------------------------------------------------- effects

    def _spawn_particle(self, anywhere=False):
        rng = self._rng
        return {
            "u": rng.random(),                               # across, 0..1
            "v": rng.random() if anywhere else 1.0 + rng.random() * 0.2,  # down, 0..1
            "speed": rng.uniform(0.035, 0.11),               # hero-heights per second
            "drift": rng.uniform(-0.012, 0.012),             # sideways wander
            "size": rng.choice((1.0, 1.0, 1.5, 1.5, 2.0)),
            "glow": rng.uniform(0.12, 0.45),                 # peak brightness
            "twinkle": rng.uniform(1.2, 3.2),                # radians per second
            "phase": rng.uniform(0.0, math.tau),
        }

    def _make_particles(self):
        for _ in self._particles:
            self._particle_ids.append(
                self.canvas.create_oval(0, 0, 0, 0, fill=theme.PANEL_BG, outline="")
            )

    def _make_sheen(self):
        # Nested bands in small steps, so the light has a soft edge and a
        # brighter core instead of visible stripes.
        for mix in SHEEN_MIXES:
            band = self.canvas.create_polygon(
                0, 0, 0, 0, 0, 0, 0, 0,
                fill=theme.blend(theme.PANEL_BG, theme.ACCENT, mix), outline="",
                state="hidden",
            )
            self._sheen_ids.append(band)

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
        x0, y0, x1, y1 = self._fx_box
        if x1 <= x0:
            return
        t = self._clock

        for item, particle in zip(self._particle_ids, self._particles):
            x = x0 + (x1 - x0) * particle["u"]
            y = y0 + (y1 - y0) * particle["v"]
            # Fade in from the bottom edge and out toward the top.
            edge = max(0.0, min(1.0, particle["v"] * 4, (1.0 - particle["v"]) * 4))
            shine = 0.55 + 0.45 * math.sin(t * particle["twinkle"] + particle["phase"])
            r = particle["size"]
            c.coords(item, x - r, y - r, x + r, y + r)
            c.itemconfigure(
                item, fill=theme.blend(theme.PANEL_BG, theme.ACCENT, particle["glow"] * shine * edge)
            )

        # The sheen crosses once per period, then waits off stage.
        progress = (t % SHEEN_PERIOD_S) / SHEEN_SWEEP_S
        if progress <= 1.0 and self._sheen_ids:
            slant = (y1 - y0) * 0.55
            travel = (x1 - x0) + slant + 120
            centre = x0 - 60 + travel * _ease_in_out(progress)
            for item, half in zip(self._sheen_ids, SHEEN_HALF_WIDTHS):
                points = [
                    centre - half + slant, y0, centre + half + slant, y0,
                    centre + half, y1, centre - half, y1,
                ]
                # No clipping on a canvas: pin each corner inside the hero.
                for i in range(0, 8, 2):
                    points[i] = max(x0, min(x1, points[i]))
                c.coords(item, *points)
                c.itemconfigure(item, state="normal")
        else:
            for item in self._sheen_ids:
                c.itemconfigure(item, state="hidden")

        # The launch button breathes; hovering makes it glow harder.
        pulse = 0.5 + 0.5 * math.sin(t * math.tau / PULSE_PERIOD_S)
        strength = (1.25 if self._hover else 0.55) + (0.45 * pulse)
        for ring, mix in self._ring_ids:
            c.itemconfigure(
                ring, outline=theme.blend(theme.PANEL_BG, theme.ACCENT, min(1.0, mix * strength))
            )

    def _draw_chips(self, width):
        c = self.canvas
        gap = 12
        chip_width = (width - 1 - gap * 3) / 4
        loader = (self.summary.get("loader") or "vanilla").title()
        chips = [
            ("INSTANCE", self.summary.get("instance", "-"), "Selected profile", None),
            ("VERSION", self.summary.get("version", "-"), loader, None),
            ("MODS", f"{self.summary.get('mods', 0)} installed", "Enabled jars", None),
            ("STATUS", self.state_label, self.status_message, self._state_color()),
        ]

        for index, (title, main, sub, dot) in enumerate(chips):
            x0 = index * (chip_width + gap)
            x1 = x0 + chip_width
            theme.rounded_rect(
                c, x0, CHIP_TOP, x1, CHIP_TOP + CHIP_HEIGHT, radius=12,
                fill=theme.PANEL_BG, outline=theme.PANEL_BORDER,
            )
            c.create_rectangle(
                x0 + 16, CHIP_TOP + 15, x0 + 30, CHIP_TOP + 17, fill=theme.ACCENT, outline=""
            )
            c.create_text(
                x0 + 38, CHIP_TOP + 16, text=title, anchor="w",
                font=theme.font(8, "bold"), fill=theme.MUTED,
            )

            value_x = x0 + 16
            if dot:
                c.create_oval(
                    value_x, CHIP_TOP + 36, value_x + 7, CHIP_TOP + 43, fill=dot, outline=""
                )
                value_x += 13

            c.create_text(
                value_x, CHIP_TOP + 40,
                text=_ellipsis(str(main), x1 - value_x - 14, 11),
                anchor="w", font=theme.font(11, "bold"), fill=theme.TEXT,
            )
            c.create_text(
                x0 + 16, CHIP_TOP + 57,
                text=_ellipsis(str(sub), chip_width - 32, 8, weight="normal"),
                anchor="w", font=theme.font(8), fill=theme.MUTED,
            )

    def _state_color(self):
        if self.error:
            return theme.ERROR
        return STATE_COLORS.get(self.state_label, theme.ACCENT_LIGHT)


def _ellipsis(value, max_width, size, weight="bold"):
    """Trim canvas text to fit; unlike a label, a canvas item will not clip."""
    measure = theme.font(size, weight).measure
    if measure(value) <= max_width:
        return value
    while value and measure(value + "...") > max_width:
        value = value[:-1]
    return value + "..."


def _ease_in_out(t):
    """Smoothstep: slow at both ends, so the sheen glides rather than snaps."""
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)
