package com.cokelord.skyblocksimplified.inventory;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets a feature substitute the ItemStack actually drawn for a container slot, consumed by
 * ContainerSlotItemMixin's redirect on the one Slot.getItem() call AbstractContainerScreen.extractSlot
 * uses to fetch its render icon — real click/menu logic elsewhere still sees the real item, only this
 * render call site is affected. Backs the Superpairs module's "keep items visible" behavior (substitutes
 * back the last real item a slot showed after Hypixel re-hides it behind its own "?" placeholder) and
 * Croesus's "Hide Claimed Chests" (substitutes ItemStack.EMPTY for an already-claimed run's item so it
 * doesn't render at all). Id-keyed like ContainerClickRegistry/SoundMuteRegistry — first non-null override
 * wins, exception-isolated per rule — since more than one feature can plausibly want this at once now.
 */
public final class SlotItemOverrideRegistry {
	private SlotItemOverrideRegistry() {}

	@FunctionalInterface
	public interface SlotOverride {
		/** Return null to leave the real item alone (let a later-registered rule, or the real item, win). */
		ItemStack override(Slot slot, ItemStack real);
	}

	private static final Map<String, SlotOverride> overrides = new LinkedHashMap<>();

	public static void set(String id, SlotOverride override) {
		overrides.put(id, override);
	}

	public static void clear(String id) {
		overrides.remove(id);
	}

	public static ItemStack apply(Slot slot, ItemStack real) {
		for (Map.Entry<String, SlotOverride> entry : overrides.entrySet()) {
			try {
				ItemStack result = entry.getValue().override(slot, real);
				if (result != null) return result;
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"SlotItemOverrideRegistry rule \"{}\" threw, ignoring its override for this slot", entry.getKey(), e);
			}
		}
		return real;
	}
}
