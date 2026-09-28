package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.api.HypixelElectionApi;
import com.cokelord.skyblocksimplified.util.IslandGate;
import com.cokelord.skyblocksimplified.util.SkyblockNbtUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/** Shared, cheap Diana context checks (hub, spade, event active). Inventory scans are cached per second. */
public final class DianaState {
	// Real Skyblock ids of the three Griffin spades (same list SBO's Helper.hasSpade checks).
	private static final Set<String> SPADE_IDS = Set.of("ANCESTRAL_SPADE", "ARCHAIC_SPADE", "DEIFIC_SPADE");

	private static long spadeCheckedAt = 0;
	private static boolean spadeInInventory = false;
	private static long lastHeldSpadeAt = 0;

	private DianaState() {}

	public static boolean inHub() {
		return IslandGate.isInHub();
	}

	public static boolean isSpade(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		String id = SkyblockNbtUtils.getItemId(stack);
		if (id != null && SPADE_IDS.contains(id)) return true;
		return stack.getHoverName().getString().contains("Spade");
	}

	public static boolean holdingSpade() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		boolean held = isSpade(mc.player.getMainHandItem());
		if (held) lastHeldSpadeAt = System.currentTimeMillis();
		return held;
	}

	/** Held a spade within the last {@code ms} milliseconds (SBO's InventoryUtils.isItemHeld window). */
	public static boolean heldSpadeWithin(long ms) {
		return holdingSpade() || System.currentTimeMillis() - lastHeldSpadeAt <= ms;
	}

	/** Spade anywhere in the inventory (rechecked at most once per second). */
	public static boolean hasSpade() {
		long now = System.currentTimeMillis();
		if (now - spadeCheckedAt < 1000) return spadeInInventory;
		spadeCheckedAt = now;
		Minecraft mc = Minecraft.getInstance();
		spadeInInventory = false;
		if (mc.player == null) return false;
		for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
			if (isSpade(stack)) { spadeInInventory = true; break; }
		}
		return spadeInInventory;
	}

	/** Diana's Mythological Ritual is active (as mayor or minister). Unknown (API not loaded yet) counts as
	 *  active only while a spade is carried, so nothing hides itself before the first API response. */
	public static boolean eventActive() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		if (mayor == null) return hasSpade();
		return mayor.isDianaEventActive();
	}

	/** Diana waypoints/guessing context: in the Hub with a spade in the inventory. */
	public static boolean dianaContext() {
		return inHub() && hasSpade();
	}
}
