package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the real chest GUI's slots/labels/tooltip render and draws {@link ChestRollingFeature}'s own
 *  scrolling-strip animation instead — same cancel-and-replace mechanism as
 *  TerminalCustomGuiRenderMixin/LeapMenuCustomGuiRenderMixin (see those classes' own doc comments for the
 *  full extractRenderState/extractBackground call-chain reasoning). Real slot clicks are separately vetoed
 *  by ContainerClickRegistry (see ChestRollingFeature.onEnable) so the player can't grab/move the real
 *  winner item mid-animation even though the underlying container screen is still technically open. */
@Mixin(AbstractContainerScreen.class)
public class ChestRollingCustomGuiRenderMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$replaceRenderForChestRolling(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (ChestRollingFeature.shouldReplaceRender(self)) {
			ChestRollingFeature.renderReplacement(graphics, self);
			ci.cancel();
		}
	}
}
