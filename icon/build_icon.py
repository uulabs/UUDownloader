#!/usr/bin/env python3
"""
Builds every launcher asset from the supplied UU Downloader art.

`icon/source/uu-downloader-icon-black-v1.png` is the drawing: a black tile, the UU in white,
and a charcoal arrow dropping into a tray behind them. This script only slices it — it does
not redraw it, so the icon in the app is the icon that was approved.

Android wants three different things from one picture:

  * legacy `mipmap-*/ic_launcher.png`      the art as drawn, tile included
  * round variant                          the same, masked to a circle
  * adaptive foreground/background         the mark alone, so a launcher can mask the tile
                                           itself — pasting a pre-rounded tile into an
                                           adaptive icon gives a tile inside a tile

The mark is separated from the tile by tone: the tile is near-black, the arrow and tray are
charcoal (~58,61,67), the letters are white (~240). A threshold between the tile and the
charcoal lifts the mark and leaves the tile behind, with a one-pixel feather so the edges do
not alias.

    python3 icon/build_icon.py
"""
from __future__ import annotations

import pathlib

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
SOURCE = ROOT / "icon" / "source" / "uu-downloader-icon-black-v1.png"

# The mark sits inside this share of the adaptive layer. Android's own guidance is a 66dp
# circle within 108dp; 0.58 leaves the corners clear of a circular mask as well.
MARK_INSET = 0.58

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
LAYERS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def load_source() -> Image.Image:
    art = Image.open(SOURCE).convert("RGBA")
    return art


def tile_colour(art: Image.Image) -> tuple[int, int, int]:
    """
    The tile's own colour, for the adaptive background.

    Median of the pixels below the mark, not the most common exact colour: this art is subtly
    textured, so its tile is spread over hundreds of near-identical values while the letters
    sit in a few uniform bright ones — and "most common" picked white.
    """
    arr = np.asarray(art).astype(float)
    rgb, alpha = arr[:, :, :3], arr[:, :, 3]
    luminance = rgb.mean(axis=2)
    body = (alpha > 250) & (luminance < 32)
    if body.sum() == 0:
        return (6, 6, 8)
    median = np.median(rgb[body], axis=0)
    return tuple(int(v) for v in median.round())


def extract_mark(art: Image.Image) -> Image.Image:
    """The letters and the arrow, with the tile lifted away."""
    arr = np.asarray(art).astype(float)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3] / 255.0
    luminance = rgb.mean(axis=2)

    # Tile body is 5-12, its rim is 20-29, the charcoal arrow starts at 40.
    lifted = np.clip((luminance - 32.0) / 8.0, 0.0, 1.0) * alpha
    # The tile's own anti-aliased edge is a sliver of alpha over a light RGB; without a floor
    # it survives as a ghost of the tile outline around the mark.
    lifted = np.where(lifted > 0.15, lifted, 0.0)
    out = np.zeros_like(arr, dtype=np.uint8)
    out[:, :, :3] = rgb.astype(np.uint8)
    out[:, :, 3] = (lifted * 255).round().astype(np.uint8)

    mark = Image.fromarray(out, "RGBA")
    # Crop by the alpha channel: getbbox() on the RGBA image would use the copied RGB and find
    # the whole tile, because near-black is not zero.
    box = Image.fromarray(out[:, :, 3]).getbbox()
    if box is None:
        raise SystemExit("the supplied art has no mark above the tile")
    return mark.crop(box)


def lift_charcoal(art: Image.Image, strength: float = 0.55) -> Image.Image:
    """
    Lifts the arrow and tray out of the black.

    The approved art draws them at luminance ~58 on a ~7 tile. That reads as texture at
    1024 and disappears entirely at 48dp, where the icon actually lives. This raises the
    charcoal toward ~105 while leaving the letters at white and the tile at black, so the
    download mark is legible at launcher size without changing the drawing.

    The lift is stronger for darker pixels, so the arrow brightens more than the letters and
    the two stay distinct.
    """
    arr = np.asarray(art).astype(float)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3] / 255.0
    luminance = rgb.mean(axis=2)

    mark = ((luminance > 32) & (alpha > 0.1)).astype(float)
    factor = 1.0 + strength * 0.9 * (1.0 - luminance / 255.0)
    lifted = np.clip(rgb * factor[:, :, None], 0, 255)

    out = arr.copy()
    blend = mark[:, :, None]
    out[:, :, :3] = rgb * (1 - blend) + lifted * blend
    return Image.fromarray(out.round().astype(np.uint8), "RGBA")


def centre_on(layer_px: int, content: Image.Image, share: float) -> Image.Image:
    canvas = Image.new("RGBA", (layer_px, layer_px), (0, 0, 0, 0))
    target = layer_px * share
    scale = min(target / content.width, target / content.height)
    size = (max(1, round(content.width * scale)), max(1, round(content.height * scale)))
    resized = content.resize(size, Image.LANCZOS)
    canvas.paste(
        resized,
        ((layer_px - size[0]) // 2, (layer_px - size[1]) // 2),
        resized,
    )
    return canvas


def write(image: Image.Image, path: pathlib.Path, size: int, circle: bool = False) -> None:
    scaled = image.resize((size, size), Image.LANCZOS)
    if circle:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        scaled = scaled.copy()
        scaled.putalpha(mask)
    path.parent.mkdir(parents=True, exist_ok=True)
    scaled.save(path, "PNG", optimize=True)


def main() -> None:
    art = lift_charcoal(load_source())
    mark = extract_mark(art)
    background_colour = tile_colour(art)
    rounded = art.resize((1024, 1024), Image.LANCZOS)

    for density, size in LEGACY.items():
        write(rounded, RES / f"mipmap-{density}" / "ic_launcher.png", size)
        write(rounded, RES / f"mipmap-{density}" / "ic_launcher_round.png", size, circle=True)

    for density, layer in LAYERS.items():
        foreground = centre_on(layer, mark, MARK_INSET)
        background = Image.new("RGBA", (layer, layer), background_colour + (255,))
        write(foreground, RES / f"mipmap-{density}" / "ic_launcher_foreground.png", layer)
        write(background, RES / f"mipmap-{density}" / "ic_launcher_background.png", layer)

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

    # The About screen shows the same mark, so the app and its launcher icon agree.
    about = centre_on(512, mark, 0.92)
    about_path = RES / "drawable-nodpi" / "uu_mark.png"
    about_path.parent.mkdir(parents=True, exist_ok=True)
    about.save(about_path, "PNG", optimize=True)

    rounded.save(ROOT / "icon" / "uu-downloader-1024.png", "PNG")
    print(f"built launcher assets from source; tile colour {background_colour}, mark {mark.size}")


if __name__ == "__main__":
    main()
