#!/usr/bin/env python3
"""
Generates every launcher asset for UU Downloader from one drawing.

A matte black tile with a single crisp mark on it. No colour, no bloom, no ornament: the
tile is black, the mark is white, and the only gradient left is the one that stops the black
from reading as a missing image.

Run from the repository root:

    python3 icon/generate_icon.py
"""
from __future__ import annotations

import pathlib

from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

SS = 2                 # supersample factor; every coordinate below is in 1024-space
SIZE = 1024
S = SIZE * SS

MARK_LIGHT = (255, 255, 255)
MARK_DEEP = (214, 222, 232)
GLOW = (236, 245, 255)
TILE_TOP = (13, 13, 15)
TILE_BOTTOM = (0, 0, 0)


def scaled(value: float) -> float:
    return value * SS


def vertical_gradient(top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    strip = Image.new("RGB", (1, S))
    draw = ImageDraw.Draw(strip)
    for y in range(S):
        t = y / (S - 1)
        draw.point((0, y), fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return strip.resize((S, S), Image.BILINEAR)


def rounded_mask(radius: float) -> Image.Image:
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, S - 1, S - 1], radius=scaled(radius), fill=255
    )
    return mask


def soft_radial(centre: tuple[float, float], radius: float, peak: int = 255) -> Image.Image:
    """A blurred disc used as an alpha mask for sheen and bloom."""
    layer = Image.new("L", (S, S), 0)
    cx, cy = scaled(centre[0]), scaled(centre[1])
    r = scaled(radius)
    ImageDraw.Draw(layer).ellipse([cx - r, cy - r, cx + r, cy + r], fill=peak)
    return layer.filter(ImageFilter.GaussianBlur(r * 0.55))


def glyph_mask(scale: float = 1.0, offset_y: float = 0.0) -> Image.Image:
    """
    The mark: a U whose bowl comes to a point, which makes it read as a down arrow too.

    The first version was an arrow dropping into a tray — the same picture every downloader
    uses. This one carries the initial instead: one stroke, no tray, no art, and it still says
    "down" because the bottom is a point rather than a curve.
    """
    mask = Image.new("L", (S, S), 0)
    draw = ImageDraw.Draw(mask)

    cx = 512.0
    cy = 512.0 + offset_y

    def x(v: float) -> float:
        return scaled(cx + v * scale)

    def y(v: float) -> float:
        return scaled(cy + v * scale)

    stroke = int(round(scaled(112 * scale)))
    half = 177.0
    draw.line(
        [(x(-half), y(-244)), (x(-half), y(22)), (x(0), y(236)), (x(half), y(22)), (x(half), y(-244))],
        fill=255,
        width=stroke,
        joint="curve",
    )
    # Square-ish ends, softened just enough not to look cut off at 48dp.
    for end_x, end_y in ((-half, -244), (half, -244)):
        radius = stroke / 2
        draw.ellipse(
            [x(end_x) - radius, y(end_y) - radius, x(end_x) + radius, y(end_y) + radius],
            fill=255,
        )
    return mask


def glyph_paint(mask: Image.Image) -> Image.Image:
    """The mark, filled with a barely-there top-to-bottom white."""
    gradient = vertical_gradient(MARK_LIGHT, MARK_DEEP)
    return Image.composite(gradient, Image.new("RGB", (S, S), (0, 0, 0)), mask)


def compose_tile() -> Image.Image:
    """The full square icon: matte black tile, one crisp white mark."""
    tile = vertical_gradient(TILE_TOP, TILE_BOTTOM)

    mark = glyph_mask()
    paint = glyph_paint(mark)

    # A single soft halo at low strength. Enough to keep the white from looking pasted on,
    # nowhere near bright enough to read as a glow.
    halo = mark.filter(ImageFilter.GaussianBlur(scaled(26)))
    tile = Image.composite(
        Image.new("RGB", (S, S), GLOW), tile, halo.point(lambda v: int(v * 0.09))
    )

    tile.paste(paint, (0, 0), mark)

    # Inner edge: one pixel of light along the top of the tile, so a black square does not sit
    # completely flat against a black launcher.
    edge = Image.new("L", (S, S), 0)
    ImageDraw.Draw(edge).line(
        [(scaled(26), scaled(3)), (S - scaled(26), scaled(3))], fill=255, width=int(scaled(3))
    )
    edge = edge.filter(ImageFilter.GaussianBlur(scaled(6)))
    tile = Image.composite(
        Image.new("RGB", (S, S), (255, 255, 255)), tile, edge.point(lambda v: int(v * 0.10))
    )

    out = tile.convert("RGBA")
    out.putalpha(rounded_mask(232))
    return out.resize((SIZE, SIZE), Image.LANCZOS)


def compose_foreground() -> Image.Image:
    """Adaptive foreground: the mark alone, kept inside the safe circle."""
    mark = glyph_mask(scale=0.94)
    paint = glyph_paint(mark)

    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    halo = mark.filter(ImageFilter.GaussianBlur(scaled(20)))
    layer.paste(
        Image.new("RGBA", (S, S), GLOW + (255,)),
        (0, 0),
        halo.point(lambda v: int(v * 0.10)),
    )
    layer.paste(paint.convert("RGBA"), (0, 0), mark)
    return layer.resize((SIZE, SIZE), Image.LANCZOS)


def compose_background() -> Image.Image:
    """Adaptive background: full bleed black, no corners; the launcher supplies the mask."""
    tile = vertical_gradient(TILE_TOP, TILE_BOTTOM)
    return tile.convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)


def write(image: Image.Image, path: pathlib.Path, size: int, round_mask: bool = False) -> None:
    scaled_image = image.resize((size, size), Image.LANCZOS)
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        scaled_image.putalpha(mask)
    path.parent.mkdir(parents=True, exist_ok=True)
    scaled_image.save(path, "PNG", optimize=True)


def main() -> None:
    tile = compose_tile()
    foreground = compose_foreground()
    background = compose_background()

    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    layers = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

    for density, size in legacy.items():
        write(tile, RES / f"mipmap-{density}" / "ic_launcher.png", size)
        write(tile, RES / f"mipmap-{density}" / "ic_launcher_round.png", size, round_mask=True)

    for density, size in layers.items():
        write(foreground, RES / f"mipmap-{density}" / "ic_launcher_foreground.png", size)
        write(background, RES / f"mipmap-{density}" / "ic_launcher_background.png", size)

    # A large copy for the repository, the About screen source art and store listings.
    (ROOT / "icon").mkdir(parents=True, exist_ok=True)
    tile.save(ROOT / "icon" / "uu-downloader-1024.png", "PNG")

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = RES / "mipmap-anydpi-v26" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(adaptive, encoding="utf-8")

    print("wrote launcher assets")


if __name__ == "__main__":
    main()
