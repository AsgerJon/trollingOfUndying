# trollingOfUndying
Trolling of Undying provides replacements for the animation that happen when a totem pops. 

A client-side Fabric mod for Minecraft 26.3 that replaces the totem of undying
pop animation with anything you like: a sequence of frames that moves across
the screen along a path you define, with its own sound.

The animations come from ordinary resource packs, so swapping them is just
swapping packs (F3+T reloads them). With no animation pack enabled, the vanilla
animation plays untouched. See [Making an animation pack](#making-an-animation-pack)
for the format; the scripts in `tools/` (below) build packs from video clips, but
a pack can just as well be made by hand.

## The built-in animation

The mod comes with one animation built in, so there is something to see straight away: El Risitas laughing, his face
cut out as an oval and flying at you like the vanilla totem.

It is a resource pack inside the mod, so you won't find it in your `resourcepacks` folder. Open Options → Resource
Packs, and **Trolling of Undying: Risitas** is in the list of available packs, switched off. Move it across to enable
it, then try `/trollingofundying play risitas`. Switch it off again and the vanilla animation is back.

The clip is from Juan Joya Borja's ("El Risitas") interview on *Ratones coloraos*, Canal Sur Televisión.

## Quick start: from a video

The scripts in `tools/` are not part of the mod. They run on your computer (Linux or macOS, with `ffmpeg` and
`python3`), cut a clip out of a video, and write a finished resource pack in the format described under [Making an
animation pack](#making-an-animation-pack).

```sh
tools/make_totem_pack.sh dancing.mp4 --start 43 --duration 2 --chroma 0x00ff00 --name "My Totem"
```

This writes `totem_animation_pack.zip`, an animation called `totem_animation_pack` (pick the name with `--id`).
Put it in `.minecraft/resourcepacks`, enable it, and run `/trollingofundying play` to preview it without having
to die.

Enable as many animation packs as you like: the totem pops take turns through them, in the order their `order`
fields give (`--order` below), and start over from the top after the last one. The rotation carries on where it
left off after a restart. `tools/combine_packs.py a.zip b.zip ... --out combined.zip` merges several into a single pack.
For a worked example, see [how the built-in pack is made](#how-the-built-in-pack-is-made).

* `--chroma` keys out a green screen, so only the dancer is drawn.
* `--audio song.ogg --audio-start 0` takes the sound from a separate file.
* `--vanilla-sound-ticks 8` opens with 0.4 s of the vanilla totem boom before the clip's sound takes over; `--let-sound-finish` lets that sound run past the end of the animation.
* `--gain 9` makes a quiet clip's sound 9 dB louder. The game caps `sound_volume` at 1, so a boost has to go into the file.
* `--alpha 0.6` makes the animation 60% opaque.
* `--motion vanilla_totem` makes the clip move like the vanilla totem instead of crossing the screen.
* `--shape soft_rect` keeps the whole (cropped) frame but fades its edges out, for wide shots an oval would cut into.
* `--motion still` keeps the clip steady in the middle of the screen, like a cutscene; `--motion pulse` keeps it there
  but swells it on every beat of the music.
* `--motion line --line-direction rtl` crosses the screen right to left instead.
* `--player-face TRACK.json` puts the player's face into the clip, following a track made by `tools/track_spot.py`
  (needs `opencv-python-headless`, `numpy` and `Pillow`): give it the face's spot and a box around what it sits on
  in the first frame, and it follows that thing through the clip. See `tools/track_spot.py --help`.
  For a person's face, `tools/track_face.py` runs a face detector on every frame instead, so it copes with them
  moving and turning; frames where their face can't be seen get no player face. See `tools/track_face.py --help`.
* No green screen? `tools/segment_person.py` cuts the people out of every frame with a segmentation model (needs
  `onnxruntime`, `numpy` and `Pillow`; it downloads its 176 MB model once). Give its output folder to
  `--frames-dir`, and the video then only supplies the sound. `--fade-out 1.0` fades that sound out over its last second.
* `--crop W:H:X:Y --shape ellipse` cuts an oval (say, a face) out of the video, with soft see-through edges. `--feather` sets how soft.
* `--duration` sets both the clip length and the animation length. Vanilla's is 2 seconds.
* `--order 3` makes it the third in the rotation of pops.

Run `tools/make_totem_pack.sh --help` for all options. It needs `ffmpeg` and `python3`.

## Skill issue counter

From your first totem pop on, a red "Skill issue counter: N" sits to the left of the hotbar, counting the
totems you have popped in this world. Every singleplayer world and every server keeps its own count for each
player. A second after the pop's animation is over, the classic "oof" hurt sound
(unused since Beta 1.9, but still in the game files) plays and the new number
lands, big, shaking and flashing, before it settles. A toast announces it the moment the totem pops:
"**Alert!** Excessive skill issues detected!" over "Total skill issues for NAME: N". It slides down at the
top right of the screen's center rather than the window's corner, so it stays on the middle monitor. Vanilla's "has reached
the goal [Postmortal]" chat line about you is dropped. The counts are stored in `config/trollingofundying.json`;
deleting that file resets them all (and starts the rotation of animations from the top).

## Commands

| Command | What it does |
| --- | --- |
| `/trollingofundying play` | Plays the next animation in the rotation (preview), moving the rotation on |
| `/trollingofundying play NAME` | Plays that animation (tab completes) |
| `/trollingofundying stop` | Stops it, sound included |
| `/trollingofundying info` | Lists the loaded animations in rotation order, which one is next, and any that failed to load and why |

## Making an animation pack

An animation pack is a normal resource pack. If you have made a texture or sound pack, everything here will look
familiar: a `pack.mcmeta`, an `assets/<namespace>/` folder, PNG textures, `.ogg` sounds and a `sounds.json`. The one
new thing is a JSON file per animation that tells the mod which textures and which sound make up the animation, and
how to move it.

### How the mod finds animations

The mod never looks in the `resourcepacks` folder itself. It goes through Minecraft's resource system, like
everything else in the game:

1. Only **enabled** packs count (Options → Resource Packs). A pack that just sits in the folder is ignored.
2. On every resource reload (game start, changing packs, F3+T), the mod collects every file matching
   `assets/trollingofundying/totem_animations/*.json` from **all** enabled packs together. Each file is one
   animation, named after the file: `totem_animations/risitas.json` is the animation `risitas`.
3. The usual stacking rules apply. If two enabled packs contain the same file (the same animation name, or the same
   frame path), the pack higher in the list wins. `sounds.json` files are merged as usual.
4. The pops take turns through all loaded animations, by their `order` field (see below), then start over.

### What a pack looks like

```
my_pack.zip
  pack.mcmeta
  assets/trollingofundying/
    totem_animations/
      risitas.json                        the animation: which frames, which sound, how it moves
    textures/totem_animations/risitas/
      frame_0001.png                      the frames, one PNG per picture
      frame_0002.png
      ...
    sounds/totem_animations/
      risitas.ogg                         the sound
    sounds.json                           declares the sound event, as in any sound pack
```

The `totem_animations/` folder must be in the `trollingofundying` namespace, because that is where the mod looks.
The frames and the sound can live anywhere, in any namespace: the JSON points at them by their full id. The layout
above is simply what the tools write.

### Frames: a PNG sequence, not a video

Minecraft can't play video, so an animation is a sequence of still images that the mod flips through, like a
flipbook. If you know vanilla's animated textures: this is **not** that. There is no vertical strip and no
`.png.mcmeta`. Each frame is its own PNG file, and the timing comes from the animation's JSON.

- **Any size.** Frames are not tied to the 16×16 grid or to powers of two. The tools make them 256 or 288 pixels tall.
  All frames of one animation should have the same proportions; the first frame's width over height is used for all
  of them (or set `aspect_ratio`).
- **Transparency works.** Transparent pixels are see-through on screen, so a figure cut out of its background (green
  screen or `tools/segment_person.py`) is drawn on its own.
- **Order is by file name.** With `"frames"` set to a folder, every `.png` in it plays in alphabetical order of its
  name. Pad the numbers (`frame_0001.png` ... `frame_0148.png`): unpadded, `frame_10.png` would sort before
  `frame_2.png`.
- **Speed is `fps`.** At the default 20 frames per second, one frame is shown per game tick, so 2 seconds of animation
  is 40 frames.
- **Full ids, with `textures/` and `.png`.** Unlike models, which leave both out, `frames` names the actual files:
  the folder `"trollingofundying:textures/totem_animations/risitas"`, or a list of single frames such as
  `"trollingofundying:textures/totem_animations/risitas/frame_0001.png"`.
- **Mind the memory.** Every frame of every enabled animation is loaded onto the graphics card when packs load, so
  the first pop doesn't stutter. Uncompressed, a frame takes width × height × 4 bytes: the built-in Risitas's 58
  frames of 182×256 take about 11 MB, while 150 frames of 384×288 would take about 66 MB. Keep frames modest in size, and clips a few seconds long.

### The frames don't move; the mod moves them

The frames themselves are flat pictures, just as the clip looked. All the movement happens in the mod while the
animation plays: every frame the game draws, the mod works out where the picture should be, how big, how far turned
and how see-through, and draws the current frame that way. The animation's JSON says which kind of movement:

- `"motion": "vanilla_totem"` flies the picture at the camera, spinning, exactly the way the vanilla totem moves.
- `keyframes` place it on screen at points in time, and the mod glides between them: one keyframe holds it still,
  like a cutscene; two can slide it across the screen; many can make it pulse or wobble.

So the same frames can be shown in completely different ways by editing only the JSON, without touching an image.

### Sound

The sound is an ordinary sound event, declared in `sounds.json` exactly as in any sound pack:

```json
{
  "totem_animations.risitas": {
    "sounds": [
      { "name": "trollingofundying:totem_animations/risitas", "stream": true }
    ]
  }
}
```

`"stream": true` is a good idea for anything longer than a second or two, as with music. The animation's `"sound"`
then names the event: `"trollingofundying:totem_animations.risitas"`. Any event works, vanilla's included, so an
animation can use a sound the game already has (`"minecraft:entity.zombie.ambient"`) without shipping any audio.
`sound_volume` can't go above 1, so a quiet recording has to be made louder in the file itself.

### A minimal pack, made by hand

Three PNGs and a vanilla sound, no tools needed:

```
pack.mcmeta
assets/trollingofundying/totem_animations/hello.json
assets/trollingofundying/textures/totem_animations/hello/frame_1.png
assets/trollingofundying/textures/totem_animations/hello/frame_2.png
assets/trollingofundying/textures/totem_animations/hello/frame_3.png
```

`hello.json`:

```json
{
  "duration_ticks": 40,
  "frames": "trollingofundying:textures/totem_animations/hello",
  "fps": 6,
  "sound": "minecraft:entity.villager.celebrate",
  "keyframes": [
    { "time": 0.0, "x": 0.5, "y": 0.5, "size": 0.5 }
  ]
}
```

This shows the three frames over and over at 6 per second (`loop_frames` is on by default), in the middle of the
screen at half its height, for 2 seconds, while a villager cheers. The vanilla totem stays hidden and silent.

### Checking a pack

- `/trollingofundying info` lists every animation that loaded, in rotation order, and for any file that failed, why
  (broken JSON, a frame folder with no PNGs in it, a value the mod doesn't understand such as an unknown `motion`).
  The same goes into the game log.
- A misspelled field name is not an error: the mod ignores fields it doesn't know, so `"fsp": 6` quietly leaves
  `fps` at its default. If a setting seems to have no effect, check its spelling.
- A sound id that doesn't exist doesn't stop the animation loading; it just plays no sound, and the game log
  mentions the unknown sound event.
- `/trollingofundying play NAME` plays one right away, without having to nearly die. It tab-completes the names.
- After changing files in an unzipped pack folder, F3+T reloads everything.

Older packs with a single `assets/trollingofundying/totem_animation.json` (no `s`) still work; that file counts as an
animation called `totem_animation`.

### `totem_animations/NAME.json`

```json
{
  "order": 1,
  "duration_ticks": 40,
  "frames": "trollingofundying:textures/totem_animations/NAME",
  "fps": 20,
  "loop_frames": true,
  "sound": "trollingofundying:totem_animations.NAME",
  "sound_source": "player",
  "sound_volume": 1.0,
  "sound_pitch": 1.0,
  "stop_sound_at_end": true,
  "hide_vanilla_item": true,
  "hide_vanilla_particles": false,
  "mute_vanilla_sound": true,
  "keyframes": [
    { "time": 0.0, "x": 0.5, "offset_x": -819, "y": 0.62, "size": 0.6, "rotation": 0, "alpha": 1 },
    { "time": 1.0, "offset_x": 819 }
  ]
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `order` | none | Place in the rotation of pops, lowest first. Equal numbers go in name order, and animations without one come after all the numbered ones. |
| `duration_ticks` | `40` | How long the animation lasts (20 ticks = 1 second). |
| `frames` | required | A folder (all `.png` files in it, played in name order), a single `.png`, or a list of textures. |
| `fps` | `20` | Frame rate of `frames`. |
| `loop_frames` | `true` | Loop the frames if they run out before the duration, or hold the last one. |
| `aspect_ratio` | from the first frame | Width over height of the drawn frame. |
| `sound` | none | Sound event id from your `sounds.json`. |
| `sound_source` | `player` | Which volume slider controls it: `master`, `music`, `record`, `weather`, `block`, `hostile`, `neutral`, `player`, `ambient`, `voice`, `ui`. |
| `sound_volume`, `sound_pitch` | `1.0` | |
| `stop_sound_at_end` | `true` | Cut the sound when the animation ends, or let it finish. |
| `hide_vanilla_item` | `true` | Hide the spinning totem. |
| `hide_vanilla_particles` | `false` | Hide the particle burst around you. |
| `mute_vanilla_sound` | `true` | Mute the vanilla totem sound. |
| `vanilla_sound_ticks` | `0` | Above 0, open with the vanilla totem sound: it plays for this many ticks, fades out, and only then do `sound` and the frames start (the first frame is held until then, so they stay in sync). Count these ticks in `duration_ticks`. |
| `vanilla_sound_fade_ticks` | `20` | How long that vanilla sound takes to fade out, underneath the pack's sound. |
| `motion` | `keyframes` | `keyframes` follows `keyframes` below. `vanilla_totem` moves exactly like the vanilla totem instead: flies at the camera from the distance, spins up to two and a half turns (mirrored while it faces away), swings out to a random side and wobbles, stretched over `duration_ticks`. |
| `alpha` | `1.0` | Opacity of the whole animation, 0–1. Multiplies the keyframes' own `alpha`. |
| `size_scale` | `1.0` | Scales the frame under the `vanilla_totem` motion. At 1 it peaks at 57% of the screen height, like the totem. |

The three `hide`/`mute` options only apply to your own totem. Other players'
totems still look and sound vanilla.

### Keyframes

Each keyframe places the frame at a point in time. Values between keyframes are interpolated.

| Field | Meaning |
| --- | --- |
| `time` | 0 = start, 1 = end of the animation. Must not decrease. |
| `x`, `y` | Center of the frame as a fraction of the screen: `0,0` is top left, `1,1` bottom right. Values outside 0–1 are off screen. |
| `offset_x`, `offset_y` | Shift from `x`, `y` in window pixels, the same at any screen size or GUI scale. Handy for multi-monitor setups: `"x": 0.5, "offset_x": -819` is 1.5 hotbar widths (at GUI scale 3) left of the center. |
| `size` | Frame height as a fraction of the screen height. |
| `rotation` | Degrees clockwise. |
| `alpha` | Opacity, 0–1. |
| `flip` | Mirror horizontally. Not interpolated; it holds until the next keyframe. |
| `easing` | Shape of the segment arriving at this keyframe: `linear`, `ease_in`, `ease_out`, `ease_in_out`, `step`. |

Any field a keyframe leaves out is copied from the keyframe before it, so a path
only needs to state what changes. The first keyframe's defaults are the screen
center with no offset, at half the screen's height, fully opaque.

### Your face in the clip

`player_face` draws the face of the player's own skin (hat layer included) onto the frames, following
something in the clip, like a coffin:

```json
{
  "player_face": {
    "hat": true,
    "alpha": 1.0,
    "track": [
      { "x": 0.52, "y": 0.31, "size": 0.12, "rotation": -4 },
      null,
      ...
    ]
  }
}
```

`track` has one spot per frame, in the same order as the frames. `x`, `y` is the face's center as a fraction
of the frame (`0,0` top left), `size` its height as a fraction of the frame's height, `rotation` its tilt in
degrees clockwise. `null`, or running out of spots, hides the face for that frame. The face moves, spins,
mirrors and fades with the frame.

### How the built-in pack is made

The built-in Risitas pack is a worked example of the tools. Its source interview is not included in this project;
`tools/build_builtin_pack.sh VIDEO` takes it as an argument and does the rest:

```sh
tools/make_totem_pack.sh VIDEO --start 288 --duration 2.9 --crop 640:900:760:40 --shape ellipse \
    --motion vanilla_totem --let-sound-finish --alpha 0.6 --vanilla-sound-ticks 8 --id risitas
```

That takes 2.9 seconds from 4:48 into the interview, crops a 640×900 box around his face and cuts an oval out of it
with soft edges, and sets it flying like the vanilla totem at 60% opacity, opening with 0.4 s of the vanilla totem
sound and letting the laugh run on after the picture is gone. The script then unpacks the result into
`src/main/resources/resourcepacks/risitas/`, where the mod build picks it up as the built-in pack.

## Building

Needs JDK 25: `./gradlew build`, and the jar lands in `build/libs/`.
The mod requires Fabric API.
