package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.HideFireFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels just the first-person "on fire" screen overlay — submitFire is a self-contained private
 *  static method (confirmed via the game jar's own method list) with no other overlay's logic in it, so
 *  cancelling it can't affect the underwater/pumpkin/item-activation overlays that share the same
 *  ScreenEffectRenderer class. */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectFireMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void skyblocksimplified$hideFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (FeatureRegistry.get("hide_fire") instanceof HideFireFeature feature && feature.isEnabled()) {
			ci.cancel();
		}
	}
}
