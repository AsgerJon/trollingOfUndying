#!/usr/bin/env python3
"""Cut the people out of a clip: transparent PNG frames for make_totem_pack.sh --frames-dir.

For clips without a green screen. Every frame goes through a person
segmentation model (U2-Net human segmentation, from the rembg project), and
everything that isn't a person becomes transparent. People cut off by the
frame's bottom edge (a singer filmed from the waist up, say) can be faded out
there with --fade-bottom, so they don't end in a hard line.

Needs numpy, Pillow and onnxruntime. The model (176 MB) is downloaded once
into ~/.cache/trollingofundying/.

  segment_person.py VIDEO --start 44.28 --duration 2.8 --crop 2280:2160:1200:0 --out frames/
"""
import argparse
import subprocess
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime
from PIL import Image

MODEL_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_human_seg.onnx"
MODEL = Path.home() / ".cache" / "trollingofundying" / "u2net_human_seg.onnx"
SIZE = 320  # the model's input size


def model_path():
    if not MODEL.exists():
        MODEL.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading the segmentation model to {MODEL}...")
        urllib.request.urlretrieve(MODEL_URL, MODEL)
    return str(MODEL)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("video")
    parser.add_argument("--start", type=float, required=True)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--fps", type=float, default=20)
    parser.add_argument("--crop", help="W:H:X:Y crop (ffmpeg syntax) before cutting out")
    parser.add_argument("--height", type=int, default=384, help="frame height in pixels")
    parser.add_argument("--fade-bottom", type=float, default=0.0, help="fade out the bottom this fraction of the frame")
    parser.add_argument("--out", required=True, help="folder for frame_0001.png, ...")
    args = parser.parse_args()

    filters = f"fps={args.fps}" + (f",crop={args.crop}" if args.crop else "") + f",scale=-2:{args.height}:flags=lanczos"
    probe = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(args.start), "-t", "0.1", "-i", args.video, "-vf", filters,
                            "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"], capture_output=True, check=True).stdout
    from io import BytesIO
    width, height = Image.open(BytesIO(probe)).size
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(args.start), "-t", str(args.duration), "-i", args.video,
                          "-vf", filters, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3)

    session = onnxruntime.InferenceSession(model_path(), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    fade = np.ones(height, dtype=np.float32)
    if args.fade_bottom > 0:
        rows = int(height * args.fade_bottom)
        fade[height - rows:] = np.linspace(1.0, 0.0, rows)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for i, frame in enumerate(frames, 1):
        image = Image.fromarray(frame)
        x = np.asarray(image.resize((SIZE, SIZE), Image.BILINEAR), dtype=np.float32) / 255.0
        x = ((x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32)
        mask = session.run(None, {input_name: x})[0][0, 0]
        mask = (mask - mask.min()) / (mask.max() - mask.min() + 1e-8)
        alpha = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((width, height), Image.BILINEAR),
                           dtype=np.float32) / 255.0
        alpha *= fade[:, None]
        rgba = np.dstack([frame, (alpha * 255).astype(np.uint8)])
        Image.fromarray(rgba, "RGBA").save(out / f"frame_{i:04d}.png")
    print(f"{len(frames)} frames ({width}x{height}) -> {out}")


if __name__ == "__main__":
    main()
