"""Full-window loading screen shown while Blueprint Client starts the game.

The screen is a canvas overlay that sits on top of the launcher window, so it
does not need a second toplevel and never steals focus from the app.
"""

import tkinter as tk
from tkinter import font as tkfont

BG = "#050d1a"
GRID = "#0e2137"
BLUE_BRIGHT = "#3d8bff"
BLUE_LIGHT = "#8ecbff"
BLUE_DEEP = "#1b3a63"
TRACK = "#12263d"
MUTED = "#6f90ad"
TEXT = "#e8f4ff"
ERROR = "#ff8080"

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
        self._fonts = {}

        self.canvas = tk.Canvas(parent, bg=BG, highlightthickness=0, bd=0)
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
        self.redraw()

        still_moving = self.current != self.target
        if still_moving or (not self.error and self.current < 100):
            self._job = self.canvas.after(FRAME_MS, self._tick)

    # -------------------------------------------------------------- drawing

    def redraw(self):
        canvas = self.canvas
        canvas.delete("all")

        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1 or height <= 1:
            return

        self._draw_grid(width, height)
        self._draw_wordmark(width, height)
        self._draw_progress(width, height)

    def _draw_grid(self, width, height):
        """Faint blueprint grid, brightest in the middle of the screen."""
        step = 46
        for x in range(0, width, step):
            self.canvas.create_line(x, 0, x, height, fill=GRID)
        for y in range(0, height, step):
            self.canvas.create_line(0, y, width, y, fill=GRID)

        # A soft blue horizon band behind the wordmark.
        band = int(height * 0.21)
        centre = int(height * 0.42)
        self.canvas.create_rectangle(
            0, centre - band, width, centre + band, fill="#071427", outline=""
        )

    def _bold_font(self, size):
        """Fonts are cached: redraw runs ~30x a second while loading."""
        if size not in self._fonts:
            self._fonts[size] = tkfont.Font(family="Segoe UI", size=size, weight="bold")
        return self._fonts[size]

    def _draw_wordmark(self, width, height):
        blueprint = "BLUEPRINT"
        client = " CLIENT"

        # Shrink the wordmark until it clears the window edges.
        available = width * 0.82
        size = max(18, min(56, int(width / 15)))
        while size > 18:
            bold = self._bold_font(size)
            if bold.measure(blueprint + client) <= available:
                break
            size -= 2
        bold = self._bold_font(size)

        total = bold.measure(blueprint) + bold.measure(client)
        x = (width - total) / 2
        y = int(height * 0.42)

        self.canvas.create_text(
            x, y, text=blueprint, anchor="w", font=bold, fill=BLUE_BRIGHT
        )
        self.canvas.create_text(
            x + bold.measure(blueprint),
            y,
            text=client,
            anchor="w",
            font=bold,
            fill=BLUE_LIGHT,
        )

        rule_half = min(total / 2, width * 0.32)
        rule_y = y + size + 14
        self.canvas.create_line(
            width / 2 - rule_half,
            rule_y,
            width / 2 + rule_half,
            rule_y,
            fill=BLUE_DEEP,
            width=2,
        )

        self.canvas.create_text(
            width / 2,
            rule_y + 22,
            text="MINECRAFT 1.21.11",
            font=("Segoe UI", 10, "bold"),
            fill=MUTED,
        )

    def _draw_progress(self, width, height):
        bar_width = min(520, width * 0.62)
        x0 = (width - bar_width) / 2
        x1 = x0 + bar_width
        y = int(height * 0.74)

        percent = int(round(self.current))
        self.canvas.create_text(
            x0,
            y - 20,
            text=self.status,
            anchor="w",
            font=("Segoe UI", 10),
            fill=ERROR if self.error else TEXT,
        )
        if not self.error:
            self.canvas.create_text(
                x1,
                y - 20,
                text=f"{percent}%",
                anchor="e",
                font=("Segoe UI", 10, "bold"),
                fill=BLUE_LIGHT,
            )

        self.canvas.create_line(
            x0, y, x1, y, fill=TRACK, width=8, capstyle=tk.ROUND
        )

        filled = bar_width * (self.current / 100.0)
        if filled > 1:
            self.canvas.create_line(
                x0,
                y,
                x0 + filled,
                y,
                fill=ERROR if self.error else BLUE_BRIGHT,
                width=8,
                capstyle=tk.ROUND,
            )

        # Shimmer travels along the unfilled part while work is in progress.
        if not self.error and self.current < 100:
            span = bar_width * 0.18
            head = x0 + (bar_width + span) * self.shimmer - span
            start = max(x0, head)
            end = min(x1, head + span)
            if end - start > 2:
                self.canvas.create_line(
                    start, y, end, y, fill=BLUE_DEEP, width=8, capstyle=tk.ROUND
                )

        footer = "Returning to the launcher..." if self.error else "Getting things ready"
        self.canvas.create_text(
            width / 2,
            y + 34,
            text=footer,
            font=("Segoe UI", 9),
            fill=MUTED,
        )
