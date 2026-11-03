"""Generate the channel badges and the cat-arrow icon shipped with the mod.

The badge lettering is cut from the Serified Font resource pack's ascii.png:
Minecraft's own font atlas with serifs. Glyphs are laid out exactly as the
vanilla provider does (two blank rows, then ASCII from U+0020, one-cell advance
plus a 1 px gap), so the labels read like the game's chat font. They are 7 px
tall, which keeps a 1 px border inside the 9 px pill next to the 7 px icon.
The badge fill matches the colour the mod draws that channel's text in.

Run from the repo root:  python3 tools/gen_badge.py [path/to/pack.zip]
The pack defaults to the PrismLauncher instance's Serified Font v1.2.zip; only
the baked PNGs are needed to build the mod.
"""

import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

CLEAR = (0, 0, 0, 0)

# The icons are 7 px tall and the badge lettering is 7 px, so the pill is 9 px
# and both glyphs span the same band above the baseline.
ICON_HEIGHT = 7
BADGE_HEIGHT = 9

# The ascii sheet is a 16x16 grid of 8 px cells. The first two rows of the
# vanilla provider are blank, so printable ASCII starts on the third row.
SHEET_CELL = 8
SHEET_ROWS_OFFSET = 2

DEFAULT_PACK = (Path.home() / ".var/app/org.prismlauncher.PrismLauncher/data/"
                "PrismLauncher/instances/wynncraft_1.21.11/minecraft/resourcepacks/"
                "Serified Font v1.2.zip")

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


def glyph(sheet: Image.Image, char: str) -> Image.Image:
    index = ord(char) - 0x20
    row, col = SHEET_ROWS_OFFSET + index // 16, index % 16
    return sheet.crop((col * SHEET_CELL, row * SHEET_CELL,
                       (col + 1) * SHEET_CELL, (row + 1) * SHEET_CELL))


def label_image(sheet: Image.Image, label: str) -> Image.Image:
    """The label set with the sheet's own advances: ink width plus a 1 px gap."""
    widths = []
    for char in label:
        bbox = glyph(sheet, char).getbbox()
        widths.append(0 if bbox is None else bbox[2])
    out = Image.new("RGBA", (sum(widths) + len(widths) - 1, SHEET_CELL), CLEAR)
    x = 0
    for char, width in zip(label, widths):
        out.alpha_composite(glyph(sheet, char), (x, 0))
        x += width + 1
    return out.crop(out.getbbox())


def draw_badge(label: str, bg, fg, sheet: Image.Image) -> Image.Image:
    """A pill as wide as the ink, with the label centred in 1 px of padding."""
    text = label_image(sheet, label)
    ink = Image.new("RGBA", text.size, fg)
    ink.putalpha(text.getchannel("A"))

    w = text.width + 2
    img = Image.new("RGBA", (w, BADGE_HEIGHT), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, BADGE_HEIGHT - 1], radius=2, fill=bg)
    img.alpha_composite(ink, (1, (BADGE_HEIGHT - text.height) // 2))
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
    pack_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK
    if not pack_path.is_file():
        raise SystemExit(f"resource pack not found: {pack_path}")
    with zipfile.ZipFile(pack_path) as pack:
        sheet = Image.open(pack.open("assets/minecraft/textures/font/ascii.png"))
        sheet = sheet.convert("RGBA")

    TEX.mkdir(parents=True, exist_ok=True)
    for name, channel in CHANNELS.items():
        images = {
            name + "_badge.png": draw_badge(
                channel["label"], channel["bg"], channel.get("ink", channel["fg"]), sheet
            ),
            name + "_icon.png": draw_icon(channel["bg"], channel["mid"], channel["fg"]),
        }
        for file_name, img in images.items():
            img.save(TEX / file_name)
            img.resize((img.width * 10, img.height * 10), Image.NEAREST).save("/tmp/" + file_name)
            print(file_name, img.size)


if __name__ == "__main__":
    main()
