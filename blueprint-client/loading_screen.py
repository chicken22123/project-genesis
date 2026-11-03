"""Full-window loading screen shown while the launcher starts the game.

The screen is a canvas overlay that sits on top of the launcher window, so it
does not need a second toplevel and never steals focus from the app.

It redraws from scratch each frame, which is fine for the ~150 items it holds:
a slowly drifting grid, a glow behind the wordmark, rising particles and the
progress bar. Colours and fonts come from the shared theme.
"""

import math
import random
import time
import tkinter as tk

import theme

GRID_STEP = 46
GRID_DRIFT = 9.0       # pixels per second the grid slides
PARTICLE_COUNT = 40
FRAME_MS = 33


class LoadingScreen:
    """Animated 'Blueprint Client' splash used during the launch sequence."""

    def __init__(self, parent):
        self.parent = parent
        self.visible = False
        self.error = False
        self.status = "Preparing your session..."
        self.target = 0.0
        self.current = 0.0
        self.shimmer = 0.0
        self._job = None
        self._started = time.monotonic()
        self._rng = random.Random()
        self._particles = [self._spawn(anywhere=True) for _ in range(PARTICLE_COUNT)]
        self._last = time.monotonic()

        self.canvas = tk.Canvas(parent, bg=theme.BG_TOP, highlightthickness=0, bd=0)
        self.canvas.bind("<Configure>", lambda _event: self.redraw())

    # ------------------------------------------------------------------ api

    def show(self, status="Preparing your session..."):
        self.error = False
        self.status = status
        self.target = 0.0
        self.current = 0.0
        self.shimmer = 0.0

        if not self.visible:
            self.visible = True
            self.canvas.place(relx=0, rely=0, relwidth=1, relheight=1)
            # Canvas.lift() raises a canvas item; this raises the widget itself.
            tk.Misc.lift(self.canvas)

        self.redraw()
        self._start_animation()

    def set_progress(self, percent, status=None):
        self.error = False
        self.target = max(0.0, min(100.0, float(percent)))
        if status:
            self.status = status
        if self.visible:
            self._start_animation()

    def set_error(self, message):
        self.error = True
        self.status = message
        if self.visible:
            self.redraw()

    def hide(self):
        if self._job is not None:
            self.canvas.after_cancel(self._job)
            self._job = None
        if self.visible:
            self.visible = False
            self.canvas.place_forget()

    def hide_after(self, delay_ms):
        self.canvas.after(delay_ms, self.hide)

    # ------------------------------------------------------------ animation

    def _start_animation(self):
        if self._job is None:
            self._job = self.canvas.after(FRAME_MS, self._tick)

    def _tick(self):
        self._job = None
        if not self.visible:
            return

        # Ease the drawn value toward the target so jumps look smooth.
        gap = self.target - self.current
        if abs(gap) < 0.25:
            self.current = self.target
        else:
            self.current += gap * 0.12

        self.shimmer = (self.shimmer + 0.012) % 1.0
        now = time.monotonic()
        dt = min(0.1, now - self._last)
        self._last = now
        for index, particle in enumerate(self._particles):
            particle["y"] -= particle["speed"] * dt
            if particle["y"] < -0.02:
                self._particles[index] = self._spawn()
        self.redraw()

        still_moving = self.current != self.target
        if still_moving or (not self.error and self.current < 100):
            self._job = self.canvas.after(FRAME_MS, self._tick)

    # -------------------------------------------------------------- drawing

    def _spawn(self, anywhere=False):
        rng = self._rng
        return {
            "x": rng.random(),
            "y": rng.random() if anywhere else 1.0 + rng.random() * 0.1,
            "speed": rng.uniform(0.02, 0.07),     # screen-heights per second
            "size": rng.choice((1.0, 1.5, 1.5, 2.0, 2.5, 3.0)),
            "glow": rng.uniform(0.2, 0.7),
            "twinkle": rng.uniform(1.0, 3.0),
            "phase": rng.uniform(0.0, math.tau),
        }

    def redraw(self):
        canvas = self.canvas
        canvas.delete("all")

        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1 or height <= 1:
            return

        t = time.monotonic() - self._started
        self._draw_grid(width, height, t)
        self._draw_glow(width, height)
        self._draw_particles(width, height, t)
        self._draw_wordmark(width, height)
        self._draw_progress(width, height)

    def _draw_grid(self, width, height, t):
        """A faint grid sliding diagonally, brightest at the centre."""
        c = self.canvas
        offset = (t * GRID_DRIFT) % GRID_STEP
        cx, cy = width / 2, height * 0.42

        x = -GRID_STEP + offset
        while x < width:
            fade = max(0.0, 1.0 - abs(x - cx) / (width * 0.55))
            c.create_line(x, 0, x, height,
                          fill=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, 0.07 * fade))
            x += GRID_STEP
        y = -GRID_STEP + offset
        while y < height:
            fade = max(0.0, 1.0 - abs(y - cy) / (height * 0.6))
            c.create_line(0, y, width, y,
                          fill=theme.blend(theme.BG_TOP, theme.ACCENT_LIGHT, 0.07 * fade))
            y += GRID_STEP

    def _draw_glow(self, width, height):
        """A wide soft light behind the wordmark."""
        cx, cy = width / 2, height * 0.42
        rx_max, ry_max = width * 0.46, height * 0.30
        steps = 22
        for index in range(steps):
            k = index / (steps - 1)
            rx = rx_max * (1.0 - 0.85 * k)
            ry = ry_max * (1.0 - 0.80 * k)
            # Starting from almost nothing hides the outermost ellipse's edge.
            self.canvas.create_oval(
                cx - rx, cy - ry, cx + rx, cy + ry,
                fill=theme.blend(theme.BG_TOP, theme.ACCENT, 0.003 + 0.077 * k * k),
                outline="",
            )

    def _draw_particles(self, width, height, t):
        for p in self._particles:
            x, y = p["x"] * width, p["y"] * height
            edge = max(0.0, min(1.0, p["y"] * 5, (1.0 - p["y"]) * 5))
            shine = 0.55 + 0.45 * math.sin(t * p["twinkle"] + p["phase"])
            r = p["size"]
            self.canvas.create_oval(
                x - r, y - r, x + r, y + r, outline="",
                fill=theme.blend(theme.BG_TOP, theme.ACCENT, p["glow"] * shine * edge),
            )

    def _draw_wordmark(self, width, height):
        first, second = theme.BRAND_WORDS
        second = " " + second

        # Shrink the wordmark until it clears the window edges.
        available = width * 0.82
        size = max(18, min(56, int(width / 15)))
        while size > 18:
            if theme.font(size, "bold").measure(first + second) <= available:
                break
            size -= 2
        bold = theme.font(size, "bold")

        total = bold.measure(first) + bold.measure(second)
        x = (width - total) / 2
        y = int(height * 0.42)

        self.canvas.create_text(x, y, text=first, anchor="w", font=bold, fill=theme.ACCENT_HOVER)
        self.canvas.create_text(
            x + bold.measure(first), y, text=second, anchor="w", font=bold, fill=theme.MUTED,
        )

        # A rule that is brightest in the middle and fades to nothing.
        rule_half = min(total / 2, width * 0.32)
        rule_y = y + size + 14
        for fraction, mix in ((1.0, 0.18), (0.6, 0.38), (0.25, 0.70)):
            half = rule_half * fraction
            self.canvas.create_line(
                width / 2 - half, rule_y, width / 2 + half, rule_y,
                fill=theme.blend(theme.BG_TOP, theme.ACCENT, mix), width=2,
            )

        self.canvas.create_text(
            width / 2, rule_y + 22, text="MINECRAFT 1.21.11",
            font=theme.font(10, "bold"), fill=theme.MUTED,
        )

    def _draw_progress(self, width, height):
        c = self.canvas
        bar_width = min(520, width * 0.62)
        x0 = (width - bar_width) / 2
        x1 = x0 + bar_width
        y = int(height * 0.74)

        percent = int(round(self.current))
        c.create_text(
            x0, y - 20, text=self.status, anchor="w", font=theme.font(10),
            fill=theme.ERROR if self.error else theme.TEXT,
        )
        if not self.error:
            c.create_text(
                x1, y - 20, text=f"{percent}%", anchor="e",
                font=theme.font(10, "bold"), fill=theme.ACCENT_HOVER,
            )

        c.create_line(x0, y, x1, y, fill=theme.TRACK, width=6, capstyle=tk.ROUND)

        filled = bar_width * (self.current / 100.0)
        if filled > 1:
            fill = theme.ERROR if self.error else theme.ACCENT_LIGHT
            # A wider, dimmer stroke under the fill reads as glow.
            c.create_line(x0, y, x0 + filled, y, width=12, capstyle=tk.ROUND,
                          fill=theme.blend(theme.BG_TOP, fill, 0.18))
            c.create_line(x0, y, x0 + filled, y, fill=fill, width=6, capstyle=tk.ROUND)

            # A bright glint runs along the filled part while work is going on.
            if not self.error and self.current < 100:
                span = max(18.0, min(80.0, filled * 0.3))
                head = x0 - span + (filled + span) * self.shimmer
                start, end = max(x0, head), min(x0 + filled, head + span)
                if end - start > 2:
                    c.create_line(start, y, end, y, fill=theme.ACCENT_HOVER,
                                  width=6, capstyle=tk.ROUND)

        footer = "Returning to the launcher..." if self.error else "Getting things ready"
        c.create_text(width / 2, y + 34, text=footer, font=theme.font(9), fill=theme.MUTED)
