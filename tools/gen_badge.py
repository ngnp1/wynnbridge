"""Generate the channel badges and the cat-arrow icon shipped with the mod.

The badge lettering is drawn with Wynncraft's own font (wynnfruma.ttf), the
same face the server uses in chat. The badges are 8 px tall and the icons 7 px,
so a themed line keeps the height of a normal chat line and shares its baseline.
The badge fill matches the colour the mod draws that channel's text in.

Run from the repo root:  python3 tools/gen_badge.py [path/to/wynnfruma.ttf]
The font defaults to ~/Downloads/wynnfruma.ttf; it is only needed to
regenerate the PNGs, not to build the mod.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CLEAR = (0, 0, 0, 0)

# Icons are 7 px tall; the badge pill is 8 so 6 px lettering keeps 1 px padding.
ICON_HEIGHT = 7
BADGE_HEIGHT = 8

BADGE_FONT_SIZE = 14
DEFAULT_FONT = Path.home() / "Downloads" / "wynnfruma.ttf"

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
    left, top, right, bottom = font.getbbox(label)
    mask = Image.new("L", (right - left + 2, bottom - top + 2), 0)
    ImageDraw.Draw(mask).text((1 - left, 1 - top), label, font=font, fill=255)
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
