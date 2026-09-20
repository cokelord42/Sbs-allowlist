package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Always removes the recipe book toggle button from every crafting-style inventory screen — per user
 *  request ("Hide recipe book, this should be in the mod originally without a module, it should just
 *  always hide recipe book"), always-on with no settings toggle. Ported from NoammAddons' confirmed
 *  {@code MixinAbstractRecipeBookScreen.java} (its own {@code hideRecipeBook} toggle setting is
 *  deliberately not carried over, matching the user's explicit "no module" ask). Cancels the private
 *  {@code initButton()} call {@code init()} makes, so the button widget is never created/registered at all
 *  rather than just hidden after the fact. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class HideRecipeBookButtonMixin {
	@Inject(method = "init", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;initButton()V"), cancellable = true)
	private void skyblocksimplified$hideRecipeBookButton(CallbackInfo ci) {
		ci.cancel();
	}
}
