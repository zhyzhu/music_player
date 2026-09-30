"""Generate the white-on-transparent status bar icon.

The system draws notification icons as a mask, tinting only their alpha, so this
is a plain white glyph with no background. Sizes follow the standard status bar
icon dimension (24dp at mdpi).
"""
import os
from PIL import Image, ImageDraw

BASE = r"C:\Users\zhuzh\Documents\music_player\app\res"
SIZES = {
    "drawable-ldpi": 18,
    "drawable-mdpi": 24,
    "drawable-hdpi": 36,
    "drawable-xhdpi": 48,
}
SS = 4  # supersample factor

WHITE = (255, 255, 255, 255)


def render(size):
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Geometry on a 24-unit master grid, inset so nothing is clipped.
    u = big / 24.0
    left, top = 0.5 * u, 0.5 * u

    stem_x = left + 14.5 * u
    stem_w = 1.6 * u
    d.rectangle([stem_x, top + 4.0 * u, stem_x + stem_w, top + 16.0 * u], fill=WHITE)

    # Flag
    d.polygon([
        (stem_x + stem_w, top + 4.0 * u),
        (stem_x + stem_w + 4.0 * u, top + 6.5 * u),
        (stem_x + stem_w + 3.4 * u, top + 11.5 * u),
        (stem_x + stem_w, top + 9.5 * u),
    ], fill=WHITE)

    # Note head
    cx, cy = left + 11.0 * u, top + 17.0 * u
    rx, ry = 4.6 * u, 3.4 * u
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=WHITE)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in SIZES.items():
        out_dir = os.path.join(BASE, folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_stat_music.png")
        render(size).save(path, "PNG")
        print("wrote %s (%dx%d)" % (path, size, size))


if __name__ == "__main__":
    main()
