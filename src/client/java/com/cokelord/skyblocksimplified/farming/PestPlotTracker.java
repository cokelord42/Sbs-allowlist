package com.cokelord.skyblocksimplified.farming;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which garden plot to teleport to for pests. Plot LABELS come ENTIRELY from chat spawn announcements —
 * per explicit user report, Hypixel's own tab-list "Pests" widget ("Plots: 4, 12, 13") is unreliable (it
 * listed a plot with zero actual pests in it), so this project doesn't read or cross-check against it at
 * all. Real SkyHanni source confirms it normally treats that same tab-list widget as authoritative for
 * clearing plots — if the tab-list bug the user hit turns out to have been this project's own now-fixed
 * overly-strict parsing rather than a genuine Hypixel-side issue, bringing tab-list reconciliation back
 * later (on top of everything here) would be the SkyHanni-faithful next step. For now this is chat-only,
 * per explicit instruction.
 *
 * <p>Plot identifiers are STRINGS, not numbers — confirmed by user report against a real plot literally
 * labeled "e12" (not a color-code rendering artifact, as earlier assumed; a genuinely alphanumeric plot
 * label), where sending "/tptoplot 12" instead of "/tptoplot e12" targeted the wrong place. The confirmed
 * real chat line, color codes spliced between nearly every word — note this needs stripping FIRST, not
 * just tolerating with \D*?, since a stray single-letter color code (the "b" in "§b") sitting immediately
 * before an alphanumeric label with no separator would otherwise bleed into the captured label itself
 * (e.g. misreading "§be12" as label "be12" instead of "e12"):
 * "§6§lYUCK! §24 §2 Pest §7have spawned in §aPlot §7- §be12§7!"
 *
 * <p>When more than one plot is currently queued, the target is the REAL nearest one by actual world
 * distance — ported from SkyHanni's own confirmed logic (GardenPlotApi's hardcoded 5x5 plot grid +
 * {@code getInfestedPlots().minByOrNull { it.middle.distanceSqToPlayer() }}), not an arbitrary
 * announcement-order queue or a "closest by plot NUMBER" guess. SkyHanni's real teleport hotkey doesn't
 * lock onto one plot either — it always retargets to whichever plot is nearest right now, so a closer plot
 * announced mid-session takes over immediately; matched here for the same reason (repeated presses only
 * risk looking like teleport-spam if the target actually changes on every press, and with real-distance
 * ranking it naturally won't unless something genuinely closer shows up).
 *
 * <p>With no tab list (or any other confirmed signal) to say a plot's pests have actually been cleared, an
 * announced plot ages out of consideration after a bounded window instead of lingering forever — otherwise
 * a single already-cleared plot would permanently block every later one from ever being picked.
 */
public final class PestPlotTracker {
	// Matched against the message with §-codes already stripped (see class doc comment on why stripping
	// first, not just \D*?-tolerating, is required for a correct alphanumeric label capture).
	private static final Pattern ONE_PEST_PLOT_CHAT = Pattern.compile("A\\D*?Pest\\D*?has\\D*?appeared\\D*?in\\D*?Plot\\D*?-\\D*?(?<plot>[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern MULTI_PEST_PLOT_CHAT = Pattern.compile("(?<amount>\\d+)\\D*?Pests?\\D*?have\\D*?spawned\\D*?in\\D*?Plot\\D*?-\\D*?(?<plot>[A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);

	// A plot no chat message has re-announced in this long is assumed handled/cleared rather than kept as
	// a permanent candidate — long enough to actually walk there and clear a wave, short enough that a
	// stale entry doesn't linger and get picked over a genuinely still-infested one. In practice pollLiveState()
	// below (tablist + scoreboard, every ~0.5s) clears a plot far sooner than this ever needs to fire —
	// this expiry is now just a backstop for the rare tick neither live signal is available at all.
	private static final long PLOT_EXPIRY_MILLIS = 3 * 60 * 1000;

	// Live cross-check cadence — per user report, chat-only tracking (plus the 3-minute expiry above) left
	// a plot targeted for far too long after its pests actually died, since nothing told the tracker the
	// plot was cleared until the player manually reopened Configure Plots. Twice a second is frequent
	// enough to catch a clear almost the moment it happens, cheap enough to not matter (both reads below
	// are just scans over already-built client-side data, no network calls).
	private static final long POLL_INTERVAL_MILLIS = 500;
	private static long lastPollMillis = 0;
	// How long a chat-announced plot is protected from the tab-list's "Alive: 0" clear, giving that widget
	// time to actually refresh after a real spawn before it's trusted — see pollLiveState()'s own comment.
	private static final long ALIVE_ZERO_GRACE_MILLIS = 3000;
	// Real Hypixel Garden sidebar line for whichever plot the player is CURRENTLY standing in (only shown
	// while inside a plot) — confirmed real samples from SkyHanni's own ScoreboardPattern: with pests,
	// "  Plot - 4  x1" (trailing "x<count>"); with none, just "  Plot - 3" (no count suffix at all). Matched
	// with §-codes already stripped, per this project's own established convention, rather than tolerating
	// them inline.
	// Cached rather than String.replaceAll's own implicit per-call Pattern.compile — pollLiveState() runs
	// on its own twice-a-second throttle for as long as the player is on the Garden, so this avoided
	// recompiling the same trivial regex forever while gardening.
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	private static final Pattern SCOREBOARD_PLOT_WITH_PESTS = Pattern.compile("^Plot\\s*-\\s*(?<plot>.+?)\\s+x(?<pests>\\d+)$");
	private static final Pattern SCOREBOARD_PLOT_LINE = Pattern.compile("^Plot\\s*-\\s*(?<plot>.+)$");

	// plot label -> the millis it was (most recently) announced at.
	private static final LinkedHashMap<String, Long> chatAnnouncedPlots = new LinkedHashMap<>();
	private static boolean listenerRegistered = false;

	private PestPlotTracker() {}

	/** Idempotent — safe to call from every feature that depends on this tracker's chat-based detection,
	 *  same pattern as CropBreakTracker.start(). */
	public static void start() {
		if (listenerRegistered) return;
		listenerRegistered = true;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			String text = message.getString().replaceAll("§.", "");
			Matcher matcher = ONE_PEST_PLOT_CHAT.matcher(text);
			if (matcher.find()) {
				String plotLabel = matcher.group("plot");
				chatAnnouncedPlots.put(plotLabel, System.currentTimeMillis());
				return;
			}
			matcher = MULTI_PEST_PLOT_CHAT.matcher(text);
			if (matcher.find()) {
				String plotLabel = matcher.group("plot");
				chatAnnouncedPlots.put(plotLabel, System.currentTimeMillis());
			}
		});
	}

	/** The plot label to teleport to right now (the exact string /tptoplot expects — may be a plain
	 *  number or a genuinely alphanumeric label like "e12"), or null if chat hasn't announced any
	 *  still-fresh plot at all (caller should fall back to warping to garden spawn in that case). Always
	 *  the real-world-nearest candidate to the player right now — see the class doc comment for why this
	 *  doesn't "lock" onto one plot the way earlier revisions of this tracker did. */
	public static String plotToTeleportTo() {
		long now = System.currentTimeMillis();
		chatAnnouncedPlots.entrySet().removeIf(e -> now - e.getValue() > PLOT_EXPIRY_MILLIS);
		if (chatAnnouncedPlots.isEmpty()) return null;

		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null) return chatAnnouncedPlots.keySet().iterator().next();
		Vec3 playerPos = player.position();

		String best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (String plotLabel : chatAnnouncedPlots.keySet()) {
			// plotLabel here is Hypixel's real, player-facing plot label (from chat) — getPlotByRealNumber
			// prefers whatever's actually been learned from Configure Plots, or null (unknown geometry,
			// deprioritized below but not excluded) if that label hasn't been seen there yet this session.
			GardenPlotApi.Plot plot = GardenPlotApi.getPlotByRealNumber(plotLabel);
			double distSq = plot != null ? playerPos.distanceToSqr(plot.middle()) : Double.MAX_VALUE;
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = plotLabel;
			}
		}
		return best;
	}

	/** Cross-checks every currently-tracked plot against Hypixel's own live scoreboard state — called every
	 *  client tick from PestPlotTeleportFeature.onTick() (self-throttled here to twice a second). Only the
	 *  sidebar "Plot - X" line (shown while actually standing in a plot, always about that one specific
	 *  plot) is used: it both confirms (refreshes the timestamp, same as a fresh chat announcement) and
	 *  clears a plot the moment its own count hits zero, rather than waiting to walk away and let it expire.
	 *
	 *  <p>An earlier revision also cross-checked the tab-list "Plots: ..." line (clearing any tracked plot
	 *  no longer listed there) — removed per user report: that widget's real text format isn't independently
	 *  confirmed against live Hypixel data, and it was observed clearing a plot chat had JUST confirmed
	 *  seconds earlier, well within its own real infestation window. The scoreboard line above is a
	 *  narrower, more trustworthy signal (it requires the player to actually be standing in that specific
	 *  plot, not a garden-wide list this project has already had one confirmed-wrong reading from).
	 *
	 *  <p>Also now checks the tab-list's real "Alive: N" line (user-confirmed real text, a direct garden-
	 *  wide count rather than an inferred list) — a confirmed 0 clears every currently tracked plot at once,
	 *  since a direct zero count is about as unambiguous a signal as this project can get. */
	public static void pollLiveState() {
		long now = System.currentTimeMillis();
		if (now - lastPollMillis < POLL_INTERVAL_MILLIS) return;
		lastPollMillis = now;

		Integer alive = PestTabListState.aliveCount();
		if (alive != null && alive == 0 && !chatAnnouncedPlots.isEmpty()) {
			// Per user report ("detecting the spawning message correctly" but MB5 still not teleporting there),
			// the tab-list "Pests" widget almost certainly doesn't update instantly the moment a pest actually
			// spawns — Hypixel refreshes tab-list widgets on their own cadence, not on every individual spawn
			// event. Trusting a fresh "Alive: 0" reading unconditionally meant a plot chat had JUST announced
			// (this exact poll cycle, or the one before it) could get wiped out again before the widget had
			// caught up to reflect it. Only clearing entries older than this grace window gives the widget
			// time to catch up without losing the "confirmed zero clears everything" behavior for anything
			// that's actually been sitting stale.
			long cutoff = now - ALIVE_ZERO_GRACE_MILLIS;
			for (String tracked : java.util.List.copyOf(chatAnnouncedPlots.keySet())) {
				Long announcedAt = chatAnnouncedPlots.get(tracked);
				if (announcedAt != null && announcedAt < cutoff) {
					setConfirmedPestState(tracked, 0);
				}
			}
		}

		for (String line : com.cokelord.skyblocksimplified.hud.ScoreboardReader.readCurrentSidebarLines()) {
			String plain = COLOR_CODE.matcher(line).replaceAll("").trim();
			Matcher withPests = SCOREBOARD_PLOT_WITH_PESTS.matcher(plain);
			if (withPests.matches()) {
				setConfirmedPestState(withPests.group("plot").trim(), Integer.parseInt(withPests.group("pests")));
				continue;
			}
			Matcher noPests = SCOREBOARD_PLOT_LINE.matcher(plain);
			if (noPests.matches()) {
				setConfirmedPestState(noPests.group("plot").trim(), 0);
			}
		}
	}

	/** Called when a /tptoplot attempt actually fails (see PestPlotTeleportFeature) — the announced plot is
	 *  very likely stale, so it's dropped from consideration entirely, otherwise the very next call would
	 *  just re-pick the same failing plot straight back off. */
	public static void clear(String plotLabel) {
		chatAnnouncedPlots.remove(plotLabel);
	}

	/** Ground-truth refresh from the real "Configure Plots" menu (see ConfigurePlotsReader) — unlike a
	 *  chat announcement, this is a direct, exact read of Hypixel's own per-plot pest count, so it both
	 *  confirms/refreshes a still-infested plot AND actually clears one chat never got an update for
	 *  (opening that menu was always meant to be a manual "confirm/refresh plot state" fallback per this
	 *  feature's own spec — this is what finally makes that real instead of just a suggestion with no
	 *  actual effect). */
	public static void setConfirmedPestState(String plotLabel, int pests) {
		if (pests > 0) {
			chatAnnouncedPlots.put(plotLabel, System.currentTimeMillis());
		} else {
			chatAnnouncedPlots.remove(plotLabel);
		}
	}
}
