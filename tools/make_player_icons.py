"""Generate the transport control icons.

The stock Android buttons look poor, so the player controls are ImageButtons with
flat vector-ish glyphs drawn here. Output is plain PNG, one per density, drawn at
4x and downsampled for smooth edges - the same approach as the launcher icons.
"""
import os
from PIL import Image, ImageDraw

BASE = r"C:\Users\zhuzh\Documents\music_player\app\res"
# 48dp buttons, so the glyph canvas matches the launcher icon sizing.
DENSITIES = {
    "drawable-ldpi": 36,
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
}
# Density scale factors for the small glyphs used on the now-playing bar.
BAR_DENSITIES = {
    "drawable-ldpi": 0.75,
    "drawable-mdpi": 1.0,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2.0,
}
SS = 4  # supersample factor

ACCENT = (61, 169, 252, 255)      # colour/accent
SECONDARY = (139, 151, 166, 255)  # colour/text_secondary


def canvas(size):
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img), (size * SS) / 48.0


def triangle(d, x0, y0, x1, y1, colour):
    """Right pointing triangle filling the given box."""
    d.polygon([(x0, y0), (x0, y1), (x1, (y0 + y1) / 2.0)], fill=colour)


def draw_play(d, u, colour):
    triangle(d, 16 * u, 10 * u, 37 * u, 24 * u, colour)


def draw_pause(d, u, colour):
    d.rectangle([15 * u, 10 * u, 21 * u, 38 * u], fill=colour)
    d.rectangle([27 * u, 10 * u, 33 * u, 38 * u], fill=colour)


def draw_prev(d, u, colour):
    d.rectangle([13 * u, 10 * u, 17 * u, 38 * u], fill=colour)
    triangle(d, 34 * u, 10 * u, 18 * u, 24 * u, colour)


def draw_next(d, u, colour):
    d.rectangle([31 * u, 10 * u, 35 * u, 38 * u], fill=colour)
    triangle(d, 14 * u, 10 * u, 30 * u, 24 * u, colour)


def draw_shuffle(d, u, colour):
    """Two crossing arrows with arrowheads."""
    w = 2.6 * u

    def segment(x0, y0, x1, y1):
        d.line([(x0, y0), (x1, y1)], fill=colour, width=max(1, int(w)))

    # Diagonals crossing in the middle.
    segment(8 * u, 15 * u, 40 * u, 33 * u)
    segment(8 * u, 33 * u, 40 * u, 15 * u)

    # Arrowheads at the right ends.
    d.polygon([(40 * u, 33 * u), (33 * u, 31 * u), (37 * u, 27 * u)], fill=colour)
    d.polygon([(40 * u, 15 * u), (33 * u, 17 * u), (37 * u, 21 * u)], fill=colour)


def draw_note(d, u, colour):
    """Small eighth note, used on the now-playing bar."""
    d.rectangle([24 * u, 10 * u, 27 * u, 32 * u], fill=colour)
    d.polygon([(27 * u, 10 * u), (37 * u, 14 * u), (36 * u, 20 * u), (27 * u, 17 * u)],
              fill=colour)
    cx, cy = 19 * u, 32 * u
    rx, ry = 6.5 * u, 5 * u
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=colour)


def draw_repeat_all(d, u, colour):
    """A rectangular loop with arrowheads: list repeat.

    Drawn from straight segments rather than arcs: PIL's arc with these angles
    produced a lopsided shape whose arrowhead did not sit on the curve.
    """
    w = max(1, int(2.6 * u))
    left, right = 13 * u, 35 * u
    top, bottom = 16 * u, 32 * u

    def line(x0, y0, x1, y1):
        d.line([(x0, y0), (x1, y1)], fill=colour, width=w)

    # Top run to the right, then down the right side.
    line(left, top, right, top)
    line(right, top, right, bottom)
    # Bottom run to the left, then back up the left side.
    line(right, bottom, left, bottom)
    line(left, bottom, left, top)

    # Arrowheads closing the loop.
    d.polygon([(left, top), (left + 7 * u, top - 3.5 * u), (left + 7 * u, top + 3.5 * u)],
              fill=colour)
    d.polygon([(right, bottom), (right - 7 * u, bottom - 3.5 * u),
               (right - 7 * u, bottom + 3.5 * u)], fill=colour)


def draw_repeat_one(d, u, colour):
    """The same loop with a 1 in the middle: single track repeat."""
    draw_repeat_all(d, u, colour)
    w = max(1, int(2.6 * u))
    d.line([(24 * u, 21 * u), (24 * u, 28 * u)], fill=colour, width=w)
    d.line([(21.5 * u, 23 * u), (24 * u, 21 * u)], fill=colour, width=w)


def main():
    # The toggle icons come in two colours: an inactive grey and an active accent
    # blue. Swapping the drawable reads more clearly as on/off than fading one
    # icon, and it avoids depending on alpha behaviour.
    glyphs = {
        "ic_play": (draw_play, ACCENT),
        "ic_pause": (draw_pause, ACCENT),
        "ic_prev": (draw_prev, ACCENT),
        "ic_next": (draw_next, ACCENT),
        "ic_shuffle_off": (draw_shuffle, SECONDARY),
        "ic_shuffle_on": (draw_shuffle, ACCENT),
        "ic_repeat_off": (draw_repeat_all, SECONDARY),
        "ic_repeat_all": (draw_repeat_all, ACCENT),
        "ic_repeat_one": (draw_repeat_one, ACCENT),
    }

    for folder, size in DENSITIES.items():
        out_dir = os.path.join(BASE, folder)
        os.makedirs(out_dir, exist_ok=True)
        for name, (func, colour) in glyphs.items():
            img, d, u = canvas(size)
            func(d, u, colour)
            path = os.path.join(out_dir, name + ".png")
            img.resize((size, size), Image.LANCZOS).save(path, "PNG")
        print("wrote %d icons to %s (%dx%d)" % (len(glyphs), folder, size, size))

    # Small action glyphs for the now-playing bar. The sizes are given in dp and
    # scaled by the density factor.
    bar = {
        "ic_bar_play": (draw_play, ACCENT, 32),
        "ic_bar_pause": (draw_pause, ACCENT, 32),
        "ic_bar_note": (draw_note, SECONDARY, 24),
    }
    for folder, scale in BAR_DENSITIES.items():
        out_dir = os.path.join(BASE, folder)
        os.makedirs(out_dir, exist_ok=True)
        for name, (func, colour, dp) in bar.items():
            size = max(1, int(dp * scale))
            img, d, u = canvas(size)
            func(d, u, colour)
            path = os.path.join(out_dir, name + ".png")
            img.resize((size, size), Image.LANCZOS).save(path, "PNG")
        print("wrote %d bar icons to %s (scale %.2f)" % (len(bar), folder, scale))


if __name__ == "__main__":
    main()
