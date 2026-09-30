"""Generate launcher icons for the music player.

Draws an eighth-note glyph on a dark rounded tile at each density so the APK
ships real PNG icons. Each icon is rendered at 4x and downscaled with LANCZOS
for smooth edges.
"""
import os
from PIL import Image, ImageDraw

BASE = r"C:\Users\zhuzh\Documents\music_player\app\res"
DENSITIES = {
    "drawable-ldpi": 36,
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
}
SS = 4  # supersample factor

BG = (16, 20, 24, 255)
ACCENT = (61, 169, 252, 255)
NOTE = (242, 245, 248, 255)


def render(size):
    """Render one icon at the given pixel size."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Rounded tile with an accent border.
    radius = max(2, int(big * 0.22))
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=radius, fill=BG,
                        outline=ACCENT, width=max(1, big // 24))

    # Geometry expressed in a 48-unit master grid, scaled to the canvas.
    u = big / 48.0

    # Stem.
    stem_x = 30 * u
    stem_w = 2.6 * u
    d.rectangle([stem_x, 12 * u, stem_x + stem_w, 34 * u], fill=NOTE)

    # Flag.
    d.polygon([
        (stem_x + stem_w, 12 * u),
        (stem_x + stem_w + 7 * u, 16 * u),
        (stem_x + stem_w + 6 * u, 24 * u),
        (stem_x + stem_w, 21 * u),
    ], fill=NOTE)

    # Note head.
    cx, cy = 24 * u, 35 * u
    rx, ry = 7.5 * u, 5.5 * u
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=NOTE)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(BASE, folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.png")
        render(size).save(path, "PNG")
        print("wrote %s (%dx%d)" % (path, size, size))


if __name__ == "__main__":
    main()
