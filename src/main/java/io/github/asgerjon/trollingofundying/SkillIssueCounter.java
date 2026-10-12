package io.github.asgerjon.trollingofundying;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts the totems a player has popped in a world, and brands the total in
 * red to the left of the hotbar from the first pop on. Every singleplayer
 * world (by save folder) and every server (by address) keeps its own count
 * for each player (by UUID), in {@code config/trollingofundying.json}:
 * <pre>{"skill_issues": {"singleplayer:New World": {"&lt;uuid&gt;": 3}, "server:example.com": {...}}}</pre>
 * <p>
 * A pop is saved at once, but shown only a second after the pop's animation
 * is over: the classic "oof" hurt sound (in the game's assets, unused since
 * Beta 1.9) plays and the new number lands big, shaking and flashing, then
 * settles back into place. {@link SkillIssueAlert} announces the new count
 * in a toast right away, at the pop itself. Vanilla's "NAME has reached the goal [Postmortal]" chat line
 * for the player is dropped, since the alert covers it.
 */
public final class SkillIssueCounter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrollingOfUndyingClient.MOD_ID);
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    /** Width of the hotbar, and of the offhand slot drawn to its left. */
    private static final int HOTBAR_HALF_WIDTH = 91;
    private static final int OFFHAND_WIDTH = 29;
    private static final int GAP = 4;
    /** {@code minecraft:random/classic_hurt}, see assets/trollingofundying/sounds.json. */
    private static final SoundEvent OOF = SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath(TrollingOfUndyingClient.MOD_ID, "skill_issue")
    );
    /** How long after the pop's animation the oof plays. */
    public static final int REVEAL_DELAY_TICKS = 20;
    private static final int REVEAL_TICKS = 40;
    private static final float REVEAL_START_SCALE = 2.5F;

    /** World key, then player UUID, to the number of pops. */
    private static final Map<String, Map<String, Integer>> COUNTS = new TreeMap<>();
    /** The world and player that {@link #shownCount} belongs to. */
    private static @Nullable String shownKey;
    private static int shownCount;
    /** Ticks until the oof, or 0 when nothing is waiting. */
    private static int ticksToReveal;
    /** Ticks since the oof, or {@link #REVEAL_TICKS} once the show is over. */
    private static int ticksSinceReveal = REVEAL_TICKS;

    private SkillIssueCounter() {
    }

    static void load() {
        JsonElement worlds = ConfigFile.read().get("skill_issues");
        // Before counts were per world, "skill_issues" was one number; there is no telling which world it was for.
        if (worlds == null || !worlds.isJsonObject()) {
            return;
        }
        try {
            for (Map.Entry<String, JsonElement> world : worlds.getAsJsonObject().entrySet()) {
                Map<String, Integer> players = COUNTS.computeIfAbsent(world.getKey(), key -> new TreeMap<>());
                for (Map.Entry<String, JsonElement> player : world.getValue().getAsJsonObject().entrySet()) {
                    players.put(player.getKey(), player.getValue().getAsInt());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the skill issue counts in {}", ConfigFile.path(), e);
        }
    }

    private static void save() {
        JsonObject worlds = new JsonObject();
        COUNTS.forEach((world, players) -> {
            JsonObject counts = new JsonObject();
            players.forEach(counts::addProperty);
            worlds.add(world, counts);
        });
        ConfigFile.put("skill_issues", worlds);
    }

    /** The singleplayer save folder or the server's address, or null outside a world. */
    private static @Nullable String worldKey(Minecraft minecraft) {
        IntegratedServer singleplayer = minecraft.getSingleplayerServer();
        if (singleplayer != null) {
            Path folder = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return "singleplayer:" + folder;
        }
        ServerData server = minecraft.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.isRealm() ? "realms:" + server.name : "server:" + server.ip;
    }

    private static int count(String world, String player) {
        return COUNTS.getOrDefault(world, Map.of()).getOrDefault(player, 0);
    }

    /**
     * Called on every totem pop of the player's own. The new count is shown
     * {@code animationTicks} plus {@link #REVEAL_DELAY_TICKS} ticks from now.
     */
    public static void increment(int animationTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        String world = worldKey(minecraft);
        if (world == null || minecraft.player == null) {
            return;
        }
        int count = COUNTS.computeIfAbsent(world, key -> new TreeMap<>()).merge(minecraft.player.getStringUUID(), 1, Integer::sum);
        save();
        SkillIssueAlert.show(minecraft.player.getName().getString(), count);
        ticksToReveal = animationTicks + REVEAL_DELAY_TICKS;
    }

    static void tick(Minecraft minecraft) {
        String world = worldKey(minecraft);
        if (minecraft.player == null || world == null) {
            // Left the world mid-wait: the next world shows its own count, quietly.
            shownKey = null;
            ticksToReveal = 0;
            ticksSinceReveal = REVEAL_TICKS;
            return;
        }
        String key = world + "/" + minecraft.player.getStringUUID();
        if (!key.equals(shownKey)) {
            shownKey = key;
            shownCount = count(world, minecraft.player.getStringUUID());
        }
        if (minecraft.isPaused()) {
            return;
        }
        if (ticksSinceReveal < REVEAL_TICKS) {
            ticksSinceReveal++;
        }
        if (ticksToReveal > 0 && --ticksToReveal == 0) {
            shownCount = count(world, minecraft.player.getStringUUID());
            ticksSinceReveal = 0;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(OOF, 1.0F));
        }
    }

    /** Whether a chat line is vanilla's "NAME has reached the goal [Postmortal]" about this player. */
    static boolean isOwnPostmortal(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(message.getContents() instanceof TranslatableContents announcement)
                || !announcement.getKey().startsWith("chat.type.advancement.") || announcement.getArgs().length < 2) {
            return false;
        }
        Object[] args = announcement.getArgs();
        return args[0] instanceof Component name && name.getString().equals(player.getName().getString())
                && args[1] instanceof Component advancement && mentions(advancement, "advancements.adventure.totem_of_undying.title");
    }

    private static boolean mentions(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (translatable.getKey().equals(key)) {
                return true;
            }
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component inner && mentions(inner, key)) {
                    return true;
                }
            }
        }
        return component.getSiblings().stream().anyMatch(sibling -> mentions(sibling, key));
    }

    static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (shownKey == null || shownCount == 0 || player == null || player.isSpectator()) {
            return;
        }
        // Right-aligned against the hotbar, or against the offhand slot when it's showing on the left.
        int right = graphics.guiWidth() / 2 - HOTBAR_HALF_WIDTH - GAP;
        if (!player.getOffhandItem().isEmpty() && player.getMainArm().getOpposite() == HumanoidArm.LEFT) {
            right -= OFFHAND_WIDTH;
        }
        Font font = minecraft.font;
        String number = Integer.toString(shownCount);
        List<String> lines = List.of("Skill issue counter: " + number);
        if (font.width(lines.getFirst()) > right - GAP) {
            lines = List.of("Skill issue", "counter: " + number);
        }
        int width = lines.stream().mapToInt(font::width).max().orElse(0);
        float scale = Math.min(1.0F, (float) (right - GAP) / width);

        // The show after the oof: starts big and eases back, shaking and
        // flashing as it goes. It grows up and to the left, away from the hotbar.
        int color = RED;
        float shake = 0.0F;
        if (ticksSinceReveal < REVEAL_TICKS) {
            float time = ticksSinceReveal + (minecraft.isPaused() ? 0.0F : deltaTracker.getGameTimeDeltaPartialTick(false));
            float left = 1.0F - Mth.clamp(time / REVEAL_TICKS, 0.0F, 1.0F);
            scale *= 1.0F + (REVEAL_START_SCALE - 1.0F) * left * left * left;
            shake = 3.0F * left * Mth.sin(time * 2.5F);
            if (left > 0.25F && ticksSinceReveal / 3 % 2 == 0) {
                color = YELLOW;
            }
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate(right + shake, graphics.guiHeight() - 2);
        graphics.pose().scale(scale, scale);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int y = -(lines.size() - i) * (font.lineHeight + 1);
            graphics.text(font, line, -font.width(line), y, color, true);
        }
        graphics.pose().popMatrix();
    }
}
