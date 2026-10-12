#!/usr/bin/env bash
# Turns a video clip into a Trolling of Undying resource pack.
#
#   tools/make_totem_pack.sh dancing.mp4 --start 43 --duration 2 --chroma 0x00ff00
#
# Writes a .zip you can drop straight into .minecraft/resourcepacks. The frames
# come from the video, the sound from the video's audio track (or --audio), and
# the motion path is a straight line from 819 px left of the screen's center
# to 819 px right of it (1.5 hotbars at GUI scale 3), which you can edit in
# assets/trollingofundying/totem_animations/ID.json afterwards. Every animation
# gets its own ID, so any number of them can be enabled together; the pops take
# turns through them, by --order (tools/combine_packs.py merges them into one pack).
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: make_totem_pack.sh VIDEO [options]

  --start SECONDS      where the clip starts in VIDEO           (default 0)
  --duration SECONDS   clip length; also the animation length   (default 2, vanilla is 2)
  --fps N              frames per second to extract             (default 20)
  --height PIXELS      frame height                             (default 256)
  --crop W:H:X:Y       crop the video first (ffmpeg crop syntax)
  --chroma COLOR       make this color transparent, e.g. 0x00ff00 for green screen
  --similarity F       how close to COLOR counts as background  (default 0.3)
  --shape SHAPE        rect, ellipse (an oval cut out of the frame) or soft_rect
                       (the whole frame, edges fading out)      (default rect)
  --feather F          softness of the edge, 0-1   (default 0.15 ellipse, 0.06 soft_rect)
  --alpha F            opacity of the animation, 0-1            (default 1)
  --vanilla-sound-ticks N  open with N ticks of the vanilla totem sound, then
                       fade it out and start the clip's sound   (default 0: off)
  --let-sound-finish   keep the sound playing after the animation ends
  --player-face FILE   draw the player's face along this track (from track_spot.py)
  --motion MOTION      line: straight across the center (default)
                       pulse: stay centered and swell on every beat of the sound
                       still: stay centered, like a cutscene
                       vanilla_totem: fly at the camera spinning, like the vanilla totem
  --line-direction D   ltr (left to right, default) or rtl, for --motion line
  --audio FILE         take the sound from FILE instead of VIDEO
  --audio-start SEC    where the sound starts in its file       (default: same as --start)
  --no-audio           make a silent animation
  --gain DB            make the sound louder (or quieter, if negative) by DB decibels
  --fade-out SEC       fade the sound out over its last SEC seconds (default 0.03)
  --frames-dir DIR     use these PNG frames (e.g. from segment_person.py) instead of
                       extracting them from VIDEO; VIDEO then only supplies the sound
  --name NAME          pack name shown in the game              (default "Totem Animation")
  --out FILE.zip       output file                              (default totem_animation_pack.zip)
  --id ID              the animation's name, a-z 0-9 _ only     (default: from --out)
  --order N            its place in the rotation of pops, lowest first
                       (default: none; those come after the numbered ones)
EOF
    exit "${1:-0}"
}

[[ $# -ge 1 ]] || usage 1
[[ $1 == -h || $1 == --help ]] && usage 0
video=$1; shift
start=0 duration=2 fps=20 height=256 crop= chroma= similarity=0.3 shape=rect feather= motion=line line_direction=ltr player_face= alpha=1.0 vanilla_sound_ticks=0 stop_sound=true gain=0 fade_out=0.03 frames_dir=
audio=$video audio_start= no_audio=0 name="Totem Animation" out=totem_animation_pack.zip id= order=
while [[ $# -gt 0 ]]; do
    case $1 in
        --start) start=$2; shift 2 ;;
        --duration) duration=$2; shift 2 ;;
        --fps) fps=$2; shift 2 ;;
        --height) height=$2; shift 2 ;;
        --crop) crop=$2; shift 2 ;;
        --chroma) chroma=$2; shift 2 ;;
        --similarity) similarity=$2; shift 2 ;;
        --shape) shape=$2; shift 2 ;;
        --feather) feather=$2; shift 2 ;;
        --motion) motion=$2; shift 2 ;;
        --line-direction) line_direction=$2; shift 2 ;;
        --player-face) player_face=$2; shift 2 ;;
        --alpha) alpha=$2; shift 2 ;;
        --vanilla-sound-ticks) vanilla_sound_ticks=$2; shift 2 ;;
        --let-sound-finish) stop_sound=false; shift ;;
        --gain) gain=$2; shift 2 ;;
        --fade-out) fade_out=$2; shift 2 ;;
        --frames-dir) frames_dir=$2; shift 2 ;;
        --audio) audio=$2; shift 2 ;;
        --audio-start) audio_start=$2; shift 2 ;;
        --no-audio) no_audio=1; shift ;;
        --name) name=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        --id) id=$2; shift 2 ;;
        --order) order=$2; shift 2 ;;
        -h|--help) usage 0 ;;
        *) echo "Unknown option: $1" >&2; usage 1 ;;
    esac
