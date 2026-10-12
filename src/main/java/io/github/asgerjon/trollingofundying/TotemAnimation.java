package io.github.asgerjon.trollingofundying;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * One totem animation, as described by {@code trollingofundying:totem_animations/NAME.json}
 * in a resource pack. See the README for the file format.
 *
 * @param frames       textures shown in order, looping or holding on the last one
 * @param aspectRatio  frame width divided by frame height
 * @param keyframes    motion path, sorted by time, never empty
 * @param motion       follow the keyframes, or move like vanilla's spinning totem
 * @param sizeScale    multiplies the frame size of the {@code vanilla_totem} motion
 * @param alpha        opacity of the whole animation, multiplied with any keyframe alpha
 * @param vanillaSoundTicks when above 0, the vanilla totem sound plays for this long and
 *                     fades out, and only then do {@code sound} and the frames start
 *                     (the frames hold on the first one until then)
 * @param vanillaSoundFadeTicks how long that vanilla sound takes to fade out, under the pack's sound
 * @param playerFace   draws the player's own face onto the frames, or null
 * @param order        place in the rotation of pops, lowest first; {@link Integer#MAX_VALUE}
 *                     when the pack gives none, so those come last
 */
public record TotemAnimation(
        int durationTicks,
        List<Identifier> frames,
        float fps,
        boolean loopFrames,
        float aspectRatio,
        List<Keyframe> keyframes,
        Motion motion,
        float sizeScale,
        float alpha,
        @Nullable Identifier sound,
        SoundSource soundSource,
        float soundVolume,
        float soundPitch,
        boolean stopSoundAtEnd,
        boolean hideVanillaItem,
        boolean hideVanillaParticles,
        boolean muteVanillaSound,
        int vanillaSoundTicks,
        int vanillaSoundFadeTicks,
        @Nullable PlayerFace playerFace,
        int order
) {
    /** Whether vanilla's own totem sound must stay quiet (it is muted, or the mod plays it itself). */
    public boolean silencesVanillaSound() {
        return this.muteVanillaSound || this.vanillaSoundTicks > 0;
    }

    /**
     * Where the frame is drawn at one point in time. Positions are fractions
     * of the screen ({@code 0,0} top left, {@code 1,1} bottom right) and mark
     * the frame's center; {@code offsetX} and {@code offsetY} then shift it by
     * that many window pixels, whatever the screen size or GUI scale.
     * {@code size} is the frame height as a fraction of the screen height.
     * {@code easing} shapes the segment that ends here.
     */
    public record Keyframe(
            float time, float x, float y, float offsetX, float offsetY, float size, float rotation, float alpha, boolean flip,
            Easing easing
    ) {
    }

    /** {@code scaleX} squashes the frame horizontally; below zero it is mirrored. */
    public record Pose(
            float x, float y, float offsetX, float offsetY, float size, float scaleX, float rotation, float alpha, boolean flip
    ) {
    }

    public enum Motion { KEYFRAMES, VANILLA_TOTEM }

    /**
     * The face of the player's skin, stuck to something in the clip.
     *
     * @param track one spot per frame, matching {@code frames} by index; a null
     *              spot (or a frame past the end of the list) hides the face
     * @param hat   draw the skin's hat layer over the face
     */
    public record PlayerFace(List<@Nullable FaceSpot> track, boolean hat, float alpha) {
        public @Nullable FaceSpot spotAt(int frame) {
            return frame < this.track.size() ? this.track.get(frame) : null;
        }
    }

    /**
     * Where the face sits in one frame: its center as a fraction of the frame
     * ({@code 0,0} top left), its height as a fraction of the frame's height,
     * and its tilt in degrees clockwise.
     */
    public record FaceSpot(float x, float y, float size, float rotation) {
    }

    /** tan(35 degrees): half of the fixed 70 degree field of view the HUD's 3D items are drawn with. */
    private static final float VANILLA_TAN_HALF_FOV = 0.70020753F;

    public enum Easing {
        LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, STEP;

        float apply(float t) {
            return switch (this) {
                case LINEAR -> t;
                case EASE_IN -> t * t;
                case EASE_OUT -> 1.0F - (1.0F - t) * (1.0F - t);
                case EASE_IN_OUT -> t * t * (3.0F - 2.0F * t);
                case STEP -> 0.0F;
            };
        }
    }

    /** Which of {@code frames} to show {@code seconds} into the animation. */
    public int frameIndexAt(float seconds) {
        int index = (int) (seconds * this.fps);
        int count = this.frames.size();
        return this.loopFrames ? Math.floorMod(index, count) : Mth.clamp(index, 0, count - 1);
    }

    /**
     * Where the frame is at {@code progress}, from 0 (start) to 1 (end).
     * {@code randomX} and {@code randomY}, each in -1..1, are rolled once per
     * pop and only used by the {@code vanilla_totem} motion.
     */
    public Pose poseAt(float progress, float randomX, float randomY) {
        return this.motion == Motion.VANILLA_TOTEM ? this.vanillaPoseAt(progress, randomX, randomY) : this.keyframePoseAt(progress);
    }

    /**
     * The path of vanilla's spinning totem (ScreenEffectRenderer), flattened to
     * 2D: the item flies from 10 units away to 1 and back, under the HUD's fixed
     * 70 degree field of view, swings out sideways by the random offset, spins
     * up to 900 degrees about its vertical axis and wobbles a little.
     */
    private Pose vanillaPoseAt(float progress, float randomX, float randomY) {
        float s = progress;
        float smooth = 10.25F * s * s * s * s * s - 24.95F * s * s * s * s + 25.5F * s * s * s - 13.8F * s * s + 4.0F * s;
        float angle = smooth * Mth.PI;
        float distance = 10.0F - 9.0F * Mth.sin(angle);
        float halfViewHeight = distance * VANILLA_TAN_HALF_FOV;
        float swing = Mth.abs(Mth.sin(angle * 2.0F)) * 0.3F / halfViewHeight;
        float spin = 900.0F * Mth.abs(Mth.sin(angle)) * Mth.DEG_TO_RAD;
        return new Pose(
                0.5F + 0.5F * randomX * swing,
                0.5F - 0.5F * randomY * swing,
                0.0F,
                0.0F,
                this.sizeScale * 0.8F / (2.0F * halfViewHeight),
                Mth.cos(spin),
                -6.0F * Mth.cos(s * 8.0F),
                1.0F,
                false
        );
    }

    private Pose keyframePoseAt(float progress) {
        Keyframe previous = this.keyframes.getFirst();
        if (progress <= previous.time()) {
            return pose(previous, previous, 0.0F);
        }
        for (Keyframe next : this.keyframes) {
            if (progress < next.time()) {
                float t = (progress - previous.time()) / (next.time() - previous.time());
                return pose(previous, next, next.easing().apply(t));
            }
            previous = next;
        }
        return pose(previous, previous, 0.0F);
    }

    private static Pose pose(Keyframe a, Keyframe b, float t) {
        return new Pose(
                Mth.lerp(t, a.x(), b.x()),
                Mth.lerp(t, a.y(), b.y()),
                Mth.lerp(t, a.offsetX(), b.offsetX()),
                Mth.lerp(t, a.offsetY(), b.offsetY()),
                Mth.lerp(t, a.size(), b.size()),
                1.0F,
                Mth.lerp(t, a.rotation(), b.rotation()),
                Mth.lerp(t, a.alpha(), b.alpha()),
                a.flip()
        );
    }

    /**
     * Builds an animation from its JSON. {@code frames} has already been
     * resolved by the caller, since that needs the resource manager.
     *
     * @throws IllegalArgumentException with a message meant for pack makers
     */
    public static TotemAnimation parse(JsonObject json, List<Identifier> frames, float measuredAspectRatio) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("\"frames\" did not match any .png textures");
        }
        int durationTicks = getInt(json, "duration_ticks", 40);
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("\"duration_ticks\" must be positive");
        }
        float fps = getFloat(json, "fps", 20.0F);
        if (fps <= 0.0F) {
            throw new IllegalArgumentException("\"fps\" must be positive");
        }
        String sound = getString(json, "sound", null);
        String sourceName = getString(json, "sound_source", SoundSource.PLAYERS.getName());
        SoundSource soundSource = null;
        for (SoundSource source : SoundSource.values()) {
            if (source.getName().equals(sourceName)) {
                soundSource = source;
            }
        }
        if (soundSource == null) {
            throw new IllegalArgumentException("unknown \"sound_source\": " + sourceName);
        }
        return new TotemAnimation(
                durationTicks,
                List.copyOf(frames),
                fps,
                getBoolean(json, "loop_frames", true),
                getFloat(json, "aspect_ratio", measuredAspectRatio),
                parseKeyframes(json),
                parseMotion(getString(json, "motion", "keyframes")),
                getFloat(json, "size_scale", 1.0F),
                getFloat(json, "alpha", 1.0F),
                sound == null ? null : parseIdentifier(sound),
                soundSource,
                getFloat(json, "sound_volume", 1.0F),
                getFloat(json, "sound_pitch", 1.0F),
                getBoolean(json, "stop_sound_at_end", true),
                getBoolean(json, "hide_vanilla_item", true),
                getBoolean(json, "hide_vanilla_particles", false),
                getBoolean(json, "mute_vanilla_sound", true),
                Math.max(0, getInt(json, "vanilla_sound_ticks", 0)),
                Math.max(1, getInt(json, "vanilla_sound_fade_ticks", 20)),
                json.has("player_face") ? parsePlayerFace(json.getAsJsonObject("player_face")) : null,
                getInt(json, "order", Integer.MAX_VALUE)
        );
    }

    /**
     * Each keyframe inherits any field it leaves out from the one before it,
     * so a path only needs to spell out what changes.
     */
    private static List<Keyframe> parseKeyframes(JsonObject json) {
        Keyframe previous = new Keyframe(0.0F, 0.5F, 0.5F, 0.0F, 0.0F, 0.5F, 0.0F, 1.0F, false, Easing.LINEAR);
        if (!json.has("keyframes")) {
            return List.of(previous);
        }
        JsonArray array = json.getAsJsonArray("keyframes");
        List<Keyframe> keyframes = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String easing = getString(object, "easing", "linear");
            Keyframe keyframe = new Keyframe(
                    getFloat(object, "time", previous.time()),
                    getFloat(object, "x", previous.x()),
                    getFloat(object, "y", previous.y()),
                    getFloat(object, "offset_x", previous.offsetX()),
                    getFloat(object, "offset_y", previous.offsetY()),
                    getFloat(object, "size", previous.size()),
                    getFloat(object, "rotation", previous.rotation()),
                    getFloat(object, "alpha", previous.alpha()),
                    getBoolean(object, "flip", previous.flip()),
                    parseEasing(easing)
            );
            if (!keyframes.isEmpty() && keyframe.time() < previous.time()) {
                throw new IllegalArgumentException("keyframe times must not decrease (" + keyframe.time() + " after " + previous.time() + ")");
            }
            keyframes.add(keyframe);
            previous = keyframe;
        }
        if (keyframes.isEmpty()) {
            throw new IllegalArgumentException("\"keyframes\" is empty");
        }
        return List.copyOf(keyframes);
    }

    private static PlayerFace parsePlayerFace(JsonObject json) {
        if (!json.has("track")) {
            throw new IllegalArgumentException("\"player_face\" needs a \"track\"");
        }
        List<@Nullable FaceSpot> track = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("track")) {
            if (element.isJsonNull()) {
                track.add(null);
                continue;
            }
            JsonObject spot = element.getAsJsonObject();
            track.add(new FaceSpot(
                    getFloat(spot, "x", 0.5F), getFloat(spot, "y", 0.5F), getFloat(spot, "size", 0.2F), getFloat(spot, "rotation", 0.0F)
            ));
        }
        return new PlayerFace(Collections.unmodifiableList(track), getBoolean(json, "hat", true), getFloat(json, "alpha", 1.0F));
    }

    private static Motion parseMotion(String name) {
        try {
            return Motion.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown \"motion\": " + name + " (use keyframes or vanilla_totem)");
        }
    }

    private static Easing parseEasing(String name) {
        try {
            return Easing.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown \"easing\": " + name);
        }
    }

    static Identifier parseIdentifier(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            throw new IllegalArgumentException("not a valid resource id: " + id);
        }
        return identifier;
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static float getFloat(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    static @Nullable String getString(JsonObject json, String key, @Nullable String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }
}
