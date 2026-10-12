#!/usr/bin/env python3
"""Find a person's face in every frame of a clip, for a pack's "player_face" track.

Unlike track_spot.py, which follows texture, this runs a face detector
(OpenCV's YuNet) on each animation frame, so it copes with someone moving,
turning and gesturing. Frames where no face is found (their back is turned,
say) get null, which hides the player's face there. The face's tilt comes
from the line between the eyes. Writes JSON for make_totem_pack.sh
--player-face, in the pack's coordinates (fractions of the cropped frame).

Needs numpy and opencv-python(-headless) 4.8 or newer. The detector's model
(230 KB) is downloaded once into ~/.cache/trollingofundying/.

  track_face.py VIDEO --start 58.0 --duration 4.7 --crop 880:720:300:0 --out track.json
"""
import argparse
import json
import subprocess
import urllib.request
from pathlib import Path

import cv2
import numpy as np

MODEL_URL = "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
MAX_TILT = 25.0
MODEL = Path.home() / ".cache" / "trollingofundying" / "face_detection_yunet_2023mar.onnx"


def model_path():
    if not MODEL.exists():
        MODEL.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading the face detector to {MODEL}...")
        urllib.request.urlretrieve(MODEL_URL, MODEL)
    return str(MODEL)


def read_frames(video, start, duration, fps):
    """The frames make_totem_pack.sh will use (same -ss, -t and fps), in color."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                            "-of", "csv=p=0", video], capture_output=True, text=True, check=True).stdout.strip()
    width, height = [int(v) for v in probe.split(",")]
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(start), "-t", str(duration), "-i", video,
                          "-vf", f"fps={fps}", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.uint8).reshape(-1, height, width, 3), width, height


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("video")
    parser.add_argument("--start", type=float, required=True)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--fps", type=float, default=20, help="animation frame rate (make_totem_pack.sh --fps)")
    parser.add_argument("--crop", help="W:H:X:Y, the same crop as make_totem_pack.sh --crop")
    parser.add_argument("--min-score", type=float, default=0.8, help="how sure the detector must be (0-1)")
    parser.add_argument("--scale", type=float, default=1.0, help="player face size relative to the detected face")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    frames, width, height = read_frames(args.video, args.start, args.duration, args.fps)
    crop_w, crop_h, crop_x, crop_y = [float(v) for v in args.crop.split(":")] if args.crop else (width, height, 0, 0)
    detector = cv2.FaceDetectorYN.create(model_path(), "", (width, height), args.min_score, 0.3, 5000)

    found, previous = [], None
    for frame in frames:
        _, faces = detector.detect(frame)
        face = None
        if faces is not None:
            # The biggest face, or once one is being followed, the one nearest to it.
            candidates = sorted(faces, key=lambda f: -f[2] * f[3])
            if previous is not None:
                big = [f for f in candidates if f[3] > 0.6 * previous[3]] or candidates
                face = min(big, key=lambda f: np.hypot(f[0] + f[2] / 2 - previous[0] - previous[2] / 2,
                                                       f[1] + f[3] / 2 - previous[1] - previous[3] / 2))
            else:
                face = candidates[0]
            previous = face
        found.append(face)

    # Smooth over three frames, so the face doesn't jitter with the detector.
    spots = []
    for i, face in enumerate(found):
        if face is None:
            spots.append(None)
            continue
        near = [f for f in found[max(0, i - 1):i + 2] if f is not None]
        x, y, w, h = (float(v) for v in np.mean([f[:4] for f in near], axis=0))
        # Tilt from the eyes (right eye x, y, left eye x, y), capped: while a head turns side-on
        # the eyes line up vertically and would otherwise spin the face.
        tilt = float(np.mean([np.clip(np.degrees(np.arctan2(f[7] - f[5], f[6] - f[4])), -MAX_TILT, MAX_TILT) for f in near]))
        size = max(w, h) * args.scale
        spots.append({
            "x": round((x + w / 2 - crop_x) / crop_w, 4),
            "y": round((y + h / 2 - crop_y) / crop_h, 4),
            "size": round(size / crop_h, 4),
            "rotation": round(tilt, 2),
        })
    Path(args.out).write_text(json.dumps(spots, indent=1))
    print(f"{len(spots)} frames, face in {sum(s is not None for s in spots)} -> {args.out}")


if __name__ == "__main__":
    main()
