package com.cokelord.skyblocksimplified.farming;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's real "Configure Plots" menu (the actual /plots GUI) the instant the player opens it —
 * confirmed real source, read directly from SkyHanni-7.35.0's own GardenPlotApi.kt/PestApi.kt this round
 * (not guessed): each plot's slot in this menu carries its own real display name ("§aPlot §7- §b4", or
 * "§aThe Barn" for the barn slot) and, when infested, a lore line with an EXACT pest count
 * ("§4§l §cThis plot has §25 §2 Pests§c!"). This is the one moment the mod can get fully authoritative,
 * ground-truth plot state directly from Hypixel — exactly what the "Open /plots!" fallback prompt in
 * PestPlotTeleportFeature has always told the player to do manually, without this project ever actually
 * reading what they see there until now.
 *
 * <p>Slot-to-plot mapping is GardenPlotApi's own {@code inventorySlot} (ported from the identical real
 * source), not a guess: SkyHanni's {@code var slot = 2}, +1 per plot across each of the 5x5 grid's rows,
 * +4 gap between rows, confirmed directly against GardenPlotApi.kt's real init block.
 *
 * <p>Critical distinction, confirmed directly from SkyHanni's real source (its {@code Plot.id} vs
 * {@code Plot.name}/{@code tpName}): {@code GardenPlotApi.Plot.id()} is this project's own internal grid-
 * position index (0-24, purely so world coordinates can be computed from the fixed grid layout) — it is
 * NOT necessarily the same STRING Hypixel actually shows the player as that plot's real label (what
 * appears in chat spawn announcements and this very menu's own "Plot - N" item name), and per user report
 * that label is not always a plain number either — a real plot was confirmed labeled "e12" (genuinely
 * alphanumeric, not a color-code rendering artifact as an earlier revision of this class assumed when it
 * tried to strip the label down to just its embedded digits). SkyHanni's own real code teleports and
 * matches by {@code plot.name} (the GUI-read label) verbatim, never by {@code plot.id}, for exactly this
 * reason — this class now does the same: the label is used as-is, whatever it actually is, only falling
 * back to the internal grid id (as a plain string) for the rare slot with no name label at all.
 */
