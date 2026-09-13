package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes AbstractContainerScreen's protected leftPos/topPos so features can compute a slot's real
 *  on-screen pixel position (slot.x/slot.y are container-relative) to draw highlights over it. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int skyblocksimplified$getLeftPos();

	@Accessor("topPos")
	int skyblocksimplified$getTopPos();

	@Accessor("titleLabelX")
	int skyblocksimplified$getTitleLabelX();

	@Accessor("titleLabelY")
	int skyblocksimplified$getTitleLabelY();

	@Accessor("imageWidth")
	int skyblocksimplified$getImageWidth();

	@Accessor("imageHeight")
	int skyblocksimplified$getImageHeight();

	@Accessor("hoveredSlot")
	Slot skyblocksimplified$getHoveredSlot();
}
