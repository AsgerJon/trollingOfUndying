package io.github.asgerjon.trollingofundying;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Plays one {@link TotemAnimation} at a time on top of the HUD. Time is
 * counted in client ticks that are not paused, so the frames stay in step
 * with the sound when the game is paused mid-animation.
 * <p>
 * A pack can open with the vanilla totem sound: it plays for
 * {@code vanilla_sound_ticks}, fades out, and the pack's own sound takes over.
 */
public final class TotemAnimationPlayer {
    /** The frame is drawn as a quad this many units across, then scaled to size. */
    private static final int QUAD = 1024;

    private static @Nullable TotemAnimation playing;
    private static @Nullable SoundInstance sound;
    private static @Nullable FadingSound vanillaSound;
    private static int ticks;
    private static float randomX;
    private static float randomY;
    private static boolean preloadPending;

    private TotemAnimationPlayer() {
    }

    public static void start(TotemAnimation animation) {
        stop();
        playing = animation;
        ticks = 0;
        RandomSource random = RandomSource.create();
        randomX = random.nextFloat() * 2.0F - 1.0F;
        randomY = random.nextFloat() * 2.0F - 1.0F;
        if (animation.vanillaSoundTicks() > 0) {
            vanillaSound = new FadingSound(animation.vanillaSoundTicks(), animation.vanillaSoundFadeTicks());
            Minecraft.getInstance().getSoundManager().play(vanillaSound);
        } else {
            playSound(animation);
        }
    }

    private static void playSound(TotemAnimation animation) {
        if (animation.sound() != null) {
            sound = new SimpleSoundInstance(
                    animation.sound(), animation.soundSource(), animation.soundVolume(), animation.soundPitch(),
                    RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true
            );
            Minecraft.getInstance().getSoundManager().play(sound);
        }
    }

    /** Ends the animation early, sounds included. */
    public static void stop() {
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        if (vanillaSound != null) {
            Minecraft.getInstance().getSoundManager().stop(vanillaSound);
        }
        playing = null;
        sound = null;
        vanillaSound = null;
    }

    /** Ends the animation on schedule, leaving the sounds to finish if the pack asks for that. */
    private static void finish() {
        if (playing != null && !playing.stopSoundAtEnd()) {
            sound = null;
            vanillaSound = null;
        }
        stop();
    }

    /** Asks for the frame textures to be uploaded on the next tick, so the first play does not stutter. */
    static void preloadFrames() {
        preloadPending = true;
    }

    static void tick(Minecraft minecraft) {
        if (preloadPending && minecraft.gui.overlay() == null) {
            preloadPending = false;
            for (TotemAnimation animation : TotemAnimationLoader.all().values()) {
                animation.frames().forEach(minecraft.getTextureManager()::getTexture);
            }
        }
        if (playing == null) {
            return;
        }
        if (minecraft.player == null) {
            stop();
        } else if (!minecraft.isPaused()) {
            ticks++;
            if (ticks == playing.vanillaSoundTicks()) {
                playSound(playing);
            }
            if (ticks >= playing.durationTicks()) {
                finish();
            }
        }
    }

    static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        TotemAnimation animation = playing;
        if (animation == null) {
            return;
        }
        float time = ticks + deltaTracker.getGameTimeDeltaPartialTick(false);
        TotemAnimation.Pose pose = animation.poseAt(Math.min(time / animation.durationTicks(), 1.0F), randomX, randomY);
        float alpha = Mth.clamp(pose.alpha() * animation.alpha(), 0.0F, 1.0F);
        if (alpha <= 0.0F) {
            return;
        }
        float height = pose.size() * graphics.guiHeight();
        float width = height * animation.aspectRatio();
        // The frames wait for the pack's sound, so a vanilla sound intro doesn't put them out of sync.
        int frameIndex = animation.frameIndexAt(Math.max(0.0F, time - animation.vanillaSoundTicks()) / 20.0F);
        Identifier frame = animation.frames().get(frameIndex);

