package com.cokelord.skyblocksimplified.farming;

import com.cokelord.skyblocksimplified.hud.TabListReader;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether Hypixel's own real tab-list "Pests" widget currently says any pests are alive garden-wide —
 * ported from SkyHanni's own confirmed {@code infestedPlotsTabListPattern} ("\sPlots: (?<plots>.*)"),
 * used only for this yes/no/unknown aggregate signal, NOT to identify which specific plot has pests (per
 * explicit user report that this same widget once listed a plot with zero actual pests in it — see
 * PestPlotTracker's own doc comment on why plot-level targeting stays chat-only). A wrong plot NUMBER in
 * this widget doesn't matter here, since only "is the Plots line present/non-empty at all" is read.
 *
 * <p>The widget's "Cooldown: ..." line (already read every tick by PestCooldownFeature) is used as a
 * presence signal: when it's there but no "Plots: ..." line accompanies it, that's Hypixel's own confirmation
 * of zero current pests, not just this project failing to find one — matching the real widget's actual
 * layout (the Plots line only appears at all once something's infested). If neither line is found this
 * tick, the widget simply isn't visible right now (not in the Garden, or it hasn't loaded yet) and the
 * real state can't be told at all, so this returns null rather than guessing.
 */
public final class PestTabListState {
	// Cached rather than String.replaceAll's own implicit per-call Pattern.compile — every method below is
	// read every tick by callers (PestCooldownFeature etc.) while in the Garden, over every tab-list line,
	// so this avoided recompiling the same trivial regex 20+ times a second per line.
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	private static final Pattern COOLDOWN_LINE = Pattern.compile("\\sCooldown: ");
	private static final Pattern PLOTS_LINE = Pattern.compile("\\sPlots: (?<plots>.*)");
	// Confirmed real by direct user report: the widget also has a literal "Alive: N" line — a straight
	// garden-wide pest COUNT, not an inferred list-presence check. This is a much more direct, unambiguous
	// signal than the "Plots:" line above (which this project's own earlier attempts to parse turned out
	// unreliable), so it's checked first wherever both are read.
	private static final Pattern ALIVE_LINE = Pattern.compile("\\sAlive: (?<count>\\d+)");

	private PestTabListState() {}

	/** The real garden-wide live pest count straight from the tab-list widget's own "Alive: N" line, or
	 *  {@code null} if that line isn't visible this tick (not in the Garden, or it hasn't loaded yet). */
	public static Integer aliveCount() {
		for (String raw : TabListReader.readLines()) {
			String line = COLOR_CODE.matcher(raw).replaceAll("");
			Matcher m = ALIVE_LINE.matcher(line);
			if (m.find()) {
				try {
					return Integer.parseInt(m.group("count"));
				} catch (NumberFormatException e) {
					return null;
				}
			}
		}
		return null;
	}

	/** {@code true} = tab-list confirms at least one plot is currently infested; {@code false} = tab-list
	 *  confirms zero; {@code null} = can't tell right now. Prefers the direct "Alive: N" count; falls back
	 *  to the older "Plots: ..." list-presence check only if that line isn't visible this tick. */
	public static Boolean anyPestsAlive() {
		Integer alive = aliveCount();
		if (alive != null) return alive > 0;

		boolean widgetPresent = false;
		Boolean plotsNonEmpty = null;
		for (String raw : TabListReader.readLines()) {
			String line = COLOR_CODE.matcher(raw).replaceAll("");
			if (COOLDOWN_LINE.matcher(line).find()) widgetPresent = true;
			Matcher plotsMatcher = PLOTS_LINE.matcher(line);
			if (plotsMatcher.find()) {
				widgetPresent = true;
				plotsNonEmpty = !plotsMatcher.group("plots").trim().isEmpty();
			}
		}
		if (plotsNonEmpty != null) return plotsNonEmpty;
		return widgetPresent ? Boolean.FALSE : null;
	}

	/** The exact set of plot labels the tab-list "Plots: ..." line currently lists (color-stripped,
	 *  trimmed), or {@code null} if that line isn't visible this tick (unknown — not "zero"). Per this
	 *  class's own doc comment, a wrongly-LISTED plot is a known real Hypixel-side glitch, so this is only
	 *  safe to use for confirming a tracked plot has dropped OFF the list (a real clear), never for adding
	 *  a new plot no chat announcement has confirmed yet. */
	public static Set<String> listedPlots() {
		for (String raw : TabListReader.readLines()) {
			String line = COLOR_CODE.matcher(raw).replaceAll("");
			Matcher plotsMatcher = PLOTS_LINE.matcher(line);
			if (plotsMatcher.find()) {
				String plotsText = plotsMatcher.group("plots").trim();
				Set<String> result = new HashSet<>();
				if (plotsText.isEmpty()) return result;
				for (String part : plotsText.split(",")) {
					String trimmed = part.trim();
					if (!trimmed.isEmpty()) result.add(trimmed);
				}
				return result;
			}
		}
		return null;
	}
}
