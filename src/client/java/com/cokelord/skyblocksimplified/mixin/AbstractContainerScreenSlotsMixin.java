package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * extractContents() calls extractSlotHighlightBack() -> extractSlots() -> extractSlotHighlightFront(), in
 * that order (confirmed via decompiling AbstractContainerScreen), all inside the same
 * pose().translate(leftPos, topPos) block — so injecting at extractSlots' HEAD is the one point BEFORE any
 * slot/item icon is drawn, letting PreItemRenderRegistry listeners draw underneath item icons instead of on
 * top of them (see that class's own doc comment for why this exists and its raw-slot-coordinate contract).
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenSlotsMixin {
	@Inject(method = "extractSlots", at = @At("HEAD"))
	private void skyblocksimplified$firePreItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		PreItemRenderRegistry.fire(graphics, (AbstractContainerScreen<?>) (Object) this, mouseX, mouseY);
	}
}
