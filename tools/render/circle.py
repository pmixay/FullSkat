#!/usr/bin/env python3
"""Cut square screenshots to the round display: transparent outside a circle of the full width.

    python3 tools/render/circle.py app/build/screenshots design/renders/app

Takes an input directory (or files) and an output directory. The Robolectric screenshots in
app/build/screenshots are the full square window of the 216 dp / 432 px display.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

*sources, out = sys.argv[1:]
out = Path(out)
out.mkdir(parents=True, exist_ok=True)
files = [f for s in sources for f in (sorted(Path(s).glob("*.png")) if Path(s).is_dir() else [Path(s)])]
for f in files:
    im = Image.open(f).convert("RGBA")
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, im.width - 1, im.height - 1), fill=255)
    im.putalpha(mask)
    im.save(out / f.name)
    print(out / f.name, im.size)
