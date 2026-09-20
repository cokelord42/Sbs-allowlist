package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.SelfClassCache;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Pattern;

/**
 * Per user request: a module for the real Party Finder BROWSING screen (the listing of other players'
 * posted parties, title "Party Finder" — a different screen than reviewing your own posted listing) that
 * highlights listings the local player can actually join AND that still need their own class.
 *
 * <p>Real, user-confirmed layout: party listings only ever occupy slots 10-16, 19-25, and 28-34 (three
 * 7-wide rows in the 9-wide chest GUI) — checked directly by index instead of a guessed item-name pattern.
 * Each listing's lore lists one real line per current member in the form {@code "{username}: {Class}
 * ({level})"}. Per user request ("It needs to detect literally any lore line that contains the class the
 * user is playing" — the previous strict {@code "name: Class (level)"} regex wasn't matching for real,
 * unconfirmed reasons): loosened to a plain word-boundary search for the local player's own class name
 * anywhere in each lore line, with one confirmed exception — a line starting with "Note:" is the party
 * leader's own free-text note (e.g. "Note: LF Tank") and is skipped, since it would otherwise false-positive
 * match a class that isn't actually a real party member.
 *
 * <p>Per user's own real-mechanic correction: Hypixel doesn't show a separately-parseable "your level is too
 * low" line — instead, a listing's own last lore line is either "Requires Catacombs N!" (can't join) or
 * "Click to join!" (the local player's class/Catacombs level already qualify) depending on whether the
 * viewer personally meets its requirements. "Click to join!" being present is therefore the real, confirmed
 * signal for "the local player CAN join this party" — combined with "this party doesn't already have my
 * class", that's the exact highlight condition the user asked for.
 */
public class HighlightPartiesFeature extends Feature {
	private static final String LISTING_SCREEN_TITLE = "Party Finder";
	// Real, user-confirmed slot layout (three 7-wide rows inside a 9-wide chest GUI) — replaces the earlier
	// guessed ".*'s Party" item-name pattern as the way this identifies a real listing slot.
	private static final int[] PARTY_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final String CLICK_TO_JOIN_LINE = "Click to join!";

	private int highlightColor = 0xFF55FF55;

	public HighlightPartiesFeature() {
		super("highlight_parties", "Highlight Parties", FeatureCategory.COMBAT, false);
		PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !LISTING_SCREEN_TITLE.equals(screen.getTitle().getString())) return;
			renderHighlights(graphics, screen);
		});
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	private void renderHighlights(net.minecraft.client.gui.GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		DungeonClass selfClass = SelfClassCache.get();
		// Real bug found (per user report — "the party highlight shouldn't highlight anything if the mod
		// doesn't have your class, since then it just highlights everything"): bailing out entirely while the
		// local player's own class isn't known yet, rather than letting either half of the condition below
		// silently degrade into "highlight everything."
		if (selfClass == null) return;
		// Word-boundary, case-insensitive search for the local player's own class name — real Hypixel class
		// names (Healer/Mage/Tank/Archer/Berserk) match DungeonClass's own enum constant names case-
		// insensitively, so this needs no separate name table.
		Pattern classWord = Pattern.compile("\\b" + selfClass.name() + "\\b", Pattern.CASE_INSENSITIVE);

		var allSlots = screen.getMenu().slots;
		for (int slotId : PARTY_SLOTS) {
			if (slotId >= allSlots.size()) continue;
			Slot slot = allSlots.get(slotId);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) continue;

			boolean containsOwnClass = false;
			boolean canJoin = false;
			for (Component line : lore.lines()) {
				String text = line.getString();
				// Per user request ("ONE BIG EXCEPTION: The lore line cannot start with the word 'Note:'
				// otherwise if people put the note as LF Tank then it would hide that highlight since it
				// falsely detected that there was a tank"): a party leader's own free-text note isn't a real
				// member listing.
				if (text.strip().startsWith("Note:")) continue;
				if (classWord.matcher(text).find()) containsOwnClass = true;
				// Per user's own real-mechanic correction: Hypixel swaps this listing's own last lore line
				// from "Requires Catacombs N!" to "Click to join!" once the viewer's own class/Catacombs level
				// already qualify — the real, confirmed "can I join this" signal, not a separately-parsed
				// requirement number.
				if (CLICK_TO_JOIN_LINE.equals(text.strip())) canJoin = true;
			}

			if (!containsOwnClass && canJoin) {
				int alpha = Math.round(0.30f * 255);
				int fillColor = (alpha << 24) | (highlightColor & 0xFFFFFF);
				RenderUtil.fillRounded(graphics, slot.x, slot.y, slot.x + 16, slot.y + 16, 0, fillColor);
			}
		}
	}

	public int getHighlightColor() { return highlightColor; }
	public void setHighlightColor(int value) { highlightColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("highlightColor", highlightColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("highlightColor")) highlightColor = obj.get("highlightColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Highlights Party Finder listings you can actually join (\"Click to join!\") that still need your class.";
	}
}
