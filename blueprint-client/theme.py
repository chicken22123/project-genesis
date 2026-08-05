"""Colours, fonts and styled Tk widget factories shared by every page.

The Play page still paints its hero panel on a canvas, but lists, forms and the
console are real Tk widgets so the pages stay short. Windows draws those widgets
grey by default, so every widget the launcher creates goes through a factory
here and comes back looking like the rest of the app.
"""

import tkinter as tk
from tkinter import font as tkfont
from tkinter import ttk

BG_TOP = "#050d1a"
BG_BOTTOM = "#0d1f2b"
PANEL_BG = "#0d1f2b"
PANEL_ALT = "#0a1929"
PANEL_BORDER = "#1b3a63"
ACCENT = "#3d8bff"
ACCENT_LIGHT = "#8ecbff"
ACCENT_HOVER = "#5a9dff"
TEXT = "#e8f4ff"
MUTED = "#6f90ad"
ERROR = "#ff8080"
OK = "#7ce0a8"
TRACK = "#12263d"
DISABLED_FILL = "#1b3a63"
STOP_RED = "#e05252"
STOP_RED_HOVER = "#ef6a6a"

FAMILY = "Segoe UI"
MONO_FAMILY = "Consolas"

_FONTS = {}


def font(size=10, weight="normal", family=FAMILY):
    """Cached font. Widgets and the canvas both redraw often enough to need it."""
    key = (family, size, weight)
    if key not in _FONTS:
        _FONTS[key] = tkfont.Font(family=family, size=size, weight=weight)
    return _FONTS[key]


def apply_ttk_theme(root):
    """Make the two ttk widgets we use (scrollbar, combobox) honour our colours."""
    style = ttk.Style(root)
    try:
        # Only 'clam' lets a theme repaint scrollbars and combobox fields.
        style.theme_use("clam")
    except tk.TclError:
        pass

    style.configure(
        "Blueprint.Vertical.TScrollbar",
        background=PANEL_BORDER,
        troughcolor=PANEL_ALT,
        bordercolor=PANEL_ALT,
        darkcolor=PANEL_BORDER,
        lightcolor=PANEL_BORDER,
        arrowcolor=MUTED,
        relief="flat",
        gripcount=0,
    )
    style.map(
        "Blueprint.Vertical.TScrollbar",
        background=[("active", ACCENT), ("pressed", ACCENT)],
        arrowcolor=[("active", TEXT)],
    )

    style.configure(
        "Blueprint.TCombobox",
        fieldbackground=TRACK,
        background=TRACK,
        foreground=TEXT,
        arrowcolor=ACCENT_LIGHT,
        bordercolor=PANEL_BORDER,
        darkcolor=PANEL_BORDER,
        lightcolor=PANEL_BORDER,
        selectbackground=TRACK,
        selectforeground=TEXT,
        padding=4,
    )
    style.map(
        "Blueprint.TCombobox",
        fieldbackground=[("readonly", TRACK), ("disabled", PANEL_ALT)],
        foreground=[("disabled", MUTED)],
        bordercolor=[("focus", ACCENT)],
    )

    # The dropdown list is a plain Tk listbox owned by Tk itself, so it can only
    # be styled through the option database.
    root.option_add("*TCombobox*Listbox.background", PANEL_ALT)
    root.option_add("*TCombobox*Listbox.foreground", TEXT)
    root.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
    root.option_add("*TCombobox*Listbox.selectForeground", BG_TOP)

    style.configure(
        "Blueprint.Treeview",
        background=PANEL_ALT,
        fieldbackground=PANEL_ALT,
        foreground=TEXT,
        bordercolor=PANEL_ALT,
        borderwidth=0,
        relief="flat",
        rowheight=27,
        font=font(10),
    )
    style.map(
        "Blueprint.Treeview",
        background=[("selected", ACCENT)],
        foreground=[("selected", BG_TOP)],
    )
    style.layout(
        "Blueprint.Treeview",
        [("Blueprint.Treeview.treearea", {"sticky": "nswe"})],
    )
    style.configure(
        "Blueprint.Treeview.Heading",
        background=PANEL_BG,
        foreground=MUTED,
        bordercolor=PANEL_BORDER,
        relief="flat",
        font=font(8, "bold"),
        padding=(10, 7),
    )
    style.map(
        "Blueprint.Treeview.Heading",
        background=[("active", PANEL_BG)],
        foreground=[("active", ACCENT_LIGHT)],
    )


