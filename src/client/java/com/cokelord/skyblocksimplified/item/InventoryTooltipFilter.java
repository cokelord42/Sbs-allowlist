package com.cokelord.skyblocksimplified.item;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Tooltip listener for Show Item Quality — the only remaining Inventory-category feature that actually
 * belongs on the item's own tooltip. Personal Compactor Overlay and Estimated Item Value used to live
 * here too, appending lines directly to the hovered item's tooltip; that made both balloon the tooltip's
 * height and sit on top of/obscure whatever was under the cursor (per user report), so both were rebuilt
 * as their own separate movable panels instead — see PersonalCompactorOverlayFeature and
 * EstimatedItemValueFeature, which render next to the tooltip rather than inside it.
 */
public final class InventoryTooltipFilter {
	private static boolean registered = false;

	private InventoryTooltipFilter() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (isOn("show_item_quality")) addItemQualityLore(stack, lines);
		});
	}

	// Real ExtraAttributes keys, confirmed via Devonian's DungeonItemStats.kt (which labels item_tier
	// "Floor" — matched verbatim) and independently cross-confirmed by SkyOcean's own
	// DungeonQualityLoreModifier.kt (same 0-50 quality scale). Only dungeon-floor drops carry these two keys
	// at all, so their mere presence is itself the "is this a quality dungeon item" check — no separate id
	// whitelist needed.
	private static void addItemQualityLore(ItemStack stack, List<Component> lines) {
		Integer quality = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getAttributeInt(stack, "baseStatBoostPercentage");
		Integer floor = com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getAttributeInt(stack, "item_tier");
		if (quality == null || floor == null) return;
		lines.add(Component.literal("§7Item Quality: §d" + quality + "/50 §7(Floor §d" + floor + "§7)"));
	}

	private static boolean isOn(String featureId) {
		Feature feature = FeatureRegistry.get(featureId);
		return feature != null && feature.isEnabled();
	}
}
