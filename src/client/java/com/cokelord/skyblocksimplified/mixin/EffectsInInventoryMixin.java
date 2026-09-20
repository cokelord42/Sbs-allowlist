package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Always hides vanilla's potion-effect icon column next to inventory-family screens — per user request,
 * unconditional and automatic (no feature toggle): the column visually collides with this mod's own
 * NEU-style inventory buttons and cooldown/estimated-value overlays, and nothing about it is Skyblock-
 * specific enough to be worth keeping configurable.
 *
 * Cancelling extractRenderState directly, not just overriding canSeeEffects()'s return value — confirmed
 * via decompiling InventoryScreen/EffectsInInventory that extractRenderState() never actually calls
 * canSeeEffects() at all: it independently recomputes the exact same "is there room" condition inline from
 * screen.leftPos/imageWidth/width, bypassing our override completely. canSeeEffects() is only consulted
 * elsewhere (screen-width layout decisions via showsActiveEffects()), so it's still overridden too, but
 * that alone was why the column kept rendering regardless.
 */
@Mixin(EffectsInInventory.class)
public class EffectsInInventoryMixin {
	@Inject(method = "canSeeEffects", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$hideEffects(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$cancelRender(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
		ci.cancel();
	}
}
