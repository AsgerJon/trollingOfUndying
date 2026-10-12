package io.github.asgerjon.trollingofundying.gametest.mixin;

import io.github.asgerjon.trollingofundying.gametest.VanillaPopCapture;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the capture render a tick without the spinning totem, to diff against. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void trollingofundying$hideTotem(CallbackInfo ci) {
        if (VanillaPopCapture.hideTotem) {
            ci.cancel();
        }
    }
}
