package com.cokelord.skyblocksimplified.api;

import java.util.Locale;

/**
 * A typed Skyblock item internal-name (Hypixel's own item "id", e.g. "ENCHANTED_CARROT") with
 * convenience price/repo lookups baked in — mirrors NEU/SkyHanni's NeuInternalName so every future
 * feature that needs an item's display name, NPC sell price, or bazaar price (shopping lists, profit
 * trackers, price checks) shares one small type instead of passing raw strings and re-deriving lookups.
 */
public final class NeuInternalName {
	private final String id;

	private NeuInternalName(String id) {
		this.id = id;
	}

	public static NeuInternalName of(String id) {
		return new NeuInternalName(id.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}

	public String asString() {
		return id;
	}

	public SkyblockItemRepo.ItemInfo getItemInfo() {
		return SkyblockItemRepo.getItem(id);
	}

	public String getDisplayName() {
		SkyblockItemRepo.ItemInfo info = getItemInfo();
		return info != null ? info.name() : id;
	}

	public BazaarApi.BazaarPrice getBazaarPrice() {
		return BazaarApi.getPrice(id);
	}

	public Double getNpcSellPrice() {
		SkyblockItemRepo.ItemInfo info = getItemInfo();
		return info != null ? info.npcSellPrice() : null;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof NeuInternalName other && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return id;
	}
}
