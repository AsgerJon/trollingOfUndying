package io.github.asgerjon.trollingofundying.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.ItemActivation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

/**
 * Records the vanilla totem pop, one screenshot per tick, for comparing
 * custom animations against. Run with {@code ./gradlew runClientGameTest};
 * the files land in {@code run/totem_capture/vanilla/}:
 * <ul>
 *     <li>{@code reference.png}: the view just before the fall,</li>
 *     <li>{@code frame_00.png} ...: one per tick while the totem is on screen,
 *     rendered exactly on the tick,</li>
 *     <li>{@code background_00.png} ...: the same tick rendered without the
 *     totem, so the two differ only in the totem's own pixels,</li>
 *     <li>{@code after.png}: the first tick after the animation ends,</li>
 *     <li>{@code capture.json}: window size, GUI scale and the pop's random
 *     offset, plus vanilla's ticks-left counter for every frame.</li>
 * </ul>
 * The player stands on a stone pad in a void superflat world, looking
 * straight ahead, and is teleported 25 blocks up so the landing pops the
 * offhand totem. View bobbing, damage tilt and clouds are off, so only the
 * pop and its HUD changes differ from the reference.
 */
public class VanillaPopCapture implements FabricClientGameTest {
    /** While true, vanilla skips drawing the pop (see ScreenEffectRendererMixin). */
    public static volatile boolean hideTotem;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path folder = Path.of(System.getProperty("trollingofundying.captureDir", "totem_capture")).resolve("vanilla");
        clearFolder(folder);
        context.runOnClient(client -> {
            client.options.bobView().set(false);
            client.options.damageTiltStrength().set(0.0);
            client.options.cloudStatus().set(CloudStatus.OFF);
        });
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(VanillaPopCapture::useVoidPreset).create()) {
            TestServerContext server = world.getServer();
            server.runCommand("execute at @p align xyz run fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 stone");
            server.runCommand("execute at @p align xyz run tp @p ~0.5 ~ ~0.5 0 0");
            server.runCommand("gamemode survival @p");
            server.runCommand("effect clear @p");
            server.runCommand("item replace entity @p weapon.offhand with totem_of_undying");
            // A fresh player earns "Postmortal" on the pop; earn it now, quietly, so
            // neither its toast nor its chat line ends up in the frames.
            server.runCommand("gamerule show_advancement_messages false");
            server.runCommand("advancement grant @p only minecraft:adventure/totem_of_undying");
            world.getConnection().waitForChunksRender();
            context.waitTicks(20);
            context.runOnClient(client -> client.gui.toastManager().clear());
            screenshot(context, folder, "reference");

            // Frozen, the world (the pop's countdown and the particles included)
            // only moves on "tick step", so a screenshot that takes more than a
            // tick to read back can't skip one. Players still move, so the fall
            // and the pop happen as usual.
            server.runCommand("tick freeze");
            context.waitFor(client -> !client.level.tickRateManager().runsNormally());
            server.runCommand("execute at @p run tp @p ~ ~25 ~");
            context.waitFor(client -> client.player != null && client.player.itemActivation().isActive(), 200);

            JsonArray frames = new JsonArray();
            JsonObject info = new JsonObject();
            while (true) {
                int ticksLeft = context.computeOnClient(client -> {
                    ItemActivation activation = client.player.itemActivation();
                    info.addProperty("off_x", activation.offX());
                    info.addProperty("off_y", activation.offY());
                    return activation.isActive() ? activation.ticks() : 0;
                });
                if (ticksLeft == 0) {
                    break;
                }
                String name = "frame_%02d".formatted(frames.size());
                screenshot(context, folder, name);
                // Same frozen tick, minus the totem: particles and HUD match exactly.
                String background = "background_%02d".formatted(frames.size());
                hideTotem = true;
                try {
                    screenshot(context, folder, background);
                } finally {
                    hideTotem = false;
                }
                JsonObject frame = new JsonObject();
                frame.addProperty("file", name + ".png");
                frame.addProperty("background", background + ".png");
                frame.addProperty("ticks_left", ticksLeft);
                frames.add(frame);
                // screenshot() only returns once the PNG is on disk, so this is
                // the "ready for the next tick" signal.
                server.runCommand("tick step 1");
                context.waitFor(client -> client.player.itemActivation().ticks() != ticksLeft);
            }
            screenshot(context, folder, "after");
            server.runCommand("tick unfreeze");

            context.runOnClient(client -> {
                info.addProperty("window_width", client.getWindow().getWidth());
                info.addProperty("window_height", client.getWindow().getHeight());
                info.addProperty("gui_scale", client.getWindow().getGuiScale());
                info.addProperty("fov", client.options.fov().get());
            });
            info.add("frames", frames);
            Files.writeString(folder.resolve("capture.json"), new GsonBuilder().setPrettyPrinting().create().toJson(info));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void useVoidPreset(WorldCreationUiState state) {
        FlatLevelGeneratorSettings voidSettings = state.getSettings().worldgenLoadContext()
                .lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                .getOrThrow(FlatLevelGeneratorPresets.THE_VOID)
                .value()
                .settings();
        state.updateDimensions(PresetEditor.flatWorldConfigurator(voidSettings));
    }

    /** Renders exactly on the tick (no partial tick), so frame N is vanilla's tick N. */
    private static void screenshot(ClientGameTestContext context, Path folder, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDeltaTicks(0.0F).withDestinationDir(folder));
    }

    private static void clearFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
