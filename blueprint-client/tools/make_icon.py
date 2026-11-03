"""Draw the app icon - the logo crystal on a dark tile - as PNG and ICO.

    py tools/make_icon.py

Writes app_icon.png (256x256, for Linux, ChromeOS and Tk off Windows) and
app_icon.ico (16 to 256, for Windows and the PyInstaller exe) beside app.py.
It needs Pillow (``py -m pip install pillow``), but only to regenerate the
icon - the launcher itself does not use it.

The crystal comes from theme.gem_faces, so the icon always matches the logo
the launcher draws in its top bar.
"""

import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

import theme  # noqa: E402 - needs ROOT on the path first

SUPERSAMPLE = 4
SIZES = (16, 24, 32, 48, 64, 128, 256)
TILE = "#111114"
TILE_EDGE = "#2a2a30"


def render(size):
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    inset = big * 0.04
    draw.rounded_rectangle(
        (inset, inset, big - inset, big - inset), radius=big * 0.22,
        fill=TILE, outline=TILE_EDGE, width=max(1, int(big * 0.012)),
    )
    for points, colour in theme.gem_faces(big / 2, big / 2, big * 0.66):
        draw.polygon(points, fill=colour)

    # Drawing large and shrinking is what gives the edges their antialiasing.
    return image.resize((size, size), Image.LANCZOS)


def main():
    largest = render(256)
    png_path = os.path.join(ROOT, "app_icon.png")
    ico_path = os.path.join(ROOT, "app_icon.ico")
    largest.save(png_path)
    # Each size is rendered on its own, rather than one image shrunk six
    # times, so the 16px icon stays crisp instead of turning to mush.
    frames = [render(size) for size in SIZES]
    frames[-1].save(ico_path, sizes=[(s, s) for s in SIZES], append_images=frames[:-1])
    print(f"wrote {png_path}")
    print(f"wrote {ico_path}")


if __name__ == "__main__":
    main()
