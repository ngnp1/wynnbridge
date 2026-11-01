"""Generate the channel badges and the cat-arrow icon shipped with the mod.

Both glyphs are 8 px tall, the same as Minecraft's default font, so a themed
line has exactly the height of a normal chat line and shares its baseline.
The badge fill matches the colour the mod draws that channel's text in.

Run from the repo root:  python3 tools/gen_badge.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

CLEAR = (0, 0, 0, 0)

HEIGHT = 7

# 5x5 hollow letters: 1 px strokes with open counters, like Wynncraft's badges.
LETTERS = {
    "A": ["01110", "10001", "11111", "10001", "10001"],
    "B": ["11110", "10001", "11110", "10001", "11110"],
    "D": ["11110", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "11110", "10000", "11111"],
    "G": ["01110", "10001", "10000", "10011", "01110"],
    "I": ["111", "010", "010", "010", "111"],
    "L": ["1000", "1000", "1000", "1000", "1111"],
    "M": ["10001", "11011", "10101", "10001", "10001"],
    "R": ["11110", "10001", "11110", "10010", "10001"],
}

# Right-pointing arrow with a flat left edge, so the chat line starts at the
# same place as Wynncraft's own messages.
ARROW = [
    "########....",
    "##########..",
    "###########.",
    "############",
    "###########.",
    "##########..",
    "########....",
]

# Cat face inside the arrow: ears, head, then eyes and nose punched in.
CAT = ["#...#", "#####", "#####", ".###."]
CAT_X, CAT_Y = 1, 1
EYES = [(2, 3), (4, 3)]
NOSE = [(3, 4)]

# A channel is a badge label plus the three colours its glyphs are drawn in:
# badge/arrow fill, cat silhouette, and lettering/eyes/nose.
CHANNELS = {
    # Discord -> game, #ff3b80 glyphs, white lettering, sender name #cf0042
    "bridge": {
        "label": "BRIDGE",
        "bg": (0xFF, 0x3B, 0x80, 255),
        "mid": (0xB3, 0x25, 0x58, 255),
        "fg": (0xFF, 0xFF, 0xFF, 255),
    },
    # Mail, #ffb86c
    "mail": {
        "label": "MAIL",
        "bg": (0xFF, 0xB8, 0x6C, 255),
        "mid": (0xC8, 0x85, 0x43, 255),
        "fg": (0x35, 0x22, 0x0D, 255),
    },
}

ROOT = Path(__file__).resolve().parent.parent
TEX = ROOT / "src/main/resources/assets/wynnbridge/textures/font"


def draw_badge(label: str, bg, fg) -> Image.Image:
    widths = [len(LETTERS[c][0]) for c in label]
    text_w = sum(widths) + (len(label) - 1)
    w = text_w + 2
    img = Image.new("RGBA", (w, HEIGHT), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, HEIGHT - 1], radius=2, fill=bg)
    x = 1
    for ch, cw in zip(label, widths):
        for ry, row in enumerate(LETTERS[ch]):
            for rx, bit in enumerate(row):
                if bit == "1":
                    d.point((x + rx, 1 + ry), fill=fg)
        x += cw + 1
    return img


def draw_icon(bg, mid, fg) -> Image.Image:
    w = max(len(row) for row in ARROW)
    img = Image.new("RGBA", (w, HEIGHT), CLEAR)

    for y, row in enumerate(ARROW):
        for x, ch in enumerate(row):
            if ch == "#":
                img.putpixel((x, y), bg)

    for dy, row in enumerate(CAT):
        for dx, ch in enumerate(row):
            if ch == "#":
                img.putpixel((CAT_X + dx, CAT_Y + dy), mid)

    for x, y in EYES + NOSE:
        img.putpixel((x, y), fg)

    return img


def main() -> None:
    TEX.mkdir(parents=True, exist_ok=True)
    for name, channel in CHANNELS.items():
        images = {
            name + "_badge.png": draw_badge(channel["label"], channel["bg"], channel["fg"]),
            name + "_icon.png": draw_icon(channel["bg"], channel["mid"], channel["fg"]),
        }
        for file_name, img in images.items():
            img.save(TEX / file_name)
            img.resize((img.width * 10, img.height * 10), Image.NEAREST).save("/tmp/" + file_name)
            print(file_name, img.size)


if __name__ == "__main__":
    main()
