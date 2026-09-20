package com.cokelord.skyblocksimplified.dungeon;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per user request: a way to know the LOCAL player's own currently-selected dungeon class OUTSIDE of an
 * active dungeon run (needed for Auto-Kick's dupe-class check — superseded there by reading each joiner's
 * own announced class directly off the real join line instead, see AutoKickFeature's own doc comment — and
 * for the new "Highlight Parties" module, which needs to compare the LOCAL player's class against Party
 * Finder listings before ever entering a dungeon). {@link DungeonState}'s own tab-list class tracking only
 * ever runs WHILE inside a dungeon, so it can't answer this while browsing the dungeon hub/Party Finder.
 *
 * <p>Three independent capture sources, all just call {@link #set}, which only ever OVERWRITES the cached
 * value (never clears it) — so whichever source most recently saw a real class always wins, and a source
 * that currently can't see anything (e.g. the sidebar scan below while off the dungeon hub island) silently
 * falls back to whatever an earlier source already cached, per user request ("Make it fallback to the cache
 * incase users run stuff like /m6 to queue party finder and the mod cant figure out their class because of
 * that").
 * <ol>
 *   <li><b>Hub sidebar scan (primary)</b> — per user request ("theres another way to detect currently
 *   selected class which i noticed. The tablist [sidebar]... the line precisely under the 'Catacombs 38:
 *   30%' actually has a 'Healer 37: 15%' which actually matches currently selected class... make it scan
 *   every like half a second in the dungeon hub"): polls {@link
 *   com.cokelord.skyblocksimplified.hud.ScoreboardReader#readCurrentSidebarLines()} — the same real sidebar
 *   reader {@link DungeonState} already uses for floor/stat detection — every {@link #HUB_SCAN_INTERVAL_TICKS}
 *   ticks while {@link com.cokelord.skyblocksimplified.util.IslandGate#isInHubOrLobby()}, for a
 *   {@link #SIDEBAR_CLASS_PATTERN} line. Needs no GUI open at all, so this is both the most reliable AND the
 *   most frequently-updated source once implemented — "priority" in practice just falls out of how often it
 *   runs versus the other two, since {@link #set} has no separate priority concept of its own.</li>
 *   <li><b>Catacombs Gate GUI (secondary)</b> — per the user's own original exact lead ("the little nether
 *   star in the dungeons gui, which is at slot 45 in the gui with the title 'Catacombs Gate'") —
 *   independently cross-confirmed against SkyHanni's own real, shipped {@code DungeonFinderFeatures.kt}
 *   ({@code dungeonClassItemIndex = 45}). Real, screenshot-confirmed tooltip shape: name "Dungeon Classes",
 *   then "View and select a dungeon class.", then "Currently Selected: X", then "Click to view!" — the item
 *   is identified by that name/lore-anchor text (see {@link #CLASS_ITEM_NAME}/{@link
 *   #CLASS_ITEM_LORE_ANCHOR}), not by material. Still useful as a fallback for the moment right before the
 *   local player has ever been in the hub for the sidebar scan to see (e.g. joining straight into the Gate
 *   from elsewhere).</li>
 *   <li><b>Dungeon-start capture</b> — see {@link #captureFromDungeonStart}'s own doc comment; fires exactly
 *   once, right as a real dungeon run actually begins.</li>
 *   <li><b>Real tab-list scan (fallback)</b> — per user report ("When i do /m6 and go to party finder it
 *   doesnt highlight the parties it has to because it can not remember my class because there has been no
 *   nether star to detect. It also doesnt detect through the tablist as a fallback"): a Party Finder
 *   shortcut like {@code /m6} skips both the Catacombs Gate GUI and the dungeon hub sidebar entirely, so
 *   neither of the sources above ever gets a chance to see the local player's own class before Highlight
 *   Parties already needs it. Scans the real tab list ({@link
 *   com.cokelord.skyblocksimplified.hud.TabListReader}, available on any island/screen, unlike the sidebar)
 *   every {@link #HUB_SCAN_INTERVAL_TICKS} ticks for the local player's own line — see
 *   {@link #scanTabListForClass()}.</li>
 * </ol>
 */
public final class SelfClassCache {
	private SelfClassCache() {}

	private static final String GATE_SCREEN_TITLE = "Catacombs Gate";
	private static final int CLASS_SLOT = 45;
	// Real, screenshot-confirmed display name/lore-anchor line for this exact item ("Dungeon Classes" / "View
	// and select a dungeon class."). See captureFromGateScreen's own doc comment for why these replaced the
	// old Items.NETHER_STAR material gate.
	private static final String CLASS_ITEM_NAME = "Dungeon Classes";
	private static final String CLASS_ITEM_LORE_ANCHOR = "View and select a dungeon class.";
	private static final Pattern CLASS_WORD = Pattern.compile(
		"\\b(Healer|Mage|Tank|Archer|Berserk)\\b", Pattern.CASE_INSENSITIVE);
	// Real bug found (per user report — "the class detection STILL is not happening in the Catacombs Gate
	// menu"): anchored full-line match (^...$, consulted via Matcher#matches()) — the exact same class of bug
	// already found and fixed elsewhere in this codebase for other "confirmed real line, but full-anchored"
	// patterns (see DungeonState's own FLOOR_PATTERN doc comment): any trailing content the anchor doesn't
	// expect — a trailing color/reset artifact, or simply this real lore line not being the LAST thing on its
	// own line the way a hand-typed test string is — silently fails the WHOLE match instead of just not
	// capturing that part. Rewritten as a `.find()` scan for the fixed "Currently Selected:" label with the
	// class captured as the very next run of non-whitespace, with no requirement that the line end there.
	private static final Pattern CURRENTLY_SELECTED_PATTERN = Pattern.compile("Currently Selected:\\s*(\\S+)");
	// Real, confirmed Hypixel line — the exact end of the pre-run lobby countdown, already relied on
	// elsewhere in this codebase as the trigger for SplitsFeature's own very first split group. Per user
	// request ("The detection needs to proc exactly once in dungeons, and thats when the dungeon starts i.e
	// the first split starts"): this is the one real, stable, one-time signal that the player has definitely
	// locked in their final class selection for this run (mid-lobby class swaps are still possible right up
	// until this line fires) — see captureFromDungeonStart's own doc comment for why this reads
	// DungeonState's own already-tracked teammates instead of re-parsing the tab list a third way.
	private static final String DUNGEON_STARTING_LINE = "Starting in 1 second.";
	// Per user request — see this class's own doc comment, source #1. Tolerant `.find()` scan (no anchors),
	// same defensive convention as CURRENTLY_SELECTED_PATTERN above, since a real sidebar line's trailing
	// color/reset code is exactly the kind of thing an anchored match has already silently failed on
	// elsewhere in this codebase (see DungeonState's own FLOOR_PATTERN history).
	private static final Pattern SIDEBAR_CLASS_PATTERN = Pattern.compile(
		"(Healer|Mage|Tank|Archer|Berserk) \\d+: [\\d.]+%", Pattern.CASE_INSENSITIVE);
	// Per this class's own doc comment, source #4 — deliberately just a bare word-boundary class-name search
	// (no anchoring/fixed shape at all), since the real tab-list line format around it is unconfirmed outside
	// a dungeon and the whole point of this source is to be tolerant of whatever it actually looks like.
	private static final Pattern CLASS_WORD_ANY = Pattern.compile("\\b(Healer|Mage|Tank|Archer|Berserk)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	private static final int HUB_SCAN_INTERVAL_TICKS = 10; // ~500ms at a real 20 TPS, per user request.

	private static volatile DungeonClass cached = null;
	private static boolean registered = false;
	private static int hubScanTickCounter = 0;

	/** Null until a real class has actually been observed this session (no guessing/defaulting). */
	public static DungeonClass get() { return cached; }

	public static void set(DungeonClass clazz) {
		if (clazz != null && clazz != DungeonClass.EMPTY) cached = clazz;
	}

	public static void register() {
		if (registered) return;
		registered = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			// Real bug found (per user report — "the mod doesnt keep searching inside the gui and only procs
			// once, before all items have spawned"): the "is this really the Catacombs Gate screen" title
			// check used to run ONCE, right here, before the per-frame capture listener below was even
			// registered — if the screen's title happened not to already be exactly right on this single
			// check (or if this whole AFTER_INIT callback fires before whatever else this comparison depends
			// on), the per-frame listener never got registered at all and nothing here ever ran again for
			// that screen, regardless of how many frames it stayed open. Moved inside the per-frame listener
			// itself, which is unconditionally registered on every real container screen now, so a title
			// that isn't ready on the very first frame simply gets re-checked next frame instead of
			// permanently opting the whole screen-open out.
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				try {
					if (!GATE_SCREEN_TITLE.equals(containerScreen.getTitle().getString())) return;
					captureFromGateScreen(containerScreen);
				} catch (Exception e) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
						"SelfClassCache: Catacombs Gate capture failed, skipping this frame", e);
				}
			});
		});
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (DUNGEON_STARTING_LINE.equals(message.getString())) {
				try {
					captureFromDungeonStart();
				} catch (Exception e) {
					com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
						"SelfClassCache: dungeon-start capture failed", e);
				}
			}
			return true;
		});
		// Per user request — see this class's own doc comment, source #1 ("make it scan every like half a
		// second in the dungeon hub"). This class isn't a Feature (no FeatureRegistry.tickAll participation),
		// so it registers its own tick listener directly, same as other always-on infra elsewhere in this
		// codebase (SuperboomUseTracker, BlessingParticleFilter).
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++hubScanTickCounter < HUB_SCAN_INTERVAL_TICKS) return;
			hubScanTickCounter = 0;
			try {
				scanHubSidebarForClass();
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"SelfClassCache: hub sidebar scan failed", e);
			}
			try {
				scanTabListForClass();
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"SelfClassCache: tab-list scan failed", e);
			}
		});
	}

	/** See this class's own doc comment, source #4. Unlike the sidebar scan above, this isn't gated on being
	 *  in the hub at all — the real tab list is populated on any island/screen (Party Finder included), which
	 *  is exactly the gap a Party Finder shortcut like "/m6" falls into. Finds the local player's own line by
	 *  a plain substring search for their username (real display names embed rank prefixes/color codes around
	 *  it, so this doesn't require the username to be the whole line), then searches that SAME line separately
	 *  for a real class name — no fixed/anchored line shape assumed at all, since the real format here is
	 *  unconfirmed outside a dungeon. */
	private static void scanTabListForClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String selfName = mc.player.getName().getString();
		for (String line : com.cokelord.skyblocksimplified.hud.TabListReader.readLines()) {
			String stripped = COLOR_CODE.matcher(line).replaceAll("");
			if (!stripped.contains(selfName)) continue;
			var m = CLASS_WORD_ANY.matcher(stripped);
			if (!m.find()) continue;
			try {
				set(DungeonClass.valueOf(m.group(1).toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {}
			return;
		}
	}

	/** See this class's own doc comment, source #1. Real Hypixel sidebar shape per the user's own live
	 *  observation: two lines under the "Dungeons:" header, right after "Catacombs {level}: {percent}%",
	 *  sits "{Class} {level}: {percent}%" for whichever class is currently selected. Only ever OVERWRITES
	 *  {@link #cached} (via {@link #set}) — never clears it — so a tick where this island's sidebar doesn't
	 *  show that line at all (off the dungeon hub, or per the user's own example, warped straight into Party
	 *  Finder via a command like "/m6") simply leaves whatever was cached from a previous real observation in
	 *  place instead of going blank. */
	private static void scanHubSidebarForClass() {
		if (!com.cokelord.skyblocksimplified.util.IslandGate.isInHubOrLobby()) return;
		for (String line : com.cokelord.skyblocksimplified.hud.ScoreboardReader.readCurrentSidebarLines()) {
			String stripped = COLOR_CODE.matcher(line).replaceAll("");
			var m = SIDEBAR_CLASS_PATTERN.matcher(stripped);
			if (!m.find()) continue;
			try {
				set(DungeonClass.valueOf(m.group(1).toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {}
			return;
		}
	}

	/** Real, one-time-per-run capture at the exact moment the pre-run lobby countdown ends (see
	 *  DUNGEON_STARTING_LINE's own doc comment for why this specific moment). Reads {@link
	 *  DungeonState#getTeammates()} — the same already-confirmed-working self-class lookup
	 *  {@code LeapCounterFeature#selfClass()}/{@code DungeonNotificationsFeature#classOf()} already use
	 *  elsewhere in this codebase — rather than re-implementing a third independent tab-list regex parse. By
	 *  this exact moment the tab list has been populated for the whole lobby countdown, so the local player's
	 *  own class is expected to already be tracked there. */
	private static void captureFromDungeonStart() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) {
				set(teammate.clazz);
				return;
			}
		}
	}

	// Real bug found (per user screenshot of the real tooltip — "Dungeon Classes / View and select a dungeon
	// class. / Currently Selected: Healer / Click to view!"): this used to require the slot's real item be
	// Items.NETHER_STAR before ever reading its lore — that material check was never actually confirmed
	// against a real screenshot, only inferred early on from a loose "the little nether star" description,
	// and silently blocked every single capture attempt regardless of how correct the lore pattern itself
	// was (explaining why last round's regex fix alone still didn't work). Replaced with a check against the
	// item's own real, now-confirmed display name/lore anchor line instead of its material.
	private static void captureFromGateScreen(AbstractContainerScreen<?> screen) {
		var menu = screen.getMenu();
		if (menu.slots.size() <= CLASS_SLOT) return;
		ItemStack stack = menu.getSlot(CLASS_SLOT).getItem();
		if (stack.isEmpty()) return;

		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return;
		java.util.List<Component> lines = lore.lines();
		Component nameCheck = stack.get(DataComponents.CUSTOM_NAME);
		boolean nameMatches = nameCheck != null && CLASS_ITEM_NAME.equals(nameCheck.getString());
		boolean loreAnchorMatches = lines.stream().anyMatch(line -> line.getString().contains(CLASS_ITEM_LORE_ANCHOR));
		if (!nameMatches && !loreAnchorMatches) return;
		// Real bug found (per user report — "Highlight Parties doesn't work, which means the detecting for
		// the class is broken... the class also displays on a lore line. The line goes 'Currently Selected:
		// Healer'"): this used to only ever check ONE hardcoded lore line index (2, zero-based) for this exact
		// pattern — a real, live-confirmed line/format, but pinning it to a specific index was never actually
		// confirmed, only assumed from SkyHanni's own source. Scanning every real lore line for the pattern
		// instead removes that fragile index assumption entirely while still matching the exact same
		// confirmed line text/format the user themselves quoted.
		for (Component line : lines) {
			var m = CURRENTLY_SELECTED_PATTERN.matcher(line.getString());
			if (m.find() && tryMatch(m.group(1))) return;
		}

		Component nameComponent = stack.get(DataComponents.CUSTOM_NAME);
		if (nameComponent != null && tryMatch(nameComponent.getString())) return;
		for (Component line : lines) {
			if (tryMatch(line.getString())) return;
		}
	}

	private static boolean tryMatch(String text) {
		var m = CLASS_WORD.matcher(text);
		if (!m.find()) return false;
		try {
			set(DungeonClass.valueOf(m.group(1).toUpperCase(Locale.ROOT)));
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
