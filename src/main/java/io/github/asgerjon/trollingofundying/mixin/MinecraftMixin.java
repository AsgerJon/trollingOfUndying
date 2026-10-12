package io.github.asgerjon.trollingofundying.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renames the window ("Minecraft 26.3 - Singleplayer") when the JVM is started
 * with {@code -Dtrollingofundying.windowTitle=<name>}. Only the game test run
 * in build.gradle sets it, so its window can be told apart from a real game.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void trollingofundying$renameWindow(CallbackInfoReturnable<String> cir) {
        String name = System.getProperty("trollingofundying.windowTitle");
        if (name != null && cir.getReturnValue().startsWith("Minecraft")) {
            cir.setReturnValue(name + cir.getReturnValue().substring("Minecraft".length()));
        }
    }
}
