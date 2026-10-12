package io.github.asgerjon.trollingofundying;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * A toast, "Alert! Excessive skill issues detected!" over "Total skill issues
 * for NAME: N", in the style of vanilla's system toasts. Vanilla puts toasts
 * in the window's top-right corner, which on a triple-screen setup is the far
 * edge of the right-hand screen; this one sits in the top-right corner of the
 * center instead, {@link #HALF_WIDTH_PIXELS} window pixels right of the middle,
 * and slides down from the top rather than in from the side.
 */
public final class SkillIssueAlert {
    /** 1.5 hotbar widths at GUI scale 3, in window pixels, from the center to the toast's right edge. */
    private static final int HALF_WIDTH_PIXELS = 819;
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system");
    private static final int HEIGHT = 32;
    /** Vanilla's toast timing: a 0.6 s slide each way, 5 s on screen. */
    private static final int SLIDE_TICKS = 12;
    private static final int STAY_TICKS = 100;
    private static final int TOTAL_TICKS = SLIDE_TICKS + STAY_TICKS + SLIDE_TICKS;

    private static @Nullable Component title;
    private static @Nullable Component message;
    private static int age = TOTAL_TICKS;

    private SkillIssueAlert() {
    }

    static void show(String player, int count) {
        title = Component.empty()
                .append(Component.literal("Alert! ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                .append(Component.literal("Excessive skill issues detected!").withStyle(ChatFormatting.RED));
        message = Component.empty()
                .append(Component.literal("Total skill issues for ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(player).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(Integer.toString(count)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        age = 0;
    }

    static void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            age = TOTAL_TICKS;
        } else if (!minecraft.isPaused() && age < TOTAL_TICKS) {
            age++;
        }
    }

    static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (age >= TOTAL_TICKS || title == null || message == null) {
            return;
        }
        float time = age + (minecraft.isPaused() ? 0.0F : deltaTracker.getGameTimeDeltaPartialTick(false));
        float shown = time < SLIDE_TICKS ? time / SLIDE_TICKS
                : time > SLIDE_TICKS + STAY_TICKS ? (TOTAL_TICKS - time) / SLIDE_TICKS
                : 1.0F;
        shown = Mth.clamp(shown, 0.0F, 1.0F);
        shown = shown * shown * (3.0F - 2.0F * shown);

        Font font = minecraft.font;
        int width = 18 + Math.max(font.width(title), font.width(message)) + 10;
        int right = Math.min(
                graphics.guiWidth(), graphics.guiWidth() / 2 + HALF_WIDTH_PIXELS / minecraft.getWindow().getGuiScale()
        );
        int x = right - width;
        int y = Math.round(-HEIGHT * (1.0F - shown));
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, width, HEIGHT);
        graphics.text(font, title, x + 18, y + 7, 0xFFFFFFFF, false);
        graphics.text(font, message, x + 18, y + 18, 0xFFFFFFFF, false);
    }
}
