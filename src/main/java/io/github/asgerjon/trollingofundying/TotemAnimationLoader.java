package io.github.asgerjon.trollingofundying;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads every totem animation in the active resource packs on each resource
 * reload: {@code assets/trollingofundying/totem_animations/NAME.json}, from all
 * packs together, plus the older single {@code totem_animation.json}. The pops
 * take turns through them in rotation: by each animation's {@code order}, ties
 * (and animations without one, which come last) by name, starting over after
 * the end. The last one played is saved in {@link ConfigFile}, so the rotation
 * carries on where it left off after a restart. With none loaded, the vanilla
 * animation plays untouched.
 * <p>
 * The next pop's animation is picked in advance ({@link #upcoming()}), because
 * vanilla plays its totem sound and particles before the pop reaches the
 * player, and whether to mute or hide depends on the animation.
 */
public final class TotemAnimationLoader implements ResourceManagerReloadListener {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(TrollingOfUndyingClient.MOD_ID, "totem_animation");
    private static final String FOLDER = "totem_animations";
    private static final Identifier LEGACY_FILE = Identifier.fromNamespaceAndPath(TrollingOfUndyingClient.MOD_ID, "totem_animation.json");
    private static final String LEGACY_NAME = "totem_animation";
    private static final Logger LOGGER = LoggerFactory.getLogger(TrollingOfUndyingClient.MOD_ID);
    private static final String LAST_KEY = "last_animation";

    private static Map<String, TotemAnimation> animations = Map.of();
    /** The names of {@link #animations}, in the order the pops take turns. */
    private static List<String> rotation = List.of();
    private static @Nullable String upcoming;
    private static @Nullable String last;
    private static @Nullable String status;

    /** Every loaded animation, by name. */
    public static Map<String, TotemAnimation> all() {
        return animations;
    }

    public static @Nullable TotemAnimation get(String name) {
        return animations.get(name);
    }

    /** The animation the next pop will play, or null to leave vanilla alone. */
    public static @Nullable TotemAnimation upcoming() {
        return upcoming == null ? null : animations.get(upcoming);
    }

    /** Hands out the upcoming animation for a pop, and moves the rotation on to the next one. */
    public static @Nullable TotemAnimation take() {
        TotemAnimation animation = upcoming();
        if (upcoming != null) {
            last = upcoming;
            ConfigFile.put(LAST_KEY, new JsonPrimitive(last));
        }
        pickUpcoming();
        return animation;
    }

    /** Reads where the rotation left off last session. */
    static void loadRotation() {
        JsonElement saved = ConfigFile.read().get(LAST_KEY);
        last = saved != null && saved.isJsonPrimitive() ? saved.getAsString() : null;
    }

    /**
     * The one after the last one played, or the first one after the end of the
     * rotation (or when nothing has been played yet, or the last one played is
     * no longer loaded).
     */
    private static void pickUpcoming() {
        int lastIndex = last == null ? -1 : rotation.indexOf(last);
        upcoming = rotation.isEmpty() ? null : rotation.get((lastIndex + 1) % rotation.size());
    }

    /** A description of the last load and of the next pop, for {@code /trollingofundying info}. */
    public static String status() {
        if (status == null) {
            return "Not loaded yet.";
        }
        return upcoming == null ? status : status + "\nNext in the rotation: " + upcoming + ".";
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        TotemAnimationPlayer.stop();
        Map<String, TotemAnimation> loaded = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Map<Identifier, Resource> files = resourceManager.listResources(
                FOLDER, id -> id.getNamespace().equals(TrollingOfUndyingClient.MOD_ID) && id.getPath().endsWith(".json")
        );
        files.forEach((id, resource) -> {
            String name = id.getPath().substring(FOLDER.length() + 1, id.getPath().length() - ".json".length());
            load(resourceManager, name, id, resource, loaded, problems);
        });
        Optional<Resource> legacy = resourceManager.getResource(LEGACY_FILE);
        if (legacy.isPresent() && !loaded.containsKey(LEGACY_NAME)) {
            load(resourceManager, LEGACY_NAME, LEGACY_FILE, legacy.get(), loaded, problems);
        }
        animations = Collections.unmodifiableMap(loaded);
        // The map is in name order, and the sort is stable, so equal orders stay in name order.
        rotation = loaded.keySet().stream().sorted(Comparator.comparingInt(name -> loaded.get(name).order())).toList();
        pickUpcoming();

        StringBuilder summary = new StringBuilder(loaded.isEmpty()
                ? "No totem animations in the active resource packs; using the vanilla animation."
                : loaded.size() + " totem animation(s), in rotation order: " + String.join(", ", rotation) + ".");
        problems.forEach(problem -> summary.append("\n").append(problem));
        status = summary.toString();
        LOGGER.info(status);
        TotemAnimationPlayer.preloadFrames();
    }

    private static void load(
            ResourceManager resourceManager, String name, Identifier file, Resource resource, Map<String, TotemAnimation> into,
            List<String> problems
    ) {
        try (Reader reader = resource.openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            List<Identifier> frames = findFrames(resourceManager, json);
            float aspectRatio = frames.isEmpty() ? 1.0F : readAspectRatio(resourceManager, frames.getFirst());
            into.put(name, TotemAnimation.parse(json, frames, aspectRatio));
        } catch (IOException | RuntimeException e) {
            String problem = "Could not load " + file + " from pack '" + resource.sourcePackId() + "': " + e.getMessage();
            problems.add(problem);
            LOGGER.error(problem, e);
        }
    }

    /**
     * {@code "frames"} is either one texture, a folder whose .png files are
     * played in name order, or an explicit list of textures.
     */
    private static List<Identifier> findFrames(ResourceManager resourceManager, JsonObject json) {
        if (!json.has("frames")) {
            throw new IllegalArgumentException("missing \"frames\"");
        }
        if (json.get("frames").isJsonArray()) {
            return json.getAsJsonArray("frames").asList().stream()
                    .map(element -> TotemAnimation.parseIdentifier(element.getAsString()))
                    .toList();
        }
        Identifier location = TotemAnimation.parseIdentifier(json.get("frames").getAsString());
        if (location.getPath().endsWith(".png")) {
            return List.of(location);
        }
        String folder = location.getPath().endsWith("/") ? location.getPath() : location.getPath() + "/";
        return resourceManager.listResources(
                        location.getPath(),
                        id -> id.getNamespace().equals(location.getNamespace())
                                && id.getPath().startsWith(folder)
                                && id.getPath().endsWith(".png")
                ).keySet().stream()
                .sorted()
                .toList();
    }

    /** Width over height, straight from the PNG header so the image is not decoded twice. */
    private static float readAspectRatio(ResourceManager resourceManager, Identifier texture) throws IOException {
        try (InputStream stream = resourceManager.getResourceOrThrow(texture).open()) {
            ByteBuffer header = ByteBuffer.wrap(stream.readNBytes(24));
            if (header.limit() < 24 || header.getLong(0) != 0x89504E470D0A1A0AL) {
                throw new IOException(texture + " is not a PNG");
            }
            int width = header.getInt(16);
            int height = header.getInt(20);
            return (float) width / height;
        }
    }
}
