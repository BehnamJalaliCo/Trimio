"""Draws the desktop app icons from the Android launcher mark (androidApp/src/main/res/drawable/
ic_launcher_foreground.xml): the play triangle in the brand gradient cut by a motion streak, on the
icon background colour, in a rounded square. Writes trimio.png (Linux, window icon), trimio.ico
(Windows) and trimio.icns (macOS) next to this script.

    python3 desktopApp/icons/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SIZE = 1024
BACKGROUND = (0x0E, 0x0F, 0x18, 255)
STREAK = (0xF4, 0xF5, 0xFF, 255)
GRADIENT = [(0.0, (0x3D, 0xE8, 0xFF)), (0.55, (0x7C, 0x5C, 0xFF)), (1.0, (0xFF, 0x4F, 0xD8))]
# The launcher's 108-unit viewport, cropped to the part around the mark so it fills a desktop icon.
VIEW_MIN, VIEW_SIZE = 18.0, 72.0


def unit(v: float) -> float:
    return (v - VIEW_MIN) / VIEW_SIZE * SIZE


def gradient_at(t: float) -> tuple:
    t = min(max(t, 0.0), 1.0)
    for (a, ca), (b, cb) in zip(GRADIENT, GRADIENT[1:]):
        if t <= b:
            k = (t - a) / (b - a)
            return tuple(round(x + (y - x) * k) for x, y in zip(ca, cb)) + (255,)
    return GRADIENT[-1][1] + (255,)


def draw() -> Image.Image:
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(icon).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=SIZE * 0.22, fill=BACKGROUND)

    # Linear gradient along the triangle's diagonal (44,36) → (74,72), masked by the triangle.
    x0, y0, x1, y1 = unit(44), unit(36), unit(74), unit(72)
    dx, dy = x1 - x0, y1 - y0
    length2 = dx * dx + dy * dy
    fill = Image.new("RGBA", (SIZE, SIZE))
    pixels = fill.load()
    for y in range(SIZE):
        for x in range(SIZE):
            pixels[x, y] = gradient_at(((x - x0) * dx + (y - y0) * dy) / length2)
    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).polygon([(unit(44), unit(36)), (unit(74), unit(54)), (unit(44), unit(72))], fill=255)
    icon.paste(fill, (0, 0), mask)

    streaks = ImageDraw.Draw(icon)
    for left, top, right, bottom in [(30, 52, 40, 56), (34, 44, 41, 47), (34, 61, 41, 64)]:
        streaks.rectangle((unit(left), unit(top), unit(right), unit(bottom)), fill=STREAK)
    return icon


def main() -> None:
    icon = draw()
    icon.resize((512, 512), Image.LANCZOS).save(HERE / "trimio.png")
    icon.save(HERE / "trimio.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    icon.save(HERE / "trimio.icns")


if __name__ == "__main__":
    main()
