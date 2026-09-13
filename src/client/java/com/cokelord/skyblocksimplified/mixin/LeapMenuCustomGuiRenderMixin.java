package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the real vanilla Leap Menu chest GUI's slots/labels/tooltip render and draws
 *  {@link LeapMenuFeature}'s own quadrant boxes instead — same cancel-and-replace mechanism as
 *  {@link TerminalCustomGuiRenderMixin}/{@link TerminalCustomGuiBackgroundMixin} (see that class's doc
 *  comment for the full extractRenderState/extractBackground call-chain reasoning), ported to fix the same
 *  class of "black box painted over the original GUI instead of replacing it" bug reported for Leap Menu. */
@Mixin(AbstractContainerScreen.class)
public class LeapMenuCustomGuiRenderMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$replaceRenderForLeapMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (LeapMenuFeature.shouldReplaceRender(self)) {
			LeapMenuFeature.renderReplacement(graphics, self);
			ci.cancel();
		}
	}
}
