#!/usr/bin/env bash
# Builds the animation pack that ships inside the mod as a built-in resource
# pack (players see it in the Resource Packs list as "Trolling of Undying:
# Risitas"): El Risitas laughing, his face cut out as an oval, flying at the
# camera like the vanilla totem.
#
#   tools/build_builtin_pack.sh VIDEO [OUT_DIR]   (default src/main/resources/resourcepacks/risitas)
#
# VIDEO is the source interview; it is not part of this project. The cut
# points below are timed to it: the close-up laugh at 4:48.
set -euo pipefail
[[ $# -ge 1 && -f $1 ]] || { echo "Usage: $0 VIDEO [OUT_DIR]" >&2; exit 1; }
video=$(realpath "$1")
cd "$(dirname "$0")/.."
out=${2:-src/main/resources/resourcepacks/risitas}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tools/make_totem_pack.sh "$video" --start 288 --duration 2.9 --crop 640:900:760:40 --shape ellipse \
    --motion vanilla_totem --let-sound-finish --alpha 0.6 --vanilla-sound-ticks 8 \
    --name "Risitas laughing" --id risitas --out "$work/risitas.zip"

# Unpacked into the mod's resources, replacing any earlier build of it.
mkdir -p "$work/pack"
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$work/risitas.zip" "$work/pack"
rm -rf "$out"
mkdir -p "$(dirname "$out")"
mv "$work/pack" "$out"
echo "Built-in pack -> $out"
