package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.item.RomanNumeralUtil;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Always-on shared "which floor/mode is the party currently queued for" tracker — extracted out of
 * {@link com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature} (its original, and so far only,
 * consumer) per user request that {@link com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature}
 * also needs the same real signal. Auto-Kick's own capture used to be gated behind {@code
 * instance.isEnabled()} — fine while it was the only consumer, but that would have silently starved Party
 * Finder Stats of this data any time the user had Auto-Kick itself turned off, an invisible coupling between
 * two otherwise-independent features. Registered unconditionally at client init instead (same "always-on
 * infra" convention as {@link com.cokelord.skyblocksimplified.party.PartyApi}, {@link
 * com.cokelord.skyblocksimplified.util.TpsMonitor}, etc.), so every consumer gets the same answer regardless
 * of which features happen to be toggled on.
 *
 * <p>Two real capture sources — see each capture method's own doc comment. Session-only state, never
 * persisted (this is live queue state, not a setting); -1/{@code null} means "not known yet this session."
 */
public final class DungeonQueueDetector {
	private DungeonQueueDetector() {}

	// Real "Group Builder" GUI slot layout (0-indexed) confirmed via a real, live, actively-maintained
	// dungeon mod's own working AutoKick feature (Devonian's AutoKick.kt, onBuildingParty) — slot 11 holds
	// the dungeon-type icon ("Currently Selected: The Catacombs" / "...Master Mode The Catacombs"), slot 12
	// holds the floor icon ("Currently Selected: Floor VII").
	private static final int SLOT_FLOOR_TYPE = 11;
	private static final int SLOT_FLOOR_NUMBER = 12;
	private static final Pattern FLOOR_TYPE_PATTERN = Pattern.compile("^Currently Selected: (Master Mode )?The Catacombs$");
	private static final Pattern FLOOR_NUMBER_PATTERN = Pattern.compile("^Currently Selected: Floor ([IVXLCDM]+)$");

	// Real second capture source, confirmed against Devonian's own real AutoKick.kt, which reads the floor
	// from TWO different real screens, not just Group Builder: the real "Party Finder" listing screen itself
	// (what you see reviewing your own posted party), whose slot 53 holds a player-head item with its own
	// "Dungeon: ..."/"Floor: Floor N" lore lines. Group Builder is only open for as long as it takes to set
	// the floor once; Party Finder's own listing is naturally reopened far more often (checking on your
	// posted party), so this gives a second, more reliably-available source instead of depending entirely on
	// the player having had Group Builder open at some point this session.
	private static final int SLOT_PARTY_FINDER_LISTING = 53;
	private static final Pattern PARTY_FINDER_FLOOR_TYPE_PATTERN = Pattern.compile("^Dungeon: (Master Mode )?The Catacombs$");
	private static final Pattern PARTY_FINDER_FLOOR_PATTERN = Pattern.compile("^Floor: Floor ([IVXLCDM]+)$");

	private static boolean selectedMasterMode = false;
	private static int selectedFloor = -1;
	private static boolean groupBuilderCaptured = false;
	private static boolean partyFinderListingCaptured = false;
	private static boolean registered = false;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			String title = containerScreen.getTitle().getString();
			if ("Group Builder".equals(title)) {
				groupBuilderCaptured = false;
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
					if (groupBuilderCaptured) return;
					try {
						captureGroupBuilder(containerScreen);
					} catch (Exception e) {
						SkyblockSimplified.LOGGER.error("DungeonQueueDetector Group Builder capture failed, skipping this frame", e);
					}
				});
			} else if ("Party Finder".equals(title)) {
				partyFinderListingCaptured = false;
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
					if (partyFinderListingCaptured) return;
					try {
						capturePartyFinderListing(containerScreen);
					} catch (Exception e) {
						SkyblockSimplified.LOGGER.error("DungeonQueueDetector Party Finder listing capture failed, skipping this frame", e);
					}
				});
			}
		});
	}

	/** Real self-healing capture — see class doc comment. Stops re-checking the instant BOTH slots have
	 *  matched at least once; a slot that never matches (e.g. the menu is a different real Hypixel screen
	 *  than expected) just leaves the last-known floor/mode in place rather than resetting to unknown. */
	private static void captureGroupBuilder(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= SLOT_FLOOR_NUMBER) return;
		boolean sawType = false, sawFloor = false;

		var typeLore = menu.getSlot(SLOT_FLOOR_TYPE).getItem().get(net.minecraft.core.component.DataComponents.LORE);
		if (typeLore != null) {
			for (Component line : typeLore.lines()) {
				Matcher m = FLOOR_TYPE_PATTERN.matcher(line.getString());
				if (m.matches()) {
					selectedMasterMode = m.group(1) != null;
					sawType = true;
					break;
				}
			}
		}

		var floorLore = menu.getSlot(SLOT_FLOOR_NUMBER).getItem().get(net.minecraft.core.component.DataComponents.LORE);
		if (floorLore != null) {
			for (Component line : floorLore.lines()) {
				Matcher m = FLOOR_NUMBER_PATTERN.matcher(line.getString());
				if (m.matches()) {
					int floor = RomanNumeralUtil.parseLevel(m.group(1));
					if (floor > 0) {
						selectedFloor = floor;
						sawFloor = true;
					}
					break;
				}
			}
		}

		if (sawType && sawFloor) groupBuilderCaptured = true;
	}

	/** Second real capture source — see SLOT_PARTY_FINDER_LISTING's own doc comment. Reads both lore lines
	 *  off the one real head item at slot 53 in a single pass, same self-healing "stop once both are seen"
	 *  contract as captureGroupBuilder. */
	private static void capturePartyFinderListing(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= SLOT_PARTY_FINDER_LISTING) return;
		ItemStack stack = menu.getSlot(SLOT_PARTY_FINDER_LISTING).getItem();
		if (!stack.is(Items.PLAYER_HEAD)) return;
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null) return;

		boolean sawType = false, sawFloor = false;
		for (Component line : lore.lines()) {
			Matcher typeMatch = PARTY_FINDER_FLOOR_TYPE_PATTERN.matcher(line.getString());
			if (typeMatch.matches()) {
				selectedMasterMode = typeMatch.group(1) != null;
				sawType = true;
				continue;
			}
			Matcher floorMatch = PARTY_FINDER_FLOOR_PATTERN.matcher(line.getString());
			if (floorMatch.matches()) {
				int floor = RomanNumeralUtil.parseLevel(floorMatch.group(1));
				if (floor > 0) {
					selectedFloor = floor;
					sawFloor = true;
				}
			}
		}

		if (sawType && sawFloor) partyFinderListingCaptured = true;
	}

	/** The currently-queued floor number (1-7), or null if never successfully captured this session. */
	public static Integer getQueuedFloor() {
		return selectedFloor > 0 ? selectedFloor : null;
	}

	/** Only meaningful when {@link #getQueuedFloor()} is non-null. */
	public static boolean isQueuedMasterMode() {
		return selectedMasterMode;
	}
}
