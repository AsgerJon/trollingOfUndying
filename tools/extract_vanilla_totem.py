#!/usr/bin/env python3
"""Cut the vanilla totem out of a capture and turn it into a resource pack.

Reads the folder written by `./gradlew runClientGameTest`
(run/totem_capture/vanilla/ by default). Every frame_NN.png has a matching
background_NN.png: the same frozen tick rendered without the totem. Pixels
that differ between the two are the totem; everything else becomes
transparent. Writes, inside the capture folder:

  cutouts/frame_NN.png       each totem cropped to its own bounding box (RGBA)
  cutouts/path.csv           per tick: bounding box, center and size on screen
  vanilla_totem_pack.zip     a Trolling of Undying pack that replays the cutouts
                             along the measured path, to check against vanilla
                             (animation "vanilla_totem")

Usage: extract_vanilla_totem.py [CAPTURE_FOLDER]
"""
import csv
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

PROJECT = Path(__file__).resolve().parent.parent
DEFAULT_CAPTURE = PROJECT / "run" / "totem_capture" / "vanilla"
NAMESPACE = "trollingofundying"
ANIMATION_ID = "vanilla_totem"


def load_rgb(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def main():
    capture = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CAPTURE
    info = json.loads((capture / "capture.json").read_text())
    screen_w, screen_h = info["window_width"], info["window_height"]
    out = capture / "cutouts"
    out.mkdir(exist_ok=True)

    cutouts = []  # (tick, RGBA image, x0, y0)
    for frame in info["frames"]:
        tick = 40 - frame["ticks_left"]
        if "background" not in frame:
            sys.exit(f"{capture} has no background frames; rerun ./gradlew runClientGameTest")
        shot = Image.open(capture / frame["file"]).convert("RGBA")
        changed = (load_rgb(capture / frame["file"]) != load_rgb(capture / frame["background"])).any(axis=2)
        if not changed.any():
            print(f"tick {tick}: totem not visible, skipped")
            continue
        ys, xs = np.nonzero(changed)
        x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
        rgba = np.array(shot)
        rgba[..., 3] = np.where(changed, 255, 0)
        cutout = Image.fromarray(rgba[y0:y1, x0:x1])
        cutout.save(out / f"frame_{tick:02d}.png")
        cutouts.append((tick, cutout, int(x0), int(y0)))

    with open(out / "path.csv", "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["tick", "x0", "y0", "width", "height", "center_x", "center_y"])
        for tick, cutout, x0, y0 in cutouts:
            w, h = cutout.size
            writer.writerow([tick, x0, y0, w, h, x0 + w / 2, y0 + h / 2])

    # The pack draws every frame at one aspect ratio, so each cutout goes in the
    # middle of a canvas as big as the largest one. The canvas is then drawn at
    # 1:1 scale, centered where that tick's cutout was.
    canvas_w = max(c.size[0] for _, c, _, _ in cutouts)
    canvas_h = max(c.size[1] for _, c, _, _ in cutouts)
    pack = capture / "vanilla_totem_pack.zip"
    keyframes = []
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as z:
        for tick, cutout, x0, y0 in cutouts:
            w, h = cutout.size
            left, top = (canvas_w - w) // 2, (canvas_h - h) // 2
            canvas = Image.new("RGBA", (canvas_w, canvas_h), (0, 0, 0, 0))
            canvas.paste(cutout, (left, top))
            png = io.BytesIO()
            canvas.save(png, "PNG")
            z.writestr(f"assets/{NAMESPACE}/textures/totem_animations/{ANIMATION_ID}/frame_{tick:02d}.png", png.getvalue())
            keyframes.append({
                "time": tick / 40,
                "x": round((x0 - left + canvas_w / 2) / screen_w, 5),
                "y": round((y0 - top + canvas_h / 2) / screen_h, 5),
            })
        keyframes[0]["size"] = round(canvas_h / screen_h, 5)
        animation = {
            "duration_ticks": 40,
            "frames": f"{NAMESPACE}:textures/totem_animations/{ANIMATION_ID}",
            "fps": 20,
            "loop_frames": False,
            "hide_vanilla_item": True,
            "hide_vanilla_particles": False,
            "mute_vanilla_sound": False,
            "keyframes": keyframes,
        }
        z.writestr(f"assets/{NAMESPACE}/totem_animations/{ANIMATION_ID}.json", json.dumps(animation, indent=2))
        z.writestr("pack.mcmeta", json.dumps({
            "pack": {"description": "Vanilla totem, replayed", "min_format": 97, "max_format": 999}
        }, indent=2))

    print(f"{len(cutouts)} cutouts in {out}")
    print(f"canvas {canvas_w}x{canvas_h} px on a {screen_w}x{screen_h} screen")
    print(f"pack: {pack}")


if __name__ == "__main__":
    main()
