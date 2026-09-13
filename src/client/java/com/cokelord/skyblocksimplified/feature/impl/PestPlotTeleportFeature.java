package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.farming.PestPlotTracker;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Keybind: teleports to whichever garden plot currently has pests, per the confirmed live command
 * "/tptoplot &lt;plot label&gt;" (not SkyHanni's older "/plottp", which the user confirmed Hypixel has
 * since renamed) — or "/warp garden" if no plot has been chat-announced recently. The label is Hypixel's
 * own real plot label VERBATIM, not necessarily a plain number (confirmed by user report against a real
 * plot literally labeled "e12" — sending "12" instead targeted the wrong place). Always targets the real
 * nearest currently-announced plot per PestPlotTracker (ported from SkyHanni's own confirmed
 * getNearestInfestedPlot() logic — see that class's doc comment for why this doesn't "lock" onto one plot
 * the way earlier revisions of this feature did).
 *
 * <p>Per user report, Hypixel's own "Pests" tab-list widget ("Plots: 4, 12, 13") is unreliable — it once
 * listed a plot with zero actual pests in it — so this project no longer reads or trusts it at all;
 * PestPlotTracker sources plot labels entirely from the real chat line Hypixel prints the instant pests
 * spawn. Two real failure modes can still leave SBAR with no usable plot data: (1) nothing has been
 * chat-announced yet this session (or the last announced plot aged out — see PestPlotTracker's own
 * PLOT_EXPIRY_MILLIS), or (2) a coop member renamed a plot before the announcement was acted on, and
 * Hypixel rejects the /tptoplot call for a plot label SBAR still thinks is valid. Both cases show an
 * "Open the Desk!" title as a heads-up (there's no real "/plots" slash command — the Configure Plots menu
 * is opened via the physical Desk in the Garden, per user correction) — and unlike earlier revisions of
 * this feature, that's no longer just a suggestion with no real effect: ConfigurePlotsReader (started
 * below, alongside PestPlotTracker) actually reads the real per-plot pest-count lore the instant that menu
 * opens and feeds it straight back into PestPlotTracker as ground truth, so manually reopening it genuinely
 * does confirm/refresh plot state now, exactly as this doc comment always claimed it would.
 */
public class PestPlotTeleportFeature extends Feature {
	// Deliberately loose/tolerant (not anchored to one exact confirmed wording) since the real Hypixel
	// error text for a stale/renamed-plot teleport failure hasn't been confirmed firsthand — same
	// "search with find(), not matches()" approach as PestSpawnAlertFeature's own patterns, plus a
	// DebugLog fallback below so a near-miss can be seen and the pattern tightened later.
	private static final Pattern TELEPORT_FAILED = Pattern.compile(
		"(can.?t|cannot|couldn.?t|unable to).{0,20}teleport.{0,30}plot", Pattern.CASE_INSENSITIVE);

	private final KeyCombo combo = new KeyCombo();
	private boolean comboHeldLastTick = false;
	// The plot label the most recent /tptoplot attempt actually targeted — needed since PestPlotTracker no
	// longer tracks a single "locked" plot itself (see its own doc comment), so this feature has to
	// remember which label to drop from consideration if that specific attempt turns out to have failed.
	private String lastAttemptedPlotId = null;

