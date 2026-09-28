package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaData;
import com.cokelord.skyblocksimplified.diana.DianaState;
import com.cokelord.skyblocksimplified.feature.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared shape of the Diana Tracker and Mob Tracker HUDs: a title plus one line per tracked key, formatted
 * "12x Chimera (24x total)" — the event count, then the all-time total. Each line can be turned off in the
 * settings, zero lines can be hidden, and "Only While Holding Spade" narrows visibility from "in the Hub
 * during the Diana event" to "holding a spade".
 */
public abstract class DianaCountsHudFeature extends DianaHudFeature {
	public record Line(String key, String label, String color, boolean coins) {
		public Line(String key, String label, String color) { this(key, label, color, false); }
	}

	private final String title;
	private final List<Line> lineSpecs;
	private List<String> cached = List.of();
	private int cachedVersion = -1;
	private long cachedAt = 0;

	protected DianaCountsHudFeature(String id, String displayName, String description, String title, List<Line> lineSpecs,
									float anchorX, float anchorY) {
		super(id, displayName, description, anchorX, anchorY);
		this.title = title;
		this.lineSpecs = lineSpecs;
		defBool("spadeOnly", false);
		defBool("hideZero", true);
		for (Line line : lineSpecs) defBool("line_" + line.key(), true);
	}

	@Override
	protected boolean shouldShow() {
		if (!DianaState.inHub()) return false;
		return bool("spadeOnly") ? DianaState.holdingSpade() : DianaState.eventActive();
	}

	@Override
	protected List<String> lines() {
		long now = System.currentTimeMillis();
		if (cachedVersion != DianaData.version() || now - cachedAt > 1000) {
			cachedVersion = DianaData.version();
			cachedAt = now;
			cached = build(false);
		}
		return cached;
	}

	@Override
	protected List<String> exampleLines() { return build(true); }

	private List<String> build(boolean example) {
		List<String> out = new ArrayList<>();
		out.add("§e§l" + title);
		boolean hideZero = bool("hideZero") && !example;
		for (Line line : lineSpecs) {
			if (!bool("line_" + line.key())) continue;
			int ev = example ? 12 : DianaData.event(line.key());
			int tot = example ? 24 : DianaData.total(line.key());
			if (hideZero && ev == 0 && tot == 0) continue;
			if (line.coins()) {
				out.add(line.color() + line.label() + ": §f" + compact(ev) + " §7(" + compact(tot) + " total)");
			} else {
				out.add(line.color() + format(ev) + "x " + line.label() + " §7(" + format(tot) + "x total)");
			}
		}
		return out.size() == 1 && !example ? List.of() : out;
	}

	private static String format(int n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	static String compact(long n) {
		if (n >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", n / 1e9);
		if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
		if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1e3);
		return Long.toString(n);
	}

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> lineRows = new ArrayList<>();
		for (Line line : lineSpecs) lineRows.add(toggle(line.label(), "line_" + line.key(), "Show the " + line.label() + " line."));
		List<SettingRow> rows = new ArrayList<>();
		rows.add(toggle("Only While Holding Spade", "spadeOnly",
			"Only show while holding a spade. Off: shows in the Hub whenever the Diana event is active."));
		rows.add(toggle("Hide Zero Lines", "hideZero", "Hides lines you haven't gotten anything for yet."));
		rows.add(new SettingRow.Group("Lines", "lines", lineRows));
		rows.addAll(extraRows());
		return rows;
	}

	protected List<SettingRow> extraRows() { return List.of(); }
}