def rows(parent, columns, height=10):
    """A borderless, header-having table - the themed replacement for a Listbox.

    ``columns`` is ``[(id, caption, width, anchor), ...]``. Returns the
    Treeview; rows are added with ``tree.insert("", "end", values=..., tags=...)``.
    Two zebra tags, ``odd`` and ``even``, are pre-configured.
    """
    widget = ttk.Treeview(
        parent,
        columns=[col[0] for col in columns],
        show="headings",
        height=height,
        style="Blueprint.Treeview",
        selectmode="browse",
    )
    for column_id, caption, width, anchor in columns:
        widget.heading(column_id, text=caption.upper(), anchor=anchor)
        # Only the last flexible column stretches, so fixed columns keep their
        # width and nothing is pushed off the right edge.
        widget.column(column_id, width=width, minwidth=width, anchor=anchor, stretch=False)
    flexible = [col[0] for col in columns if col[3] == "w"]
    if flexible:
        widget.column(flexible[-1], stretch=True)

    widget.tag_configure("odd", background=PANEL_ALT)
    widget.tag_configure("even", background="#0c1e30")
    widget.tag_configure("muted", foreground=MUTED)
    widget.tag_configure("accent", foreground=ACCENT_LIGHT)
    widget.tag_configure("error", foreground=ERROR)
    return widget


def zebra_tag(index, *extra):
    return (("odd" if index % 2 else "even"),) + extra


# ------------------------------------------------------------------ drawing


