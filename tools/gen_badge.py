"""Generate the BRIDGE badge and the cat-arrow icon shipped with the mod.

Run from the repo root:  python3 tools/gen_badge.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

BG = (0xAC, 0xD8, 0xAA, 255)  # arrow and badge fill
MID = (0x74, 0xA8, 0x80, 255)  # cat silhouette
FG = (0x14, 0x26, 0x1A, 255)  # lettering, eyes, nose
CLEAR = (0, 0, 0, 0)

# 5x7 hollow letters: 1px strokes with open counters, like Wynncraft's badges.
LETTERS = {
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "I": ["111", "010", "010", "010", "010", "010", "111"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "G": ["01110", "10001", "10000", "10011", "10001", "10001", "01110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
}
LABEL = "BRIDGE"

# Right-pointing arrow with a flat left edge, so the chat line starts at the
# same place as Wynncraft's own messages.
ARROW = [
    "########....",
    "#########...",
    "##########..",
    "###########.",
    "############",
    "###########.",
    "##########..",
    "#########...",
    "########....",
]

# Cat face inside the arrow: ears, head, then eyes and nose punched in.
CAT = ["#...#", "##.##", "#####", "#.#.#", ".###."]
CAT_X, CAT_Y = 1, 2
EYES = [(2, 5), (4, 5)]
NOSE = [(3, 6)]

ROOT = Path(__file__).resolve().parent.parent
TEX = ROOT / "src/main/resources/assets/wynnbridge/textures/font"


def draw_badge() -> Image.Image:
    widths = [len(LETTERS[c][0]) for c in LABEL]
    text_w = sum(widths) + (len(LABEL) - 1)
    w = text_w + 4
    img = Image.new("RGBA", (w, 9), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, 8], radius=2, fill=BG)
    x = 2
    for ch, cw in zip(LABEL, widths):
        for ry, row in enumerate(LETTERS[ch]):
            for rx, bit in enumerate(row):
                if bit == "1":
                    d.point((x + rx, 1 + ry), fill=FG)
        x += cw + 1
    return img


def draw_icon() -> Image.Image:
    w = max(len(row) for row in ARROW)
    h = len(ARROW)
    img = Image.new("RGBA", (w, h), CLEAR)

    for y, row in enumerate(ARROW):
        for x, ch in enumerate(row):
            if ch == "#":
                img.putpixel((x, y), BG)

    for dy, row in enumerate(CAT):
        for dx, ch in enumerate(row):
            if ch == "#":
                img.putpixel((CAT_X + dx, CAT_Y + dy), MID)

    for x, y in EYES + NOSE:
        img.putpixel((x, y), FG)

    return img


def main() -> None:
    TEX.mkdir(parents=True, exist_ok=True)
    for name, img in (("bridge_badge.png", draw_badge()), ("bridge_icon.png", draw_icon())):
        img.save(TEX / name)
        img.resize((img.width * 10, img.height * 10), Image.NEAREST).save("/tmp/" + name)
        print(name, img.size)


if __name__ == "__main__":
    main()
