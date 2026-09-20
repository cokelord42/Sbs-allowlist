package com.cokelord.skyblocksimplified.feature.impl;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** Bare data payload carried through vanilla's own tooltip-image pipeline (ItemStack.getTooltipImage() ->
 *  ClientTooltipComponent.create(TooltipComponent)) — TooltipComponent itself is an empty marker interface
 *  (confirmed via javap), same role BundleContents/BundleTooltip play for the vanilla Bundle item's own
 *  contents-preview tooltip, which this whole mechanism is modeled on. slotItems is zero-indexed and
 *  pre-resolved from NBT at capture time (ItemStackTooltipImageMixin only has the ItemStack there, not
 *  later), one entry per real compactor slot, null for an empty slot. */
public record PersonalCompactorTooltipData(boolean isDeletor, boolean active, int slotCount, String[] slotItems)
	implements TooltipComponent {
}