done
[[ -f $video ]] || { echo "No such video: $video" >&2; exit 1; }
for tool in ffmpeg ffprobe python3; do
    command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
if [[ -z $audio_start ]]; then
    # A separate audio file starts at its beginning unless told otherwise.
    [[ $audio == "$video" ]] && audio_start=$start || audio_start=0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
assets=$work/assets/trollingofundying
[[ -n $id ]] || id=$(basename "$out" .zip | tr 'A-Z' 'a-z' | tr -c 'a-z0-9_\n' '_')
[[ $id =~ ^[a-z0-9_]+$ ]] || { echo "--id may only use a-z, 0-9 and _: $id" >&2; exit 1; }
[[ -z $order || $order =~ ^-?[0-9]+$ ]] || { echo "--order must be a whole number: $order" >&2; exit 1; }
textures=$assets/textures/totem_animations/$id
mkdir -p "$textures" "$assets/sounds/totem_animations"

filters="fps=$fps"
[[ -n $crop ]] && filters+=",crop=$crop"
[[ -n $chroma ]] && filters+=",colorkey=$chroma:$similarity:0.1"
filters+=",scale=-2:$height:flags=lanczos,format=rgba"
case $shape in
    rect) ;;
    soft_rect)
        f=${feather:-0.06}
        filters+=",geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*clip(min(X,W-1-X)/($f*W),0,1)*clip(min(Y,H-1-Y)/($f*H),0,1)'" ;;
    ellipse)
        feather=${feather:-0.15}
        # d is 0 in the middle and 1 on the ellipse touching the frame's edges;
        # alpha fades from full to none over the outer `feather` of that range.
        d="pow((X-W/2)/(W/2),2)+pow((Y-H/2)/(H/2),2)"
        filters+=",geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*clip((1-($d))/$feather,0,1)'" ;;
    *) echo "Unknown --shape: $shape (use rect, ellipse or soft_rect)" >&2; exit 1 ;;
