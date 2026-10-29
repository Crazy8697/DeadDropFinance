# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 DarkRelay
"""Rebuild the desktop program's icons from the app icon, design/deaddropfinance-icon.svg:

    desktop/icons/deaddropfinance.ico                   exe, installer and shortcuts (256, 128, 64, 48, 32, 24, 16)
    desktop/src/main/resources/deaddropfinance-256.png  the window icon

    python desktop/tools/make_icon.py

Each layer is rendered by Inkscape at its exact pixel size ($env:INKSCAPE, PATH, or Program Files).
"""
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DESKTOP = os.path.dirname(HERE)
SVG = os.path.join(os.path.dirname(DESKTOP), "design", "deaddropfinance-icon.svg")
ICO = os.path.join(DESKTOP, "icons", "deaddropfinance.ico")
PNG = os.path.join(DESKTOP, "src", "main", "resources", "deaddropfinance-256.png")
SIZES = [256, 128, 64, 48, 32, 24, 16]


def find_inkscape():
    for c in (os.environ.get("INKSCAPE"), shutil.which("inkscape"),
              os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Inkscape", "bin", "inkscape.exe")):
        if c and os.path.isfile(c):
            return c
    sys.exit("make_icon: Inkscape not found (set INKSCAPE)")


def main():
    ink = find_inkscape()
    tmp = tempfile.mkdtemp(prefix="ddf-icon-")
    try:
        layers = []
        for s in SIZES:
            out = os.path.join(tmp, f"{s}.png")
            subprocess.run([ink, SVG, "--export-type=png", f"--export-filename={out}", f"--export-width={s}",
                            f"--export-height={s}"], check=True, capture_output=True,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            layers.append(Image.open(out).convert("RGBA"))
        os.makedirs(os.path.dirname(ICO), exist_ok=True)
        os.makedirs(os.path.dirname(PNG), exist_ok=True)
        layers[0].save(ICO, format="ICO", sizes=[(s, s) for s in SIZES], append_images=layers[1:])
        layers[0].save(PNG, format="PNG")
        print(f"wrote {os.path.relpath(ICO, DESKTOP)} and {os.path.relpath(PNG, DESKTOP)}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
