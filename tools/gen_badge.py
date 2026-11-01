"""Generate the BRIDGE badge and Discord icon glyphs shipped with the mod.

Run from the repo root:  python3 tools/gen_badge.py
Writes PNGs next to the mod resources and enlarges previews to /tmp.
"""

from pathlib import Path

from PIL import Image, ImageDraw

BG = (0xAC, 0xD8, 0xAA, 255)
FG = (0x14, 0x26, 0x1A, 255)
CLEAR = (0, 0, 0, 0)

# 4x5 pixel letters, matching the proportions of Wynncraft's rank badges.
LETTERS = {
    "B": ["1110", "1001", "1110", "1001", "1110"],
    "R": ["1110", "1001", "1110", "1010", "1001"],
    "I": ["111", "010", "010", "010", "111"],
    "D": ["1110", "1001", "1001", "1001", "1110"],
    "G": ["0110", "1001", "1011", "1001", "0110"],
    "E": ["1111", "1000", "1110", "1000", "1111"],
}
LABEL = "BRIDGE"

# Discord mark: two horns and two eyes, with a point on the right so it reads
# like the arrow Wynncraft puts in front of its rank badges.
ICON = [
    "...........",
    ".##...##...",
    "#########..",
    "###########",
    "##.###.####",
    "###########",
    "#########..",
    ".##...##...",
    "...........",
]

ROOT = Path(__file__).resolve().parent.parent
TEX = ROOT / "src/main/resources/assets/wynnbridge/textures/font"


def draw_badge() -> Image.Image:
    widths = [len(LETTERS[c][0]) for c in LABEL]
    text_w = sum(widths) + (len(LABEL) - 1)
    w = text_w + 6
    img = Image.new("RGBA", (w, 9), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, 8], radius=2, fill=BG)
    x = 3
    for ch, cw in zip(LABEL, widths):
        for ry, row in enumerate(LETTERS[ch]):
            for rx, bit in enumerate(row):
                if bit == "1":
                    d.point((x + rx, 2 + ry), fill=FG)
        x += cw + 1
    return img


def draw_icon() -> Image.Image:
    w = max(len(row) for row in ICON)
    h = len(ICON)
    img = Image.new("RGBA", (w, h), CLEAR)
    for y, row in enumerate(ICON):
        for x, bit in enumerate(row):
            if bit != ".":
                img.putpixel((x, y), BG if bit == "#" else CLEAR)
    return img


def main() -> None:
    TEX.mkdir(parents=True, exist_ok=True)
    for name, img in (("bridge_badge.png", draw_badge()), ("bridge_icon.png", draw_icon())):
        img.save(TEX / name)
        img.resize((img.width * 10, img.height * 10), Image.NEAREST).save("/tmp/" + name)
        print(name, img.size)


if __name__ == "__main__":
    main()
