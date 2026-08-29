"""The Play page: the hero panel, the launch buttons and the live console.

The top of the page is drawn on a canvas - a blueprint grid, a soft glow behind
the title and the launch buttons - so it matches the client's own title screen
in game. Everything below it is real Tk widgets.
"""

import os
import subprocess
import sys
import tkinter as tk

import theme

MAX_CONSOLE_LINES = 2000

HERO_TOP = 0
HERO_BOTTOM = 152
CHIP_TOP = 164
CHIP_HEIGHT = 70
CANVAS_HEIGHT = CHIP_TOP + CHIP_HEIGHT

GRID_STEP = 26

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
        width = canvas.winfo_width()
        if width <= 1:
            return
        self._draw_hero(width)
        self._draw_chips(width)

    def _draw_hero(self, width):
        c = self.canvas
        left, right = 0, width - 1
        theme.rounded_rect(
            c, left, HERO_TOP, right, HERO_BOTTOM, radius=14,
            fill=theme.PANEL_BG, outline=theme.PANEL_BORDER,
        )
        self._draw_hero_glow(left, right)
        self._draw_hero_grid(left, right)

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
        """A soft band of light behind the title, brightest in the middle."""
        c = self.canvas
        top, bottom = HERO_TOP + 12, HERO_BOTTOM - 12
        steps = 14
        for index in range(steps):
            y0 = top + (bottom - top) * index / steps
            y1 = top + (bottom - top) * (index + 1) / steps + 1
            distance = abs((index + 0.5) / steps - 0.42) * 2
            color = theme.blend(theme.PANEL_BG, theme.ACCENT, 0.16 * max(0.0, 1.0 - distance))
            c.create_rectangle(left + 2, y0, right - 1, y1, fill=color, outline="")

    def _draw_hero_grid(self, left, right):
        """The blueprint grid, matching the client's title screen in game."""
        c = self.canvas
        line = theme.blend(theme.PANEL_BG, theme.ACCENT_LIGHT, 0.13)
        for x in range(int(left) + GRID_STEP, int(right) - 2, GRID_STEP):
            c.create_line(x, HERO_TOP + 2, x, HERO_BOTTOM - 2, fill=line)
        for y in range(HERO_TOP + GRID_STEP, HERO_BOTTOM - 2, GRID_STEP):
            c.create_line(left + 2, y, right - 1, y, fill=line)

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
            # Two rings, faint to bright, so the primary action glows a little.
            for spread, mix in ((7, 0.25), (4, 0.55)):
                theme.rounded_rect(
                    c, play_x0 - spread, y0 - spread, play_x1 + spread, y1 + spread,
                    radius=14 + spread, fill="",
                    outline=theme.blend(theme.PANEL_BG, theme.ACCENT, mix),
                )

        theme.rounded_rect(c, play_x0, y0, play_x1, y1, radius=11, fill=fill, outline="")
        c.create_text(
            (play_x0 + play_x1) / 2, (y0 + y1) / 2, text=self.button_text.upper(),
            font=theme.font(12, "bold"), fill=text_color,
        )

        if not self.app.game_running():
            stop_fill, stop_color = theme.DISABLED_FILL, theme.MUTED
        elif self._stop_hover:
            stop_fill, stop_color = theme.STOP_RED_HOVER, theme.BG_TOP
        else:
            stop_fill, stop_color = theme.STOP_RED, theme.BG_TOP

        theme.rounded_rect(c, stop_x0, y0, stop_x1, y1, radius=11, fill=stop_fill, outline="")
        c.create_text(
            (stop_x0 + stop_x1) / 2, (y0 + y1) / 2, text="STOP",
            font=theme.font(11, "bold"), fill=stop_color,
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
