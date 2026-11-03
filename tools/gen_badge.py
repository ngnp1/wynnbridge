"""Generate the channel badges and the cat-arrow icon shipped with the mod.

The badge lettering uses Monocraft, an open font designed to look like
Minecraft's own. It is a pixel font, so it is rasterised monochrome at its
design size: 7 px caps inside a 9 px pill, next to the 7 px icon. Both glyph
bands line up on the chat baseline. The badge fill matches the colour the mod
draws that channel's text in.

Run from the repo root:  python3 tools/gen_badge.py [path/to/font.ttf]
The font defaults to tools/fonts/Monocraft-badges.ttf (a subset of Monocraft;
see tools/fonts/OFL.txt) and is only needed to regenerate the PNGs.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CLEAR = (0, 0, 0, 0)

# The icons are 7 px tall and the badge lettering is 7 px, so the pill is 9 px
# and both glyphs span the same band above the baseline.
ICON_HEIGHT = 7
BADGE_HEIGHT = 9

# Monocraft's pixel grid: 9 px gives 7 px caps with a whole-pixel advance.
BADGE_FONT_SIZE = 9
DEFAULT_FONT = Path(__file__).resolve().parent / "fonts" / "Monocraft-badges.ttf"

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

# A channel is a badge label plus the colours its glyphs are drawn in:
#   bg   badge and arrow fill
#   mid  cat silhouette inside the arrow
#   fg   eyes and nose inside the arrow
#   ink  lettering on the badge
CHANNELS = {
    # Discord -> game, #fd4988 glyphs, black lettering, sender name #cf0042
    "bridge": {
        "label": "BRIDGE",
        "bg": (0xFD, 0x49, 0x88, 255),
        "mid": (0xB1, 0x33, 0x5F, 255),
        "fg": (0x00, 0x00, 0x00, 255),
        "ink": (0x00, 0x00, 0x00, 255),
    },
    # Mail, brighter mail colour glyphs
    "mail": {
        "label": "MAIL",
        "bg": (0xFF, 0xE3, 0xC5, 255),
        "mid": (0xD9, 0xA9, 0x7C, 255),
        "fg": (0x35, 0x22, 0x0D, 255),
        "ink": (0x35, 0x22, 0x0D, 255),
    },
    # Mod messages, #50fa7b glyphs
    "system": {
        "label": "SYSTEM",
        "bg": (0x50, 0xFA, 0x7B, 255),
        "mid": (0x2F, 0xA3, 0x4F, 255),
        "fg": (0x00, 0x00, 0x00, 255),
        "ink": (0x00, 0x00, 0x00, 255),
    },
}

ROOT = Path(__file__).resolve().parent.parent
TEX = ROOT / "src/main/resources/assets/wynnbridge/textures/font"


def draw_badge(label: str, bg, fg, font: ImageFont.FreeTypeFont) -> Image.Image:
    """A pill as wide as the ink, with the label centred in 1 px of padding."""
    # Monocraft is a pixel font: the monochrome rasteriser gives hard 1-bit
    # edges instead of the fuzzy lettering later versions produced.
    core = font.getmask(label, mode="1")
    mask = Image.frombytes("L", core.size, bytes(core))
    mask = mask.crop(mask.getbbox())

    w = mask.width + 2
    img = Image.new("RGBA", (w, BADGE_HEIGHT), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, BADGE_HEIGHT - 1], radius=2, fill=bg)
    img.paste(Image.new("RGBA", mask.size, fg),
              (1, (BADGE_HEIGHT - mask.height) // 2), mask)
    return img


def draw_icon(bg, mid, fg) -> Image.Image:
    w = max(len(row) for row in ARROW)
    img = Image.new("RGBA", (w, ICON_HEIGHT), CLEAR)

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
    font_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_FONT
    if not font_path.is_file():
        raise SystemExit(f"font not found: {font_path}")
    font = ImageFont.truetype(str(font_path), BADGE_FONT_SIZE)

    TEX.mkdir(parents=True, exist_ok=True)
    for name, channel in CHANNELS.items():
        images = {
            name + "_badge.png": draw_badge(
                channel["label"], channel["bg"], channel.get("ink", channel["fg"]), font
            ),
            name + "_icon.png": draw_icon(channel["bg"], channel["mid"], channel["fg"]),
        }
        for file_name, img in images.items():
            img.save(TEX / file_name)
            img.resize((img.width * 10, img.height * 10), Image.NEAREST).save("/tmp/" + file_name)
            print(file_name, img.size)


if __name__ == "__main__":
    main()
