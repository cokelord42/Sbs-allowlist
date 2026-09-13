package com.cokelord.skyblocksimplified.api;

/**
 * Shared "roughly how much is this item worth" price resolution — split out of the now-deleted
 * EstimatedItemValueFeature (per explicit user request — "the estimated item value is really hard for a
 * fully accurate one, and i want to move on, so for now just delete the module entirely") so the other
 * features that reused its priceOf (Chest Rolling's own real-drop ranking) keep working.
 */
public final class ItemPriceUtil {
	private ItemPriceUtil() {}

	/** Median BIN auction price, else Bazaar sell price, else NPC sell price — null if unpriceable through
	 *  any of those.
	 *
	 *  <p>Real bug found (per user report — "The instance chest profit doesnt seem to be detecting auction
	 *  house prices, theres a wither helmet and it doesnt count it in the profit. The hierarchy of prices
	 *  should go like this: Auction, bazaar, npc sell. If it cant find it on auction then it should default
	 *  to bazaar, and if it cant find on bazaar then it should default to npc sell"): this used to check
	 *  Bazaar first, then NPC, then Auction only as a last resort — for most real dungeon/Kuudra gear
	 *  rewards (helmets, weapons, etc.) that never trade on the Bazaar and have no NPC sell price, Auction
	 *  WAS already the only source that could ever price them, so the order itself wasn't strictly why a
	 *  priceable item like a Wither Helmet could still show as uncounted — but it does mean any item that
	 *  genuinely has BOTH a Bazaar/NPC price and a (possibly more accurate, more current) Auction price
	 *  always used the wrong one. Reordered to match the user's explicit priority exactly: Auction first,
	 *  falling back to Bazaar, then NPC sell. */
	public static Double priceOf(NeuInternalName name) {
		AuctionApi.AuctionPrice auction = AuctionApi.getPrice(name.asString());
		if (auction != null) return auction.medianBinPrice();
		BazaarApi.BazaarPrice bazaar = name.getBazaarPrice();
		if (bazaar != null) return bazaar.sellPrice();
		return name.getNpcSellPrice();
	}
}
