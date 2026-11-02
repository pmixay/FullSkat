#!/usr/bin/env python3
"""Lay circular renders out on one contact sheet so designs can be compared side by side.

    python3 tools/render/sheet.py out.png --cols 4 --title "Design A" renders/a__*.png

Every input is the 432 px circle from render.js; the sheet puts a dark backdrop behind them and
labels each with its file name.
"""
import argparse
from PIL import Image, ImageDraw

p = argparse.ArgumentParser()
p.add_argument("out")
p.add_argument("images", nargs="+")
p.add_argument("--cols", type=int, default=4)
p.add_argument("--title", default="")
p.add_argument("--gap", type=int, default=28)
a = p.parse_args()

tiles = [Image.open(f).convert("RGBA") for f in a.images]
d = tiles[0].width
rows = -(-len(tiles) // a.cols)
head = 64 if a.title else 0
label = 30
w = a.cols * d + (a.cols + 1) * a.gap
h = head + rows * (d + label) + (rows + 1) * a.gap
sheet = Image.new("RGBA", (w, h), (24, 24, 28, 255))
draw = ImageDraw.Draw(sheet)
if a.title:
    draw.text((a.gap, 22), a.title, fill=(240, 240, 240, 255))
for i, (tile, name) in enumerate(zip(tiles, a.images)):
    r, c = divmod(i, a.cols)
    x = a.gap + c * (d + a.gap)
    y = head + a.gap + r * (d + label + a.gap)
    draw.ellipse((x - 3, y - 3, x + d + 3, y + d + 3), outline=(70, 70, 76, 255), width=2)  # bezel hint
    sheet.alpha_composite(tile, (x, y))
    draw.text((x, y + d + 8), name.rsplit("/", 1)[-1].removesuffix(".png"), fill=(160, 160, 168, 255))
sheet.convert("RGB").save(a.out)
print(f"{a.out}  {w}x{h}px, {len(tiles)} renders")
