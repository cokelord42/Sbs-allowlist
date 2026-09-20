package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ported from Odin's {@code LocalPlayerMixin.java}: OR's the vanilla sprint-key read with the Auto Sprint toggle. */
@Mixin(LocalPlayer.class)
public abstract class AutoSprintMixin {
	@ModifyExpressionValue(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Input;sprint()Z"))
	private boolean skyblocksimplified$autoSprint(boolean original) {
		Feature feature = FeatureRegistry.get("auto_sprint");
		return original || (feature != null && feature.isEnabled());
	}
}
