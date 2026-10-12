package io.github.asgerjon.trollingofundying.mixin;

import io.github.asgerjon.trollingofundying.SkillIssueCounter;
import io.github.asgerjon.trollingofundying.TotemAnimation;
import io.github.asgerjon.trollingofundying.TotemAnimationLoader;
import io.github.asgerjon.trollingofundying.TotemAnimationPlayer;
import net.minecraft.client.player.ItemActivation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the skill issue and starts the next of the packs' animations in the
 * rotation whenever a death protection item (the totem) saves the player,
 * optionally instead of the spinning item.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void trollingofundying$playCustomAnimation(ItemStack itemStack, CallbackInfo ci) {
        if (!itemStack.has(DataComponents.DEATH_PROTECTION)) {
            return;
        }
        TotemAnimation animation = TotemAnimationLoader.take();
        // The counter's oof waits for whatever is on screen to finish: the
        // pack's animation, vanilla's spinning totem, or both.
        int vanillaTicks = ItemActivation.ANIMATION_LENGTH;
        SkillIssueCounter.increment(
                animation == null ? vanillaTicks
                        : animation.hideVanillaItem() ? animation.durationTicks()
                        : Math.max(vanillaTicks, animation.durationTicks())
        );
        if (animation == null) {
            return;
        }
        TotemAnimationPlayer.start(animation);
        if (animation.hideVanillaItem()) {
            ci.cancel();
        }
    }
}
