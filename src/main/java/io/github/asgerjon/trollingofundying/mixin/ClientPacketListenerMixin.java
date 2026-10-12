package io.github.asgerjon.trollingofundying.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.asgerjon.trollingofundying.TotemAnimation;
import io.github.asgerjon.trollingofundying.TotemAnimationLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a pack switch off the totem particles and sound, but only for the
 * player's own totem: other players popping theirs still look vanilla.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @WrapWithCondition(
            method = "handleEntityEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V"
            )
    )
    private boolean trollingofundying$maybeHideParticles(
            ParticleEngine particleEngine, Entity target, ParticleOptions particle, int lifeTime, @Local Entity entity
    ) {
        TotemAnimation animation = TotemAnimationLoader.upcoming();
        return !(animation != null && animation.hideVanillaParticles() && entity == Minecraft.getInstance().player);
    }

    @WrapWithCondition(
            method = "handleEntityEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"
            )
    )
    private boolean trollingofundying$maybeMuteSound(
            ClientLevel level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch,
            boolean distanceDelay, @Local Entity entity
    ) {
        TotemAnimation animation = TotemAnimationLoader.upcoming();
        return !(animation != null && animation.silencesVanillaSound() && entity == Minecraft.getInstance().player);
    }
}
