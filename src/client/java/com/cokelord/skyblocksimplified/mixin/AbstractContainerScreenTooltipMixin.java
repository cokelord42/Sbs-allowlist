package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry;
import com.cokelord.skyblocksimplified.gui.TooltipSuppressRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * extractRenderState() calls extractContents() -> extractCarriedItem() -> extractTooltip(), in that order
 * (confirmed via decompiling AbstractContainerScreen) — so injecting at extractTooltip's HEAD is the one
 * point after every slot/item icon is drawn but before the tooltip itself, letting PreTooltipRenderRegistry
 * listeners draw underneath the tooltip instead of on top of it (see that class's doc comment), and now
 * also letting TooltipSuppressRegistry cancel the tooltip outright (Terminal Solver's blackout mode: a real
 * item's name/lore showing on hover would defeat the point of hiding it).
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenTooltipMixin {
	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$firePreTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (TooltipSuppressRegistry.shouldSuppress(self, mouseX, mouseY)) {
			ci.cancel();
			return;
		}
		PreTooltipRenderRegistry.fire(graphics, self, mouseX, mouseY);
	}
}
