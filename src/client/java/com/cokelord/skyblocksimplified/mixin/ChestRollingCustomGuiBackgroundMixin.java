package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Companion to {@link ChestRollingCustomGuiRenderMixin} — cancels the real chest GUI's own background
 *  texture during the roll animation, same "cancel the real background TEXTURE, not just paint over it"
 *  mechanism already proven by TerminalCustomGuiBackgroundMixin/LeapMenuCustomGuiBackgroundMixin (see
 *  those classes' own doc comments for why the background needs its own separate cancellation call). */
@Mixin(ContainerScreen.class)
public class ChestRollingCustomGuiBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$cancelBackgroundForChestRolling(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (ChestRollingFeature.shouldReplaceRender(self)) {
			ci.cancel();
		}
	}
}
