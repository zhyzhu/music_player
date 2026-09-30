"""Check WCAG contrast for every text-on-background pair in the UI.

Guessing that a colour pair "looks fine" is how the invisible search text
happened, so compute the actual ratios instead.
"""
BG = (0x10, 0x14, 0x18)
PANEL = (0x1B, 0x20, 0x27)
TEXT_PRIMARY = (0xF2, 0xF5, 0xF8)
TEXT_SECONDARY = (0x8B, 0x97, 0xA6)
TEXT_HINT = (0x99, 0xF2, 0xF5, 0xF8)   # 60% alpha of text_primary over panel
ACCENT = (0x3D, 0xA9, 0xFC)


def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb):
    r, g, b = (srgb_to_lin(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def flatten(fg, bg, alpha):
    """Composite an alpha-blended foreground over an opaque background."""
    return tuple(round(f * alpha + b * (1 - alpha)) for f, b in zip(fg, bg))


def ratio(fg, bg):
    l1, l2 = luminance(fg), luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def verdict(r):
    if r >= 7.0:
        return "AAA"
    if r >= 4.5:
        return "AA"
    if r >= 3.0:
        return "AA-large"
    return "FAIL"


pairs = [
    ("search text        on search field", TEXT_PRIMARY, PANEL),
    ("search hint        on search field", flatten(TEXT_HINT, PANEL, 0.60), PANEL),
    ("list_info          on app bg      ", TEXT_SECONDARY, BG),
    ("song title         on app bg      ", TEXT_PRIMARY, BG),
    ("song artist/time   on app bg      ", TEXT_SECONDARY, BG),
    ("metadata row       on row bg      ", TEXT_SECONDARY, BG),
    ("now playing        on panel       ", ACCENT, PANEL),
    ("title bar          on panel       ", TEXT_PRIMARY, PANEL),
    ("empty/list message on app bg      ", TEXT_SECONDARY, BG),
]

print("%-36s %8s  %s" % ("pair", "ratio", "verdict"))
print("-" * 60)
worst = None
for name, fg, bg in pairs:
    r = ratio(fg, bg)
    print("%-36s %8.2f  %s" % (name, r, verdict(r)))
    if worst is None or r < worst[1]:
        worst = (name, r)

print()
print("worst pair: %s -> %.2f (%s)" % (worst[0].strip(), worst[1], verdict(worst[1])))