        graphics.pose().pushMatrix();
        // Offsets are in window pixels; the GUI is drawn guiScale window pixels per unit.
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        graphics.pose().translate(
                pose.x() * graphics.guiWidth() + pose.offsetX() / guiScale,
                pose.y() * graphics.guiHeight() + pose.offsetY() / guiScale
        );
        graphics.pose().rotate(pose.rotation() * Mth.DEG_TO_RAD);
        // Mirror through the texture coordinates (u from 1 to 0), never a negative
        // scale: a mirrored quad faces away from the camera and is culled.
        boolean mirrored = pose.flip() != (pose.scaleX() < 0.0F);
        graphics.pose().scale(width * Math.abs(pose.scaleX()) / QUAD, height / QUAD);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, frame, -QUAD / 2, -QUAD / 2, mirrored ? 1.0F : 0.0F, 0.0F,
                QUAD, QUAD, mirrored ? -1 : 1, 1, 1, 1, ARGB.white(alpha)
        );
        if (animation.playerFace() != null) {
            renderPlayerFace(graphics, animation, animation.playerFace(), frameIndex, mirrored, alpha);
        }
        graphics.pose().popMatrix();
    }

    /**
     * Draws the player's face onto the current frame. The pose is already the
     * frame's, where the frame spans from -QUAD/2 to QUAD/2 both ways, so the face
     * follows the frame's position, size, spin and fade.
     */
    private static void renderPlayerFace(
            GuiGraphicsExtractor graphics, TotemAnimation animation, TotemAnimation.PlayerFace face, int frameIndex, boolean mirrored,
            float alpha
    ) {
        TotemAnimation.FaceSpot spot = face.spotAt(frameIndex);
        Minecraft minecraft = Minecraft.getInstance();
        if (spot == null || minecraft.player == null) {
            return;
        }
        Identifier skin = minecraft.player.getSkin().body().texturePath();
        int color = ARGB.white(Mth.clamp(alpha * face.alpha(), 0.0F, 1.0F));
        int size = Math.round(spot.size() * QUAD);
        // A mirrored frame mirrors the spot, the tilt and the face with it.
        float x = mirrored ? 1.0F - spot.x() : spot.x();
        graphics.pose().pushMatrix();
        graphics.pose().translate((x - 0.5F) * QUAD, (spot.y() - 0.5F) * QUAD);
        // Undo the frame's aspect ratio, so the face stays square and tilts without shearing.
        graphics.pose().scale(1.0F / animation.aspectRatio(), 1.0F);
        graphics.pose().rotate((mirrored ? -spot.rotation() : spot.rotation()) * Mth.DEG_TO_RAD);
        float u = mirrored ? 16.0F : 8.0F;
        int uWidth = mirrored ? -8 : 8;
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, -size / 2, -size / 2, u, 8.0F, size, size, uWidth, 8, 64, 64, color);
        if (face.hat()) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, skin, -size / 2, -size / 2, u + 32.0F, 8.0F, size, size, uWidth, 8, 64, 64, color);
        }
        graphics.pose().popMatrix();
    }

    /**
     * The vanilla totem sound, as vanilla plays it, fading out after
     * {@code playTicks} over {@code fadeTicks}: quickly at first, then trailing off.
     */
    private static final class FadingSound extends AbstractTickableSoundInstance {
        private final int playTicks;
        private final int fadeTicks;
        private int age;

        FadingSound(int playTicks, int fadeTicks) {
            super(SoundEvents.TOTEM_USE, SoundSource.PLAYERS, RandomSource.create());
            this.playTicks = playTicks;
            this.fadeTicks = fadeTicks;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            this.age++;
            if (this.age > this.playTicks) {
                float left = Math.max(0.0F, 1.0F - (float) (this.age - this.playTicks) / this.fadeTicks);
                this.volume = left * left;
                if (this.volume <= 0.0F) {
                    this.stop();
                }
            }
        }
    }
}
