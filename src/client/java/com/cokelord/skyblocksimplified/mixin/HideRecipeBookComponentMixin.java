package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Companion to {@link HideRecipeBookButtonMixin} — also collapses the recipe book panel itself the
 *  instant a crafting screen opens, in case it was left open from a previous vanilla session before this
 *  build was installed. Always-on, no settings toggle, matching the user's explicit request. Ported from
 *  NoammAddons' confirmed {@code MixinRecipeBookComponent.java}. */
@Mixin(RecipeBookComponent.class)
public abstract class HideRecipeBookComponentMixin {
	@Shadow
	protected abstract void setVisible(boolean visible);

	@Inject(method = "init", at = @At("TAIL"))
	private void skyblocksimplified$hideRecipeBookComponent(CallbackInfo ci) {
		setVisible(false);
	}
}
