package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.NeuInternalName;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.farming.CropBreakTracker;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Estimated coins/hour for whichever crop is currently being farmed. Originally measured via a 10-second
 * inventory snapshot of the harvested item's stack count — but Garden's default auto-collection sends
 * every harvested crop straight to its Sack, never touching the player's actual inventory at all, so that
 * count could never increase and the display could never update during real gameplay (it only ever worked
 * in the position-editor preview, which shows a hardcoded placeholder instead of real data). Rebuilt to use
 * CropBreakTracker's own rolling breaks-per-second rate instead, which is driven purely by the block-break
 * event itself and has no dependency on where the harvested item ends up.
 */
public class MoneyPerHourFeature extends TabWidgetOverlayFeature {
	public enum PriceSource { BAZAAR_INSTANT_BUY, BAZAAR_INSTANT_SELL, NPC_SELL }

	private static final long IDLE_HIDE_MILLIS = 60_000;

	// Melon's crop-break display name ("Melon", the block) differs from what actually lands in the
	// inventory/sack (Melon Slice items) — every other tracked crop's block/item name already matches.
	private static final Map<String, String> CROP_TO_ITEM_NAME = Map.of("Melon", "Melon Slice");

	private PriceSource priceSource = PriceSource.BAZAAR_INSTANT_SELL;

	private String display = null;

	public MoneyPerHourFeature() {
		super("farming_money_per_hour", "Money/Hour Display", FeatureCategory.FARMING, "Farming", 0.01f, 0.5f);
		CropBreakTracker.start();
	}

	public PriceSource getPriceSource() {
		return priceSource;
	}

	public void setPriceSource(PriceSource priceSource) {
		this.priceSource = priceSource;
	}

	public void cyclePriceSource() {
		PriceSource[] values = PriceSource.values();
		priceSource = values[(priceSource.ordinal() + 1) % values.length];
	}

	public static String displayName(PriceSource source) {
		return switch (source) {
			case BAZAAR_INSTANT_BUY -> "Instant buy";
			case BAZAAR_INSTANT_SELL -> "Instant Sell";
			case NPC_SELL -> "NPC Sell";
		};
	}

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.player == null) {
			display = null;
			return;
		}
		if (CropBreakTracker.millisSinceLastBreak() > IDLE_HIDE_MILLIS) {
			display = null;
			return;
		}

		String crop = CropBreakTracker.currentCrop();
		if (crop == null) {
			display = null;
			return;
		}
		String itemName = CROP_TO_ITEM_NAME.getOrDefault(crop, crop);
		Double unitPrice = priceFor(itemName);
		if (unitPrice == null) {
			display = null;
			return;
		}

		double breaksPerSecond = CropBreakTracker.breaksPerSecond(crop);
		double perHour = breaksPerSecond * 3600.0 * unitPrice;
		display = "§6" + crop + "§7: §e" + String.format(Locale.ROOT, "%,.0f", perHour) + "§7/hr";
	}

	private Double priceFor(String itemName) {
		String id = SkyblockItemRepo.findIdByName(itemName);
		if (id == null) return null;
		NeuInternalName internalName = NeuInternalName.of(id);
		return switch (priceSource) {
			case BAZAAR_INSTANT_BUY -> {
				BazaarApi.BazaarPrice price = internalName.getBazaarPrice();
				yield price != null ? price.buyPrice() : internalName.getNpcSellPrice();
			}
			case BAZAAR_INSTANT_SELL -> {
				BazaarApi.BazaarPrice price = internalName.getBazaarPrice();
				yield price != null ? price.sellPrice() : internalName.getNpcSellPrice();
			}
			case NPC_SELL -> internalName.getNpcSellPrice();
		};
	}

	@Override
	protected List<String> currentLines() {
		return display == null ? List.of() : List.of(display);
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): this used to return a bare JsonPrimitive, discarding the position/scale JsonObject the
	// TabWidgetOverlayFeature base class now persists (see that class's own doc comment) — merged in as a
	// "priceSource" key on the same object instead.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("priceSource", priceSource.name());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!(data instanceof JsonObject obj) || !obj.has("priceSource")) return;
		try {
			priceSource = PriceSource.valueOf(obj.get("priceSource").getAsString());
		} catch (IllegalArgumentException ignored) {}
	}

	@Override
	public String getDescription() {
		return "Estimates your coins-per-hour for whichever crop you're currently farming.";
	}
}