def blend(color_a, color_b, amount):
    """Mix two #rrggbb colours. amount=0 returns a, amount=1 returns b.

    Tk canvases have no alpha channel, so soft glows and faint grid lines are
    made by mixing against the colour they sit on.
    """
    amount = max(0.0, min(1.0, amount))
    a = [int(color_a[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(color_b[i:i + 2], 16) for i in (1, 3, 5)]
    mixed = [round(a[i] + (b[i] - a[i]) * amount) for i in range(3)]
    return "#{:02x}{:02x}{:02x}".format(*mixed)


def rounded_rect(canvas, x0, y0, x1, y1, radius=12, **kwargs):
    """A rounded rectangle on a Canvas. ``radius`` is clamped to half the shape."""
    radius = max(0, min(radius, (x1 - x0) / 2, (y1 - y0) / 2))
    points = [
        x0 + radius, y0,
        x1 - radius, y0,
        x1, y0,
        x1, y0 + radius,
        x1, y1 - radius,
        x1, y1,
        x1 - radius, y1,
        x0 + radius, y1,
        x0, y1,
        x0, y1 - radius,
        x0, y0 + radius,
        x0, y0,
    ]
    return canvas.create_polygon(points, smooth=True, splinesteps=24, **kwargs)


# ------------------------------------------------------------------- containers


def frame(parent, bg=BG_TOP, **kwargs):
    return tk.Frame(parent, bg=bg, bd=0, highlightthickness=0, **kwargs)


def panel(parent, bg=PANEL_BG, **kwargs):
    """A bordered card, the same shape the canvas draws on the Play page."""
    return tk.Frame(
        parent,
        bg=bg,
        bd=0,
        highlightthickness=1,
        highlightbackground=PANEL_BORDER,
        highlightcolor=PANEL_BORDER,
        **kwargs,
    )


# ---------------------------------------------------------------------- labels


def label(parent, text="", size=10, weight="normal", fg=TEXT, bg=PANEL_BG, **kwargs):
    return tk.Label(parent, text=text, font=font(size, weight), fg=fg, bg=bg, **kwargs)


def eyebrow(parent, text="", bg=PANEL_BG, **kwargs):
    """The small uppercase caption used above every value in the app."""
    return label(parent, text.upper(), size=8, weight="bold", fg=MUTED, bg=bg, **kwargs)


def heading(parent, text="", bg=BG_TOP, **kwargs):
    return label(parent, text, size=14, weight="bold", fg=TEXT, bg=bg, **kwargs)


# --------------------------------------------------------------------- buttons

_BUTTON_KINDS = {
    "primary": (ACCENT, ACCENT_HOVER, BG_TOP),
    "ghost": (TRACK, PANEL_BORDER, TEXT),
    "danger": (STOP_RED, STOP_RED_HOVER, BG_TOP),
}


def button(parent, text, command=None, kind="ghost", **kwargs):
    fill, hover_fill, fg = _BUTTON_KINDS[kind]
    widget = tk.Button(
        parent,
        text=text,
        command=command,
        font=font(9, "bold"),
        bg=fill,
        fg=fg,
        activebackground=hover_fill,
        activeforeground=fg,
        disabledforeground=MUTED,
        relief="flat",
        bd=0,
        highlightthickness=0,
        padx=14,
        pady=6,
        cursor="hand2",
        **kwargs,
    )

    def enter(_event):
        if str(widget["state"]) != "disabled":
            widget.configure(bg=hover_fill)

    def leave(_event):
        widget.configure(bg=DISABLED_FILL if str(widget["state"]) == "disabled" else fill)

    widget.bind("<Enter>", enter)
    widget.bind("<Leave>", leave)
    widget.base_fill = fill
    return widget


def set_button_enabled(widget, enabled):
    """tk.Button keeps its own colour when disabled, so set both together."""
    widget.configure(
        state="normal" if enabled else "disabled",
        bg=widget.base_fill if enabled else DISABLED_FILL,
        cursor="hand2" if enabled else "",
    )


NAV_ICONS = {
    "play": "▶",       # ▶
    "instances": "▦",  # ▦
    "mods": "◈",       # ◈
    "settings": "⚙",   # ⚙
}


class NavButton(tk.Frame):
    """A sidebar row: an accent bar that appears when selected, an icon, a label.

    A plain ``tk.Button`` can't hold two differently-coloured pieces of text,
    so the row is a small canvas instead - cheap to redraw and it gives the
    left accent bar real companies' sidebars use for the active item.
    """

    def __init__(self, parent, text, icon, command):
        super().__init__(parent, bg=PANEL_BG, bd=0, highlightthickness=0, height=40)
        self.pack_propagate(False)
        self.command = command
        self.selected = False
        self.hover = False

        self.canvas = tk.Canvas(self, bg=PANEL_BG, highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True)
        self._icon = icon
        self._text = text

        for widget in (self, self.canvas):
            widget.configure(cursor="hand2")
            widget.bind("<Enter>", self._on_enter)
            widget.bind("<Leave>", self._on_leave)
            widget.bind("<Button-1>", lambda _e: self.command())

        self.canvas.bind("<Configure>", lambda _e: self.redraw())
        self.redraw()

    def _on_enter(self, _event):
        self.hover = True
        self.redraw()

    def _on_leave(self, _event):
        self.hover = False
        self.redraw()

    def set_selected(self, selected):
        self.selected = selected
        self.redraw()

    def redraw(self):
        c = self.canvas
        c.delete("all")
        width = c.winfo_width()
        height = c.winfo_height()
        if width <= 1 or height <= 1:
            return

        bg = TRACK if self.selected else (PANEL_ALT if self.hover else PANEL_BG)
        c.configure(bg=bg)
        self.configure(bg=bg)

        if self.selected:
            c.create_rectangle(0, 6, 3, height - 6, fill=ACCENT, outline="")

        fg = ACCENT_LIGHT if self.selected else (TEXT if self.hover else MUTED)
        c.create_text(
            22, height / 2, text=self._icon, anchor="w", font=font(12), fill=fg
        )
        c.create_text(
            46, height / 2, text=self._text, anchor="w", font=font(10, "bold"), fill=fg
        )


def nav_button(parent, text, command, icon=""):
    return NavButton(parent, text, icon, command)


def set_nav_selected(widget, selected):
    widget.set_selected(selected)


def logo_badge(parent, letter="B", size=40):
    """The little rounded-square brand mark shown above the nav list."""
    canvas = tk.Canvas(parent, width=size, height=size, bg=PANEL_BG, highlightthickness=0, bd=0)
    rounded_rect(canvas, 1, 1, size - 1, size - 1, radius=size * 0.28, fill=ACCENT, outline="")
    rounded_rect(
        canvas, 1, size * 0.52, size - 1, size - 1, radius=size * 0.28,
        fill=ACCENT_HOVER, outline="",
    )
    # Redraw the top corners square so the second rect doesn't round the bottom
    # over the top piece's straight edge.
    canvas.create_rectangle(1, size * 0.5, size - 1, size * 0.58, fill=ACCENT_HOVER, outline="")
    canvas.create_text(
        size / 2, size / 2 + 1, text=letter, font=font(int(size * 0.42), "bold"), fill=BG_TOP
    )
    return canvas


# ----------------------------------------------------------------- form inputs


def entry(parent, textvariable=None, width=24, **kwargs):
    return tk.Entry(
        parent,
        textvariable=textvariable,
        font=font(10),
        width=width,
        bg=TRACK,
        fg=TEXT,
        insertbackground=TEXT,
        disabledbackground=PANEL_ALT,
        disabledforeground=MUTED,
        readonlybackground=PANEL_ALT,
        relief="flat",
        bd=0,
        highlightthickness=1,
        highlightbackground=PANEL_BORDER,
        highlightcolor=ACCENT,
        **kwargs,
    )


def checkbutton(parent, text, variable, command=None, bg=PANEL_BG):
    return tk.Checkbutton(
        parent,
        text=text,
        variable=variable,
        command=command,
        font=font(10),
        bg=bg,
        fg=TEXT,
        activebackground=bg,
        activeforeground=TEXT,
        selectcolor=TRACK,
        relief="flat",
        bd=0,
        highlightthickness=0,
        anchor="w",
        padx=0,
        cursor="hand2",
    )


def scale(parent, variable, from_, to, resolution, command=None, bg=PANEL_BG):
    return tk.Scale(
        parent,
        variable=variable,
        from_=from_,
        to=to,
        resolution=resolution,
        orient="horizontal",
        command=command,
        bg=bg,
        fg=TEXT,
        troughcolor=TRACK,
        activebackground=ACCENT_HOVER,
        highlightthickness=0,
        bd=0,
        sliderrelief="flat",
        sliderlength=20,
        width=10,
        showvalue=False,
    )


def combobox(parent, textvariable, values=(), width=30):
    return ttk.Combobox(
        parent,
        textvariable=textvariable,
        values=list(values),
        width=width,
        state="readonly",
        style="Blueprint.TCombobox",
        font=font(10),
    )


# --------------------------------------------------------------- lists + text


def scrollbar(parent, command):
    return ttk.Scrollbar(
        parent, orient="vertical", command=command, style="Blueprint.Vertical.TScrollbar"
    )


def text(parent, **kwargs):
    widget = tk.Text(
        parent,
        font=font(9, family=MONO_FAMILY),
        bg=PANEL_ALT,
        fg=MUTED,
        insertbackground=TEXT,
        selectbackground=PANEL_BORDER,
        selectforeground=TEXT,
        relief="flat",
        bd=0,
        highlightthickness=1,
        highlightbackground=PANEL_BORDER,
        highlightcolor=PANEL_BORDER,
        wrap="none",
        padx=10,
        pady=8,
        state="disabled",
        **kwargs,
    )
    widget.tag_configure("info", foreground=TEXT)
    widget.tag_configure("muted", foreground=MUTED)
    widget.tag_configure("accent", foreground=ACCENT_LIGHT)
    widget.tag_configure("error", foreground=ERROR)
    widget.tag_configure("ok", foreground=OK)
    return widget


def separator(parent, bg=PANEL_BORDER):
    """A hairline. Pack it with fill="x" for a rule, fill="y" for a divider."""
    return tk.Frame(parent, bg=bg, width=1, height=1, bd=0, highlightthickness=0)
