#!/usr/bin/env python3
"""Keyframes that keep an animation centered and pulse its size with the music.

Finds the tempo and the beats in a sound file, then prints a "keyframes" list
for totem_animation.json: on every beat the frame swells (more on stronger
beats) and eases back before the next one. Used by make_totem_pack.sh
--motion pulse. Needs numpy and ffmpeg.

  beat_pulse.py SOUND.ogg --duration-ticks 81 --offset-ticks 8
"""
import argparse
import json
import subprocess

import numpy as np

RATE = 22050
HOP = 256


def onset_strength(path):
    """How much new sound starts at each moment (spectral flux), and when those moments are."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    audio = np.frombuffer(raw, dtype=np.float32)
    size = 1024
    window = np.hanning(size)
    spectra = np.log1p(np.array([np.abs(np.fft.rfft(audio[i:i + size] * window))
                                 for i in range(0, len(audio) - size, HOP)]))
    flux = np.maximum(0, np.diff(spectra, axis=0)).sum(axis=1)
    flux = (flux - flux.mean()) / (flux.std() or 1.0)
    times = (np.arange(len(flux)) + 1) * HOP / RATE + size / 2 / RATE
    return flux, times


def find_beats(flux, times):
    """The beat times: tempo from the autocorrelation (70-180 bpm), phase from where the onsets line up best."""
    correlation = np.correlate(flux, flux, "full")[len(flux) - 1:]
    lags = np.arange(len(correlation)) * HOP / RATE
    usable = (lags > 60 / 180) & (lags < 60 / 70)
    period = lags[usable][np.argmax(correlation[usable])]
    phases = np.linspace(0, period, 100, endpoint=False)
    scores = [np.interp(np.arange(p, times[-1], period), times, flux).sum() for p in phases]
    phase = phases[int(np.argmax(scores))]
    return np.arange(phase, times[-1], period), period


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sound")
    parser.add_argument("--duration-ticks", type=int, required=True, help="the animation's duration_ticks")
    parser.add_argument("--offset-ticks", type=int, default=0, help="ticks before the sound starts (vanilla_sound_ticks)")
    parser.add_argument("--size", type=float, default=0.6, help="resting size, as a fraction of the screen height")
    parser.add_argument("--strength", type=float, default=0.15, help="how much the strongest beat swells it (0.15 = 15%%)")
    parser.add_argument("--x", type=float, default=0.5)
    parser.add_argument("--y", type=float, default=0.5)
    args = parser.parse_args()

    flux, times = onset_strength(args.sound)
    beats, period = find_beats(flux, times)
    # Each beat's strength: the loudest onset within 50 ms of it, scaled so the strongest beat is 1.
    strengths = np.array([flux[np.abs(times - b) < 0.05].max(initial=0.0) for b in beats])
    strengths = np.clip(strengths / (strengths.max() or 1.0), 0.0, 1.0)

    def progress(seconds):
        return (args.offset_ticks + seconds * 20) / args.duration_ticks

    rest = round(args.size, 4)
    keyframes = [{"time": 0.0, "x": args.x, "y": args.y, "size": rest, "rotation": 0, "alpha": 1}]
    for beat, strength in zip(beats, strengths):
        peak = round(args.size * (1 + args.strength * (0.35 + 0.65 * strength)), 4)
        rise, settle = progress(beat - 0.03), progress(beat + period * 0.6)
        if rise < keyframes[-1]["time"] or settle > 1.0:
            continue
        keyframes.append({"time": round(rise, 4), "size": rest})
        keyframes.append({"time": round(progress(beat), 4), "size": peak})
        keyframes.append({"time": round(settle, 4), "size": rest, "easing": "ease_out"})
    keyframes.append({"time": 1.0, "size": rest})
    print(json.dumps(keyframes, indent=2))


if __name__ == "__main__":
    main()
