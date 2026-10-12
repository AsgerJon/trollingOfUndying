#!/usr/bin/env python3
"""Follow a spot on something moving in a video, for a pack's "player_face" track.

Give it the spot on the first frame (where the face goes, and how big) and a
box around the thing it sits on (a coffin, say). It tracks the box's texture
from frame to frame, works out how the thing moves, grows and tilts, and
carries the spot along. Writes one spot per animation frame, in the same
coordinates the pack uses (fractions of the cropped frame), as JSON for
make_totem_pack.sh --player-face. Also writes a preview sheet with the spot
drawn on every few frames, to check it stays put.

Needs numpy, opencv-python(-headless) and Pillow.

  track_spot.py VIDEO --start 81.34 --duration 3.66 --spot 900,385,125 \\
      --region 615,165,1200,600 --crop 1500:1080:40:0 --out track.json
"""
import argparse
import json
import subprocess
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

SCALE = 0.5  # track at half size: plenty for a coffin, and four times faster


def read_frames(video, start, duration):
    """Every frame of the segment at the video's own frame rate, as half-size grayscale."""
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate",
         "-of", "csv=p=0", video], capture_output=True, text=True, check=True).stdout.strip().split(",")
    width, height = int(probe[0]), int(probe[1])
    num, den = probe[2].split("/")
    fps = float(num) / float(den)
    w, h = int(width * SCALE), int(height * SCALE)
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(duration), "-i", video,
         "-vf", f"scale={w}:{h},format=gray", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w), fps


def seed_points(gray, polygon):
    mask = np.zeros_like(gray)
    cv2.fillPoly(mask, [polygon.astype(np.int32)], 255)
    points = cv2.goodFeaturesToTrack(gray, maxCorners=300, qualityLevel=0.01, minDistance=5, mask=mask)
    return points if points is not None else np.empty((0, 1, 2), np.float32)


def track(frames, region):
    """The similarity transform (2x3, first frame to this one) for every frame."""
    x0, y0, x1, y1 = [v * SCALE for v in region]
    polygon = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], np.float32)
    total = np.eye(3)
    transforms = [total[:2].copy()]
    points = seed_points(frames[0], polygon)
    for previous, current in zip(frames, frames[1:]):
        moved, status, _ = cv2.calcOpticalFlowPyrLK(previous, current, points, None, winSize=(21, 21), maxLevel=3)
        good_old, good_new = points[status == 1], moved[status == 1]
        step, inliers = cv2.estimateAffinePartial2D(good_old, good_new, method=cv2.RANSAC, ransacReprojThreshold=2.0)
        if step is None:
            step = np.array([[1, 0, 0], [0, 1, 0]], np.float64)
        total = np.vstack([step, [0, 0, 1]]) @ total
        transforms.append(total[:2].copy())
        points = good_new[inliers.ravel() == 1].reshape(-1, 1, 2) if inliers is not None else good_new.reshape(-1, 1, 2)
        if len(points) < 40:
            # Lost too many corners: look for fresh ones on the box where it is now.
            box = cv2.transform(polygon.reshape(-1, 1, 2), total[:2]).reshape(-1, 2)
            points = np.vstack([points, seed_points(current, box)]).astype(np.float32)
    return transforms


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("video")
    parser.add_argument("--start", type=float, required=True)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--fps", type=float, default=20, help="animation frame rate (make_totem_pack.sh --fps)")
    parser.add_argument("--spot", required=True, help="X,Y,SIZE of the face on the first frame, in video pixels")
    parser.add_argument("--region", required=True, help="X0,Y0,X1,Y1 box around what the face sits on, first frame")
    parser.add_argument("--crop", help="W:H:X:Y, the same crop as make_totem_pack.sh --crop")
    parser.add_argument("--out", required=True, help="track JSON to write")
    parser.add_argument("--preview", help="PNG sheet with the tracked spot drawn on, to check it")
    args = parser.parse_args()

    spot_x, spot_y, spot_size = [float(v) for v in args.spot.split(",")]
    region = [float(v) for v in args.region.split(",")]
    frames, video_fps = read_frames(args.video, args.start, args.duration)
    transforms = track(frames, region)
    height, width = frames[0].shape
    width, height = width / SCALE, height / SCALE
    crop_w, crop_h, crop_x, crop_y = [float(v) for v in args.crop.split(":")] if args.crop else (width, height, 0, 0)

    track_out, preview = [], []
    count = int(round(args.duration * args.fps))
    for i in range(count):
        index = min(int(round(i / args.fps * video_fps)), len(transforms) - 1)
        m = transforms[index]
        x, y = m @ np.array([spot_x * SCALE, spot_y * SCALE, 1.0]) / SCALE
        scale = float(np.hypot(m[0, 0], m[1, 0]))
        rotation = float(np.degrees(np.arctan2(m[1, 0], m[0, 0])))
        size = spot_size * scale
        track_out.append({
            "x": round((x - crop_x) / crop_w, 4),
            "y": round((y - crop_y) / crop_h, 4),
            "size": round(size / crop_h, 4),
            "rotation": round(rotation, 2),
        })
        preview.append((index, x, y, size, rotation))
    Path(args.out).write_text(json.dumps(track_out, indent=1))
    print(f"{len(track_out)} spots -> {args.out}")

    if args.preview:
        picks = preview[::max(1, len(preview) // 12)][:12]
        tiles = []
        for index, x, y, size, rotation in picks:
            tile = Image.fromarray(frames[index]).convert("RGB")
            draw = ImageDraw.Draw(tile)
            half = size * SCALE / 2
            corners = np.array([[-half, -half], [half, -half], [half, half], [-half, half]])
            turn = np.radians(rotation)
            rot = np.array([[np.cos(turn), -np.sin(turn)], [np.sin(turn), np.cos(turn)]])
            corners = corners @ rot.T + [x * SCALE, y * SCALE]
            draw.polygon([tuple(c) for c in corners], outline=(255, 0, 0), width=4)
            if args.crop:
                draw.rectangle([crop_x * SCALE, crop_y * SCALE, (crop_x + crop_w) * SCALE, (crop_y + crop_h) * SCALE],
                               outline=(0, 255, 255), width=2)
            tiles.append(tile.resize((tile.width // 2, tile.height // 2)))
        w, h = tiles[0].size
        sheet = Image.new("RGB", (w * 4, h * ((len(tiles) + 3) // 4)))
        for i, tile in enumerate(tiles):
            sheet.paste(tile, ((i % 4) * w, (i // 4) * h))
        sheet.save(args.preview)
        print(f"preview -> {args.preview}")


if __name__ == "__main__":
    main()
