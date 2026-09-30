"""Generate the transport control icons.

The stock Android buttons look poor, so the player controls are ImageButtons with
flat vector-ish glyphs drawn here. Output is plain PNG, one per density, drawn at
4x and downsampled for smooth edges - the same approach as the launcher icons.
"""
import math
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


def rounded_bar(d, x0, y0, x1, y1, r, colour):
    """Vertical rounded bar; the building block of the transport glyphs."""
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=colour)


def rounded_polygon(d, verts, r, colour):
    """Filled polygon with circular corners.

    PIL has no rounded polygon. For each vertex the two edges are shortened by
    the tangent length and joined with an arc whose centre lies on the angle
    bisector - stamping discs along the outline instead leaves a hollow shape,
    because the discs do not merge at plausible sizes.
    """
    n = len(verts)
    r = min(r, _min_half_edge(verts))
    outline = []
    # The tangent point where the previous corner's arc ended; the straight edge
    # from there to this corner's first tangent point has to be emitted too, or
    # the polygon closes arc-to-arc and comes out as a bowtie.
    carry = None

    for i in range(n):
        prev = verts[(i - 1) % n]
        cur = verts[i]
        nxt = verts[(i + 1) % n]
        p = _unit(prev[0] - cur[0], prev[1] - cur[1])
        q = _unit(nxt[0] - cur[0], nxt[1] - cur[1])
        angle = math.acos(max(-1.0, min(1.0, p[0] * q[0] + p[1] * q[1])))
        if angle <= 0.0001:
            continue
        tangent = r / math.tan(angle / 2.0)
        t1 = (cur[0] + p[0] * tangent, cur[1] + p[1] * tangent)
        t2 = (cur[0] + q[0] * tangent, cur[1] + q[1] * tangent)
        bisect = _unit(p[0] + q[0], p[1] + q[1])
        centre = (cur[0] + bisect[0] * (r / math.sin(angle / 2.0)),
                  cur[1] + bisect[1] * (r / math.sin(angle / 2.0)))

        if carry is not None:
            outline.append(carry)
        outline.append(t1)

        start = math.degrees(math.atan2(t1[1] - centre[1], t1[0] - centre[0]))
        end = math.degrees(math.atan2(t2[1] - centre[1], t2[0] - centre[0]))
        # Sweep must pass through the point where the two tangent lines would
        # have met, i.e. the corner itself. Try both directions and keep the one
        # that does so within a half turn.
        vertex_dir = math.degrees(math.atan2(cur[1] - centre[1], cur[0] - centre[0]))
        sweep_to = end
        if not _sweeps_through(start, end, vertex_dir):
            if _sweeps_through(start, end + 360.0, vertex_dir):
                sweep_to = end + 360.0
            elif _sweeps_through(start, end - 360.0, vertex_dir):
                sweep_to = end - 360.0
        steps = 14
        for s in range(steps + 1):
            angle_step = math.radians(start + (sweep_to - start) * (s / float(steps)))
            outline.append((centre[0] + r * math.cos(angle_step),
                            centre[1] + r * math.sin(angle_step)))
        carry = t2

    d.polygon(outline, fill=colour)


def _sweeps_through(start, end, target):
    """True when sweeping from start to end (in that direction) passes target."""
    span = end - start
    offset = ((target - start) % 360.0 + 360.0) % 360.0
    if span >= 0:
        return offset <= span + 0.001
    return (offset - 360.0) >= span - 0.001


def _unit(dx, dy):
    length = (dx * dx + dy * dy) ** 0.5
    return (0.0, 0.0) if length == 0 else (dx / length, dy / length)


def _min_half_edge(verts):
    """Cap on the corner radius so the shape cannot invert."""
    shortest = None
    n = len(verts)
    for i in range(n):
        ax, ay = verts[i]
        bx, by = verts[(i + 1) % n]
        edge = ((bx - ax) ** 2 + (by - ay) ** 2) ** 0.5
        if shortest is None or edge < shortest:
            shortest = edge
    return shortest / 2.0 * 0.9


def draw_play(d, u, colour):
    """Solid play triangle with softened corners."""
    rounded_polygon(d, [(15.5 * u, 11.0 * u), (15.5 * u, 37.0 * u), (37.5 * u, 24.0 * u)],
                    3.2 * u, colour)


def draw_pause(d, u, colour):
    """Two rounded bars."""
    r = 1.8 * u
    rounded_bar(d, 15.5 * u, 12.0 * u, 21.5 * u, 36.0 * u, r, colour)
    rounded_bar(d, 26.5 * u, 12.0 * u, 32.5 * u, 36.0 * u, r, colour)


def draw_prev(d, u, colour):
    """Bar plus a left pointing triangle, both softened."""
    rounded_bar(d, 12.5 * u, 13.5 * u, 16.3 * u, 34.5 * u, 1.8 * u, colour)
    rounded_polygon(d, [(37.0 * u, 12.5 * u), (37.0 * u, 35.5 * u), (18.5 * u, 24.0 * u)],
                    3.0 * u, colour)


def draw_next(d, u, colour):
    """Bar plus a right pointing triangle, both softened."""
    rounded_bar(d, 31.7 * u, 13.5 * u, 35.5 * u, 34.5 * u, 1.8 * u, colour)
    rounded_polygon(d, [(11.0 * u, 12.5 * u), (11.0 * u, 35.5 * u), (29.5 * u, 24.0 * u)],
                    3.0 * u, colour)


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
