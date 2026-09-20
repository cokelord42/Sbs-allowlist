package com.cokelord.skyblocksimplified.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Reads Hypixel's per-item SkyBlock attributes (what used to be called "ExtraAttributes" NBT) off an
 *  ItemStack. Since MC's item-component rewrite, any NBT a server sends that doesn't map to a real
 *  vanilla component round-trips into the single minecraft:custom_data component as one compound tag —
 *  for Hypixel items that compound IS the old ExtraAttributes tag directly (holds "id", "uuid", and every
 *  per-item modifier key like "upgrade_level" or "PERSONAL_DELETOR_ACTIVE"), confirmed against SkyHanni's
 *  own SkyBlockItemModifierUtils.getExtraAttributes(), which reads the identical component with no extra
 *  nesting. */
public final class SkyblockNbtUtils {
	private SkyblockNbtUtils() {}

	public static CompoundTag getAttributes(ItemStack stack) {
		var customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null ? customData.copyTag() : new CompoundTag();
	}

	public static String getItemId(ItemStack stack) {
		return getAttributeString(stack, "id");
	}

	public static String getItemUuid(ItemStack stack) {
		return getAttributeString(stack, "uuid");
	}

	public static String getAttributeString(ItemStack stack, String key) {
		String value = getAttributes(stack).getStringOr(key, "");
		return value.isBlank() ? null : value;
	}

	public static Integer getAttributeInt(ItemStack stack, String key) {
		CompoundTag tag = getAttributes(stack);
		return tag.contains(key) ? tag.getIntOr(key, 0) : null;
	}

	public static Long getAttributeLong(ItemStack stack, String key) {
		CompoundTag tag = getAttributes(stack);
		return tag.contains(key) ? tag.getLongOr(key, 0L) : null;
	}

	public static byte getAttributeByte(ItemStack stack, String key) {
		return getAttributes(stack).getByteOr(key, (byte) 0);
	}

	/** The applied ability scroll(s) on a legendary sword like Hyperion — e.g. "WITHER_SHIELD_SCROLL",
	 *  "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL" (the fourth ability, Wither Impact, is the sword's
	 *  innate/default and never appears here since it needs no scroll). Real Hypixel key
	 *  ("ability_scroll", a string list under ExtraAttributes) per the same convention every reference
	 *  price-check tool reads it from. Empty if none applied. */
	public static java.util.List<String> getAbilityScrolls(ItemStack stack) {
		CompoundTag tag = getAttributes(stack);
		if (!tag.contains("ability_scroll")) return java.util.List.of();
		net.minecraft.nbt.ListTag list = tag.getListOrEmpty("ability_scroll");
		java.util.List<String> result = new java.util.ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			String value = list.getStringOr(i, "");
			if (!value.isBlank()) result.add(value);
		}
		return result;
	}

	/** True if this Aspect of the Void has been permanently merged with an Etherwarp Conduit — a real,
	 *  one-way upgrade that meaningfully changes the item's value. Real Hypixel key ("ethermerge", an int
	 *  flag under ExtraAttributes) per the same convention every reference price-check tool reads it from. */
	public static boolean isEtherMerged(ItemStack stack) {
		Integer value = getAttributeInt(stack, "ethermerge");
		return value != null && value != 0;
	}

	/** The raw "petInfo" JSON string Hypixel stores a pet's real species/tier/level/held-item/skin/candy
	 *  data under — a pet's own top-level "id" attribute is always the generic literal "PET", so this is
	 *  the only place its actual identity lives. Null if this isn't a pet item at all. Caller parses the
	 *  JSON itself (this class only reads raw NBT strings, no JSON dependency of its own). */
	public static String getPetInfoJson(ItemStack stack) {
		return getAttributeString(stack, "petInfo");
	}
}
