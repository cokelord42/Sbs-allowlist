package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Companion to {@link LeapMenuCustomGuiRenderMixin} — same "cancel the real background TEXTURE, not just
 *  paint over it" fix already proven by {@link TerminalCustomGuiBackgroundMixin} for Terminal Solver's
 *  Custom GUI mode (see that class's own doc comment for why the background texture needs its own separate
 *  cancellation — it's a different top-level render call than the slots/labels). Per user report ("Leap
 *  menu is rendering a black box over the original gui instead of hiding it"): the previous approach drew
 *  an opaque rect via {@code ScreenEvents.afterExtract}, which runs AFTER the real vanilla chest frame
 *  already rendered — that's paint-over, not cancellation. This actually cancels the real render. */
@Mixin(ContainerScreen.class)
public class LeapMenuCustomGuiBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$cancelBackgroundForLeapMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (LeapMenuFeature.shouldReplaceRender(self)) {
			ci.cancel();
		}
	}
}
