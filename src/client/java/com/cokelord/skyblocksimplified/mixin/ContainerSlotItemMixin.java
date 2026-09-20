package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.inventory.SlotItemOverrideRegistry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets a feature substitute the ItemStack actually drawn for a slot (see SlotItemOverrideRegistry)
 *  without touching the real Slot/Menu state any click logic reads — only the first Slot.getItem() call
 *  in extractSlot (the one that fetches the rendered icon, confirmed via javap) is redirected; a later
 *  quick-craft-preview call to the same method further down in extractSlot is untouched (ordinal 0). */
@Mixin(AbstractContainerScreen.class)
public class ContainerSlotItemMixin {
	@Redirect(
		method = "extractSlot",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0)
	)
	private ItemStack skyblocksimplified$overrideRenderedItem(Slot slot) {
		return SlotItemOverrideRegistry.apply(slot, slot.getItem());
	}
}