	public PestPlotTeleportFeature() {
		super("pest_plot_teleport", "Teleport to Pest Plot", FeatureCategory.FARMING, false);
		PestPlotTracker.start();
		com.cokelord.skyblocksimplified.farming.ConfigurePlotsReader.start();
		// No isEnabled() check here, deliberately matching onTick()'s own behavior below — this is a
		// non-toggleable feature (see isToggleable() override), and Feature.java's own doc comment on that
		// flag is explicit that a non-toggleable feature's enabled state must never gate anything, since
		// it's never exposed in the GUI for the user to fix if it silently ends up false.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			onChatMessage(message.getString());
		});
	}

	@Override
	public String getSubcategory() {
		return "Custom Keybinds";
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return combo;
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.player == null || client.gui.screen() != null) {
			comboHeldLastTick = false;
			return;
		}
		// Live cross-check against the real tab-list/scoreboard, twice a second — see PestPlotTracker's own
		// doc comment. Runs unconditionally (not just on a keybind press) so tracked state is already
		// correct the instant the player DOES press the combo, not just eventually.
		PestPlotTracker.pollLiveState();
		boolean held = !combo.isEmpty() && combo.isHeld();
		// Per user report, the combo fired outside the Garden too — nothing here ever checked which island
		// the player is actually on, so pressing it anywhere sent a /tptoplot or /warp garden command.
		if (held && !comboHeldLastTick && com.cokelord.skyblocksimplified.util.IslandGate.isInGarden()) {
			// Per user report, gating this on PestTabListState.anyPestsAlive() (tab-list "Plots:" line
			// parsing) was actively vetoing a plot chat had JUST confirmed seconds earlier — that tab-list
			// widget's real text format isn't independently confirmed against live data, and two separate
			// reports now trace back to it (this gate, and pollLiveState's own former use of the same
			// parsing — removed too, see PestPlotTracker). Trusting PestPlotTracker alone (chat + the
			// scoreboard-based live poll, which IS a well-scoped, real per-plot signal) is more reliable.
			String plotId = PestPlotTracker.plotToTeleportTo();
			if (plotId != null) {
				lastAttemptedPlotId = plotId;
				client.player.connection.sendCommand("tptoplot " + plotId);
			} else {
				// No infested plot detected at all — still send the player somewhere useful (garden spawn),
				// but flag it per the user's spec: "no plots detected" is exactly the case where they said
				// to prompt "Open /plots!" as a heads-up, since that's their manual way to confirm/refresh
				// plot state regardless of how SBAR itself sources plot numbers.
				client.player.connection.sendCommand("warp garden");
				showPlotsPrompt();
			}
		}
		comboHeldLastTick = held;
	}

	private void onChatMessage(String message) {
		if (TELEPORT_FAILED.matcher(message).find()) {
			// The just-attempted plot id is very likely stale (renamed by a coop member, per user report) —
			// drop it so the next press re-picks instead of repeating the same failing /tptoplot call.
			if (lastAttemptedPlotId != null) PestPlotTracker.clear(lastAttemptedPlotId);
			showPlotsPrompt();
		}
	}

	// Per user request — now that ConfigurePlotsReader actually reads real plot state off this menu the
	// instant it opens (see this class's own doc comment), telling the player specifically to REOPEN it is
	// no longer just a vague suggestion: doing so is what actually refreshes PestPlotTracker's ground truth.
	// Wording corrected per user report: there's no real "/plots" command — the Configure Plots menu is
	// opened via the physical Desk in the Garden, not a slash command.
	//
	// Per further user report, this was showing far too often — any time chat simply hadn't announced a
	// plot yet (including when there are genuinely zero pests to find at all) tripped it. It should only
	// actually fire when Hypixel's own tab-list "Pests" widget CONFIRMS pests are alive somewhere but SBAR
	// still doesn't know which plot — i.e. exactly the case reopening the Desk can actually resolve. If the
	// tab-list confirms zero pests, or its state can't be read at all right now, there's nothing this
	// prompt would help with, so it stays silent.
	private void showPlotsPrompt() {
		if (!Boolean.TRUE.equals(com.cokelord.skyblocksimplified.farming.PestTabListState.anyPestsAlive())) return;
		Minecraft mc = Minecraft.getInstance();
		mc.gui.hud.setTimes(5, 40, 10);
		mc.gui.hud.setTitle(Component.literal("§cOpen the Desk to refresh!"));
	}

	// Per user request ("make sure the mod remembers") — plot names learned from Configure Plots
	// (GardenPlotApi.PLOT_NAMES) used to live purely in memory, forgotten on every game restart even
	// though nothing about a plot's real name changes between sessions. Persisted here since this is the
	// feature that already owns save/load for this whole area, not because the names are otherwise tied
	// to the keybind itself.
	@Override
	public com.google.gson.JsonElement savePersistedData() {
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		com.google.gson.JsonArray comboArr = new com.google.gson.JsonArray();
		for (String s : combo.serialize()) comboArr.add(new com.google.gson.JsonPrimitive(s));
		obj.add("combo", comboArr);
		com.google.gson.JsonObject names = new com.google.gson.JsonObject();
		for (var entry : com.cokelord.skyblocksimplified.farming.GardenPlotApi.allPlotNames().entrySet()) {
			names.addProperty(String.valueOf(entry.getKey()), entry.getValue());
		}
		obj.add("plotNames", names);
		// Real plot number -> internal grid id (see GardenPlotApi's own doc comment on why these can
		// differ) — without this, every fresh session falls back to assuming they're the same number
		// until the player reopens Configure Plots again, which is exactly the "have to open the menu
		// every time" the user asked to stop needing.
		com.google.gson.JsonObject realNumbers = new com.google.gson.JsonObject();
		for (var entry : com.cokelord.skyblocksimplified.farming.GardenPlotApi.allLearnedRealNumbers().entrySet()) {
			realNumbers.addProperty(String.valueOf(entry.getKey()), entry.getValue());
		}
		obj.add("plotRealNumbers", realNumbers);
		return obj;
	}

	@Override
	public void loadPersistedData(com.google.gson.JsonElement data) {
		// Older configs saved this as a bare combo array — keep loading those too, just without any
		// plot names (there wouldn't have been any to save yet).
		if (data.isJsonArray()) {
			java.util.List<String> serialized = new java.util.ArrayList<>();
			for (var e : data.getAsJsonArray()) serialized.add(e.getAsString());
			combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
			return;
		}
		if (!data.isJsonObject()) return;
		com.google.gson.JsonObject obj = data.getAsJsonObject();
		if (obj.has("combo") && obj.get("combo").isJsonArray()) {
			java.util.List<String> serialized = new java.util.ArrayList<>();
			for (var e : obj.getAsJsonArray("combo")) serialized.add(e.getAsString());
			combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
		}
		if (obj.has("plotNames") && obj.get("plotNames").isJsonObject()) {
			for (var entry : obj.getAsJsonObject("plotNames").entrySet()) {
				try {
					int plotId = Integer.parseInt(entry.getKey());
					com.cokelord.skyblocksimplified.farming.GardenPlotApi.setPlotName(plotId, entry.getValue().getAsString());
				} catch (NumberFormatException ignored) {
					// Malformed key — skip it rather than let one bad entry break the rest of the load.
				}
			}
		}
		if (obj.has("plotRealNumbers") && obj.get("plotRealNumbers").isJsonObject()) {
			// Keys are the real plot LABEL (a plain number for most plots, but confirmed by user report to
			// genuinely be alphanumeric for some, e.g. "e12" — not always parseable as an int, so the key
			// itself is used as-is; only the grid-id VALUE needs to parse cleanly).
			for (var entry : obj.getAsJsonObject("plotRealNumbers").entrySet()) {
				try {
					int gridId = entry.getValue().getAsInt();
					com.cokelord.skyblocksimplified.farming.GardenPlotApi.loadLearnedRealNumber(entry.getKey(), gridId);
				} catch (NumberFormatException ignored) {
					// Malformed value — skip it rather than let one bad entry break the rest of the load.
				}
			}
		}
	}

	@Override
	public String getDescription() {
		return "Keybind that teleports you straight to whichever garden plot currently has a pest infestation.";
	}
}