esac
if [[ -n $frames_dir ]]; then
    echo "Copying frames from $frames_dir..."
    cp "$frames_dir"/*.png "$textures/"
else
    echo "Extracting frames..."
    ffmpeg -v error -ss "$start" -t "$duration" -i "$video" -vf "$filters" \
        "$textures/frame_%04d.png"
fi
frames=$(find "$textures" -name '*.png' | wc -l)
[[ $frames -gt 0 ]] || { echo "No frames came out of $video" >&2; exit 1; }

sound_json=
if [[ $no_audio -eq 0 ]]; then
    if [[ -n $(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$audio") ]]; then
        echo "Extracting sound..."
        # A 30 ms fade at the end, so a cut in the middle of a word doesn't click.
        fade_start=$(python3 -c "print(max(0.0, float('$duration') - float('$fade_out')))")
        ffmpeg -v error -ss "$audio_start" -t "$duration" -i "$audio" -vn -af "volume=${gain}dB,afade=t=out:st=$fade_start:d=$fade_out" -c:a libvorbis -q:a 5 \
            "$assets/sounds/totem_animations/$id.ogg"
        cat > "$assets/sounds.json" <<EOF
{
  "totem_animations.$id": {
    "sounds": [
      { "name": "trollingofundying:totem_animations/$id", "stream": true }
    ]
  }
}
EOF
        sound_json='"sound": "trollingofundying:totem_animations.'$id'",'
    else
        echo "No audio track in $audio, so the animation will be silent."
    fi
fi

case $line_direction in
    ltr) from=-819 to=819 ;;
    rtl) from=819 to=-819 ;;
    *) echo "Unknown --line-direction: $line_direction (use ltr or rtl)" >&2; exit 1 ;;
esac
case $motion in
    line) motion_json='"keyframes": [
    { "time": 0.0, "x": 0.5, "offset_x": '$from', "y": 0.62, "size": 0.6, "rotation": 0, "alpha": 1 },
    { "time": 1.0, "offset_x": '$to' }
  ]' ;;
    vanilla_totem) motion_json='"motion": "vanilla_totem",
  "size_scale": 1.0' ;;
    still) motion_json='"keyframes": [
    { "time": 0.0, "x": 0.5, "y": 0.5, "size": 0.6, "rotation": 0, "alpha": 1 }
  ]' ;;
    pulse)
        [[ -n $sound_json ]] || { echo "--motion pulse needs a sound to find the beat in" >&2; exit 1; } ;;
    *) echo "Unknown --motion: $motion (use line, vanilla_totem, pulse or still)" >&2; exit 1 ;;
esac

# As long as the frames that actually came out (ffmpeg can land one short of
# --duration), so the last tick doesn't loop back to the first frame. A vanilla
# sound intro delays the clip by that many ticks, so the animation runs that much longer.
duration_ticks=$(python3 -c "print(max(1, round($frames * 20 / float('$fps'))) + int('$vanilla_sound_ticks'))")
if [[ $motion == pulse ]]; then
    echo "Finding the beat..."
    motion_json='"keyframes": '$(python3 "$(dirname "$0")/beat_pulse.py" "$assets/sounds/totem_animations/$id.ogg" \
        --duration-ticks "$duration_ticks" --offset-ticks "$vanilla_sound_ticks")
fi
mkdir -p "$assets/totem_animations"
json=$assets/totem_animations/$id.json
order_line=
[[ -n $order ]] && order_line=$'  "order": '$order$',\n'
cat > "$json" <<EOF
{
${order_line}  "duration_ticks": $duration_ticks,
  "frames": "trollingofundying:textures/totem_animations/$id",
  "fps": $fps,
  "loop_frames": true,
  $sound_json
  "sound_volume": 1.0,
  "stop_sound_at_end": $stop_sound,
  "vanilla_sound_ticks": $vanilla_sound_ticks,
  "hide_vanilla_item": true,
  "hide_vanilla_particles": false,
  "mute_vanilla_sound": true,
  "alpha": $alpha,
  $motion_json
}
EOF
if [[ -n $player_face ]]; then
    python3 - "$json" "$player_face" "$frames" <<'PY'
import json, sys
path, track, frames = sys.argv[1], sys.argv[2], int(sys.argv[3])
animation = json.load(open(path))
spots = json.load(open(track))
# ffmpeg can land a frame more or fewer than the tracker counted; hold the last spot.
spots = (spots + spots[-1:] * frames)[:frames]
animation["player_face"] = {"hat": True, "alpha": 1.0, "track": spots}
json.dump(animation, open(path, "w"), indent=2)
PY
fi
cat > "$work/pack.mcmeta" <<EOF
{
  "pack": {
    "description": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$name"),
    "min_format": 97,
    "max_format": 999
  }
}
EOF

python3 - "$work" "$out" <<'EOF'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for folder, _, files in os.walk(root):
        for f in sorted(files):
            path = os.path.join(folder, f)
            z.write(path, os.path.relpath(path, root))
EOF
echo "Wrote $out ($frames frames, $duration_ticks ticks)."
echo "Animation \"$id\". Put it in .minecraft/resourcepacks, enable it, then try /trollingofundying play $id in game."
