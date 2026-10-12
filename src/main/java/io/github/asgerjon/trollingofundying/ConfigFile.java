package io.github.asgerjon.trollingofundying;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code config/trollingofundying.json}, where the mod keeps what it
 * remembers between sessions. Each part of the mod owns one top-level key
 * ({@code skill_issues}, {@code last_animation}), and saving one leaves the
 * others as they are.
 */
final class ConfigFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrollingOfUndyingClient.MOD_ID);

    private ConfigFile() {
    }

    static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(TrollingOfUndyingClient.MOD_ID + ".json");
    }

    /** The whole file, or an empty object when it is missing or unreadable. */
    static JsonObject read() {
        Path file = path();
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}", file, e);
            return new JsonObject();
        }
    }

    /** Sets one top-level key and saves, keeping the rest of the file. */
    static void put(String key, JsonElement value) {
        JsonObject json = read();
        json.add(key, value);
        try {
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            LOGGER.error("Could not save {}", path(), e);
        }
    }
}
