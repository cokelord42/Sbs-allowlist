package com.cokelord.skyblocksimplified.foraging;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads perk state (locked / enabled / disabled, current level) out of the Heart of the Forest GUI's
 * item lore — ported from SkyHanni's HotfData.kt/HotxHandler, keeping its confirmed lore regexes and
 * per-perk max levels (used to know when a perk is already maxed, to match SkyHanni's "hide the level
 * once maxed" behavior). Shared by the highlight and level-stack-size features so both read the exact
 * same detection instead of drifting.
 */
public final class HotfPerkApi {
	public static final String INVENTORY_TITLE = "Heart of the Forest";

	public enum PerkState { LOCKED, ENABLED, DISABLED }

	// Colorless — matched against the lore line with color codes stripped.
	private static final Pattern ENABLED = Pattern.compile("ENABLED|SELECTED");
	private static final Pattern NOT_UNLOCKED = Pattern.compile("Requires.*|.*Forest!|Click to unlock!");
	// Keeps its own §. handling — matched against the raw (colored) lore line.
	private static final Pattern LEVEL = Pattern.compile("(?:§.)*Level (?<level>\\d+).*");

	private static final Map<String, Integer> PERK_MAX_LEVELS = new LinkedHashMap<>();

	static {
		PERK_MAX_LEVELS.put("Sweep", 50);
		PERK_MAX_LEVELS.put("Foraging Fortune", 50);
		PERK_MAX_LEVELS.put("Strength Boost", 50);
		PERK_MAX_LEVELS.put("Damage Boost", 2);
		PERK_MAX_LEVELS.put("Speed Boost", 50);
		PERK_MAX_LEVELS.put("Axe Toss", 2);
		PERK_MAX_LEVELS.put("Luck of the Forest", 40);
		PERK_MAX_LEVELS.put("Daily Wishes", 100);
		PERK_MAX_LEVELS.put("250 Gifts", 40);
		PERK_MAX_LEVELS.put("Lottery", 2);
		PERK_MAX_LEVELS.put("Foraging Madness", 2);
		PERK_MAX_LEVELS.put("Deep Waters", 50);
		PERK_MAX_LEVELS.put("Efficient Forager", 100);
		PERK_MAX_LEVELS.put("Collector", 50);
		PERK_MAX_LEVELS.put("Early Bird", 2);
		PERK_MAX_LEVELS.put("Precision Cutting", 2);
		PERK_MAX_LEVELS.put("Monster Hunter", 2);
		PERK_MAX_LEVELS.put("Tree Whisperer", 2);
		PERK_MAX_LEVELS.put("Homing Axe", 2);
		PERK_MAX_LEVELS.put("Forest Strength", 50);
		PERK_MAX_LEVELS.put("Hunter's Luck", 50);
		PERK_MAX_LEVELS.put("Galatea's Might", 50);
		PERK_MAX_LEVELS.put("Essence Fortune", 50);
		PERK_MAX_LEVELS.put("Forest Speed", 50);
		PERK_MAX_LEVELS.put("Maniac Slicer", 2);
		PERK_MAX_LEVELS.put("Half Empty", 25);
		PERK_MAX_LEVELS.put("Ricochet", 10);
		PERK_MAX_LEVELS.put("Half Full", 25);
		PERK_MAX_LEVELS.put("Center of the Forest", 5);
	}

	private HotfPerkApi() {}

	public static Integer maxLevelForName(String plainName) {
		return PERK_MAX_LEVELS.get(plainName);
	}

	/** Null means "not a perk item at all" (glass pane filler, border, nav button, etc.) — the caller
	 *  should skip highlighting those entirely rather than falling back to a status color. Previously this
	 *  defaulted every unrecognized item straight to DISABLED, which is why the highlight lit up every
	 *  slot in the GUI: any filler item with no matching lore line fell through to that same default. A
	 *  slot only counts as a real (unlocked-but-inactive) perk if it actually has a level readout line —
	 *  filler items have no lore at all, so they never set isPerkItem and correctly fall through to null. */
	public static PerkState determineState(ItemStack stack) {
		boolean isPerkItem = false;
		for (String line : loreLines(stack)) {
			String stripped = line.replaceAll("§.", "");
			if (NOT_UNLOCKED.matcher(stripped).matches()) return PerkState.LOCKED;
			if (ENABLED.matcher(stripped).matches()) return PerkState.ENABLED;
			if (LEVEL.matcher(line).matches()) isPerkItem = true;
		}
		return isPerkItem ? PerkState.DISABLED : null;
	}

	/** Null if no lore line matches the confirmed level-readout pattern. */
	public static Integer currentLevel(ItemStack stack) {
		for (String line : loreLines(stack)) {
			Matcher matcher = LEVEL.matcher(line);
			if (matcher.matches()) {
				try {
					return Integer.parseInt(matcher.group("level"));
				} catch (NumberFormatException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private static Iterable<String> loreLines(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return java.util.List.of();
		java.util.List<String> lines = new java.util.ArrayList<>();
		for (Component line : lore.lines()) lines.add(line.getString());
		return lines;
	}
}
