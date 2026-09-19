#!/usr/bin/env python3
"""
Generates the PNG launcher icons and the widget preview image.

Vector drawables are fine for the app itself, but several OEM launchers
(Xiaomi's among them) decode `android:previewImage` as a bitmap and silently
skip a widget whose preview is an XML drawable -- so the preview in particular
has to be a real PNG. The launcher icon is bitmapped for the same reason: some
widget pickers list apps by their bitmap icon.

Run from the project root:  python3 tools/generate-icons.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")

SANS = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
SANS_BOLD = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"

BRAND_TOP = (131, 58, 180)     # instagram-ish purple
BRAND_MID = (193, 53, 132)     # magenta
BRAND_BOT = (253, 89, 73)      # warm orange
TITLE = (17, 17, 17)
BODY = (68, 68, 68)
SECONDARY = (138, 138, 138)
DIVIDER = (226, 226, 226)


def font(path, size):
    return ImageFont.truetype(path, size)


def plane(draw, box, fill):
    """Paper-plane glyph scaled into `box` = (x, y, w, h)."""
    x, y, w, h = box
    pts = [(0.02, 0.92), (1.00, 0.50), (0.02, 0.08), (0.00, 0.42),
           (0.65, 0.50), (0.00, 0.58)]
    draw.polygon([(x + px * w, y + py * h) for px, py in pts], fill=fill)


def gradient(size):
    img = Image.new("RGB", (1, size), BRAND_TOP)
    px = img.load()
    for i in range(size):
        t = i / max(size - 1, 1)
        if t < 0.5:
            a, b, u = BRAND_TOP, BRAND_MID, t * 2
        else:
            a, b, u = BRAND_MID, BRAND_BOT, (t - 0.5) * 2
        px[0, i] = tuple(round(a[c] + (b[c] - a[c]) * u) for c in range(3))
    return img.resize((size, size))


def launcher_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=255)
    img.paste(gradient(size), (0, 0), mask)
    plane(ImageDraw.Draw(img), (size * 0.22, size * 0.24, size * 0.56, size * 0.52),
          (255, 255, 255, 255))
    return img


def preview(scale):
    """Mock of the placed widget, drawn at `scale` px per dp."""
    w, h = int(280 * scale), int(190 * scale)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = int(6 * scale)
    d.rounded_rectangle((pad, pad, w - pad, h - pad),
                        radius=int(16 * scale), fill=(255, 255, 255, 242))

    left = int(16 * scale)
    right = w - int(16 * scale)
    y = int(18 * scale)

    glyph = int(13 * scale)
    plane(d, (left, y + int(1 * scale), glyph, glyph), BRAND_MID)
    d.text((left + glyph + int(7 * scale), y), "Instagram DMs",
           font=font(SANS_BOLD, int(14 * scale)), fill=TITLE)
    hint = "Tap to open"
    hint_font = font(SANS, int(10 * scale))
    d.text((right - d.textlength(hint, font=hint_font), y + int(3 * scale)),
           hint, font=hint_font, fill=SECONDARY)

    y += int(26 * scale)
    rows = [("priya.desai", "2m", "are you coming tonight?"),
            ("marco_v", "18m", "sent you a photo"),
            ("aisha.k", "1h", "haha that reel is so accurate")]
    for name, when, text in rows:
        d.line((left, y, right, y), fill=DIVIDER, width=max(1, int(scale)))
        y += int(9 * scale)
        d.text((left, y), name, font=font(SANS_BOLD, int(13 * scale)), fill=TITLE)
        when_font = font(SANS, int(10 * scale))
        d.text((right - d.textlength(when, font=when_font), y + int(2 * scale)),
               when, font=when_font, fill=SECONDARY)
        y += int(17 * scale)
        d.text((left, y), text, font=font(SANS, int(12 * scale)), fill=BODY)
        y += int(21 * scale)
    return img


def write(img, folder, name):
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, name)
    img.save(path, "PNG", optimize=True)
    print(f"{path}  {img.size[0]}x{img.size[1]}")


def main():
    for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72),
                         ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144),
                         ("mipmap-xxxhdpi", 192)]:
        write(launcher_icon(size), folder, "ic_launcher.png")

    for folder, scale in [("drawable-mdpi", 1), ("drawable-hdpi", 1.5),
                          ("drawable-xhdpi", 2), ("drawable-xxhdpi", 3)]:
        write(preview(scale), folder, "widget_preview.png")


if __name__ == "__main__":
    main()