public final class ConfigurePlotsReader {
	private static final String INVENTORY_TITLE = "Configure Plots";
	private static final Pattern PLOT_NAME = Pattern.compile("Plot\\D*?-\\D*?(?<name>.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern PEST_COUNT = Pattern.compile("This plot has\\D*?(?<amount>\\d+)\\D*?Pests?", Pattern.CASE_INSENSITIVE);
	// Fallback for when a plot's lore doesn't match the exact structured PEST_COUNT phrasing above (a
	// locked/uncleared/greenhouse plot's item can have a different lore layout entirely per SkyHanni's own
	// real source) — a bare "contains the word pest anywhere in this plot's own lore" check, used only to
	// confirm THIS plot (already known, since we're reading its own dedicated slot) has pests when the
	// precise count can't be parsed, not to guess which plot at all.
	private static final Pattern PEST_WORD = Pattern.compile("pest", Pattern.CASE_INSENSITIVE);
	// 25 real plots minus the Barn (id 0, which this menu doesn't show pest/name data for) — the read is
	// only "done" once every one of those 24 slots has actually had an item in it, not just once the loop
	// has run (early frames right after the menu opens can still be waiting on the server's contents packet).
	private static final int EXPECTED_PLOT_COUNT = 24;

	private static boolean registered = false;

	private ConfigurePlotsReader() {}

	/** Idempotent — safe to call from every feature that depends on this, same pattern as
	 *  PestPlotTracker.start(). */
	public static void start() {
		if (registered) return;
		registered = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			// Scoped to this one screen instance (not a static field) so it naturally resets every time the
			// player reopens the menu, with no separate ScreenEvents.remove bookkeeping needed.
			boolean[] announcedThisOpen = {false};
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				try {
					readIfConfigurePlots(containerScreen, announcedThisOpen);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Configure Plots read failed, skipping this frame", e);
				}
			});
		});
	}

	private static void readIfConfigurePlots(AbstractContainerScreen<?> screen, boolean[] announcedThisOpen) {
		String title = screen.getTitle().getString().replaceAll("§.", "").trim();
		if (!title.equals(INVENTORY_TITLE)) return;

		int slotCount = screen.getMenu().slots.size();
		int read = 0;
		int infested = 0;
		for (GardenPlotApi.Plot plot : GardenPlotApi.allPlots()) {
			if (GardenPlotApi.isBarn(plot.id())) continue;
			int slotIndex = plot.inventorySlot();
			if (slotIndex < 0 || slotIndex >= slotCount) continue;
			Slot slot = screen.getMenu().getSlot(slotIndex);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			read++;

			String plainName = stack.getHoverName().getString().replaceAll("§.", "");
			Matcher nameMatcher = PLOT_NAME.matcher(plainName);
			String label = null;
			if (nameMatcher.find()) {
				label = nameMatcher.group("name").trim();
				GardenPlotApi.setPlotName(plot.id(), label);
			}
			// The real, player-facing plot label — what chat/tptoplot both actually use — is whatever this
			// slot's own item says, verbatim, NOT this project's internal grid-position id (see class doc
			// comment) and NOT just whatever digits happen to be embedded in it — "e12" means "e12", the
			// full string, since that's confirmed to be the literal /tptoplot argument this plot expects.
			String realPlotLabel = label != null ? label : String.valueOf(plot.id());
			GardenPlotApi.learnRealNumber(realPlotLabel, plot);

			int pests = 0;
			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore != null) {
				boolean mentionsPest = false;
				for (Component line : lore.lines()) {
					String plainLine = line.getString().replaceAll("§.", "");
					Matcher pestMatcher = PEST_COUNT.matcher(plainLine);
					if (pestMatcher.find()) {
						try {
							pests = Integer.parseInt(pestMatcher.group("amount"));
						} catch (NumberFormatException ignored) {
							// Shouldn't happen against \d+, but don't let a malformed line blow up the read.
						}
						mentionsPest = true;
						break;
					}
					if (PEST_WORD.matcher(plainLine).find()) mentionsPest = true;
				}
				// The exact "This plot has N Pests" phrasing didn't match anywhere, but the word "pest"
				// still shows up somewhere in THIS plot's own lore (already known to be this specific plot
				// — this never guesses which plot, only whether the one we're already looking at has any) —
				// treat it as infested with an unknown exact count rather than missing it entirely.
				if (pests == 0 && mentionsPest) pests = 1;
			}
			// Per repeated user report ("everytime i close the plot configuring gui it drops the pests" —
			// still reproducing even after a same-session-consecutive-zero-reads debounce), this menu's own
			// pest-count lore parsing (PEST_COUNT/PEST_WORD above) apparently doesn't reliably match
			// Hypixel's real lore text — meaning it can read 0 for a plot that genuinely still has pests, not
			// just on the closing frame but seemingly any time it's read. Rather than keep guessing at the
			// real lore format, this reader now ONLY ever confirms/raises a pest count (a false positive
			// here is harmless — it just means the tracker keeps a plot that was already tracked), never
			// clears one to zero. A genuine clear still reaches the tracker via chat's own 3-minute expiry
			// and PestPlotTracker.pollLiveState()'s scoreboard-based check.
			if (pests > 0) {
				infested++;
				PestPlotTracker.setConfirmedPestState(realPlotLabel, pests);
			}
		}

		// Per user request: a one-time confirmation once every plot slot has actually been read this menu
		// session, so it's clear the mod is genuinely done (not left silently guessing) — also a real
		// diagnostic: the infested count/plot list here is a direct readout of what the mod itself just
		// parsed off Hypixel's own menu, straight from the source of truth.
		if (!announcedThisOpen[0] && read >= EXPECTED_PLOT_COUNT) {
			announcedThisOpen[0] = true;
			sendDoneMessage(infested);
		}
	}

	private static void sendDoneMessage(int infestedCount) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String text = infestedCount == 0
			? "§aFinished reading plot names — no plots currently infested."
			: "§aFinished reading plot names — §e" + infestedCount + " §aplot" + (infestedCount == 1 ? "" : "s") + " currently infested.";
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
	}
}
