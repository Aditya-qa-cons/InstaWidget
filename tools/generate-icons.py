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


def gear(d, cx, cy, r, scale):
    """Small settings glyph: a ring with teeth and a hollow centre."""
    import math
    teeth = 8
    tw = max(1, int(2.0 * scale))
    for i in range(teeth):
        a = math.pi * 2 * i / teeth
        x1, y1 = cx + math.cos(a) * r * 0.82, cy + math.sin(a) * r * 0.82
        x2, y2 = cx + math.cos(a) * r * 1.35, cy + math.sin(a) * r * 1.35
        d.line((x1, y1, x2, y2), fill="white", width=tw)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill="white")
    ir = r * 0.4
    d.ellipse((cx - ir, cy - ir, cx + ir, cy + ir), fill=(193, 53, 132))


def avatar(d, cx, cy, r, letter, fill, scale):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)
    f = font(SANS_BOLD, int(r * 1.0))
    w = d.textlength(letter, font=f)
    bbox = f.getbbox(letter)
    d.text((cx - w / 2, cy - (bbox[1] + bbox[3]) / 2), letter, font=f, fill="white")


AVATARS = [(225, 48, 108), (131, 58, 180), (47, 128, 237),
           (23, 162, 162), (239, 138, 58), (91, 140, 42)]


def preview(scale):
    """Mock of the placed widget, drawn at `scale` px per dp."""
    w, h = int(280 * scale), int(190 * scale)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Card with the same soft vertical wash as widget_background.xml.
    card = Image.new("RGB", (1, h), "white")
    px = card.load()
    for i in range(h):
        t = i / max(h - 1, 1)
        px[0, i] = (round(255 + (253 - 255) * t),
                    round(255 + (242 - 255) * t),
                    round(255 + (248 - 255) * t))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w - 1, h - 1), radius=int(24 * scale), fill=255)
    img.paste(card.resize((w, h)), (0, 0), mask)

    pad = int(10 * scale)

    # Gradient header pill.
    ph = int(30 * scale)
    strip = Image.new("RGB", (w - 2 * pad, 1))
    sp = strip.load()
    ramp = [(131, 58, 180), (193, 53, 132), (253, 89, 73)]
    for i in range(strip.width):
        t = i / max(strip.width - 1, 1)
        a, b, u = (ramp[0], ramp[1], t * 2) if t < 0.5 else (ramp[1], ramp[2], (t - 0.5) * 2)
        sp[i, 0] = tuple(round(a[c] + (b[c] - a[c]) * u) for c in range(3))
    pill_mask = Image.new("L", (strip.width, ph), 0)
    ImageDraw.Draw(pill_mask).rounded_rectangle(
        (0, 0, strip.width - 1, ph - 1), radius=int(18 * scale), fill=255)
    img.paste(strip.resize((strip.width, ph)), (pad, pad), pill_mask)

    gy = pad + ph // 2
    plane(d, (pad + int(12 * scale), gy - int(6 * scale), int(13 * scale), int(13 * scale)),
          "white")
    hf = font(SANS_BOLD, int(13 * scale))
    d.text((pad + int(32 * scale), gy - int(8 * scale)), "Instagram DMs", font=hf, fill="white")
    gear(d, w - pad - int(18 * scale), gy, int(7.5 * scale), scale)

    # Rows.
    y = pad + ph + int(6 * scale)
    rows = [("priya.desai", "2m", "are you coming tonight?", 3),
            ("marco_v", "18m", "sent you a photo", 1),
            ("aisha.k", "1h", "haha that reel is so accurate", 1)]
    rh = int(46 * scale)
    for i, (name, when, text, count) in enumerate(rows):
        d.rounded_rectangle((pad, y, w - pad, y + rh), radius=int(16 * scale),
                            fill=(131, 58, 180, 10))
        cy = y + rh // 2
        ar = int(17 * scale)
        avatar(d, pad + int(10 * scale) + ar, cy, ar, name[0].upper(),
               AVATARS[i % len(AVATARS)], scale)
        tx = pad + int(10 * scale) + 2 * ar + int(10 * scale)
        d.text((tx, cy - int(15 * scale)), name, font=font(SANS_BOLD, int(13 * scale)),
               fill=(31, 27, 36))
        wf = font(SANS, int(11 * scale))
        wx = w - pad - int(12 * scale) - d.textlength(when, font=wf)
        d.text((wx, cy - int(14 * scale)), when, font=wf, fill=(154, 147, 163))
        if count > 1:
            # Unread-style pill, matching count_badge.xml.
            bf = font(SANS_BOLD, int(10 * scale))
            label = str(count)
            bw = max(d.textlength(label, font=bf) + int(10 * scale), int(18 * scale))
            bh = int(15 * scale)
            bx = wx - int(8 * scale) - bw
            by = cy - int(15 * scale)
            d.rounded_rectangle((bx, by, bx + bw, by + bh), radius=int(9 * scale),
                                fill=(193, 53, 132))
            d.text((bx + (bw - d.textlength(label, font=bf)) / 2, by + int(1.5 * scale)),
                   label, font=bf, fill="white")
        d.text((tx, cy + int(1 * scale)), text, font=font(SANS, int(12 * scale)),
               fill=(85, 78, 94))
        y += rh + int(5 * scale)
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
