#!/usr/bin/env python3
"""Merge several animation packs from make_totem_pack.sh into one resource pack.

Every animation keeps its own ID (and its "order" in the rotation), so the
combined pack holds them all and the pops take turns through them. The packs' sounds.json files are merged;
if two packs use the same ID, the later one on the command line wins.

  combine_packs.py first.zip second.zip third.zip --out combined.zip --name "My totem animations"
"""
import argparse
import json
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("packs", nargs="+", help="packs made by make_totem_pack.sh")
    parser.add_argument("--out", required=True)
    parser.add_argument("--name", default="Trolling of Undying animations", help="pack name shown in the game")
    args = parser.parse_args()

    files, sounds, ids = {}, {}, {}
    for pack in args.packs:
        with zipfile.ZipFile(pack) as z:
            for name in z.namelist():
                if name.endswith("/") or name == "pack.mcmeta":
                    continue
                if name == "assets/trollingofundying/totem_animation.json":
                    raise SystemExit(f"{pack} is an old single-animation pack; rebuild it with make_totem_pack.sh --id")
                if name == "assets/trollingofundying/sounds.json":
                    sounds.update(json.loads(z.read(name)))
                    continue
                if name.startswith("assets/trollingofundying/totem_animations/") and name.endswith(".json"):
                    ids[name.rsplit("/", 1)[1][:-len(".json")]] = pack
                files[name] = z.read(name)

    mcmeta = {"pack": {"description": args.name, "min_format": 97, "max_format": 999}}
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(mcmeta, indent=2))
        if sounds:
            z.writestr("assets/trollingofundying/sounds.json", json.dumps(sounds, indent=2))
        for name in sorted(files):
            z.writestr(name, files[name])
    print(f"{len(ids)} animations ({', '.join(ids)}) -> {args.out}")


if __name__ == "__main__":
    main()
