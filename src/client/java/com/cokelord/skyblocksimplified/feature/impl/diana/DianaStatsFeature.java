package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaData;
import com.cokelord.skyblocksimplified.diana.DianaState;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.util.ChatText;

import java.util.ArrayList;
import java.util.List;

/** "Since" stats, ported from SBO's DianaStats overlay (same counters, same lootshare split). */
public class DianaStatsFeature extends DianaHudFeature {
	private static DianaStatsFeature instance;

	private record Stat(String key, String label, String color, String lsKey) {}

	private static final List<Stat> STATS = List.of(
		new Stat("mobsSinceKing", "Mobs since King", "§c", null),
		new Stat("kingSinceWool", "Kings since Wool", "§c", "kingSinceLsWool"),
		new Stat("mobsSinceManti", "Mobs since Manti", "§c", null),
		new Stat("mantiSinceCore", "Mantis since Core", "§c", "mantiSinceLsCore"),
		new Stat("mantiSinceStinger", "Mantis since Stinger", "§c", "mantiSinceLsStinger"),
		new Stat("mobsSinceInq", "Mobs since Inq", "§d", null),
		new Stat("inqsSinceChim", "Inqs since Chimera", "§d", "inqsSinceLsChim"),
		new Stat("mobsSinceSphinx", "Mobs since Sphinx", "§d", null),
		new Stat("sphinxSinceFood", "Sphinxes since Food", "§5", "sphinxSinceLsFood"),
		new Stat("champsSinceRelic", "Champs since Relic", "§5", null),
		new Stat("minotaursSinceStick", "Minotaurs since Stick", "§6", null));

	private List<String> cached = List.of();
	private int cachedVersion = -1;
	private long cachedAt = 0;

	public DianaStatsFeature() {
		super("diana_stats", "Diana Stats",
			"Shows luck stats like mobs since your last Inquisitor and Inquisitors since your last Chimera.", 0.2f, 0.25f);
		defBool("spadeOnly", false);
		defBool("showLs", true);
		defBool("sinceMessages", true);
		for (Stat stat : STATS) defBool("line_" + stat.key(), true);
		instance = this;
	}

	/** "Took N mobs to get an Inquis!" / b2b chat messages. */
	public static boolean sinceMessages() { return instance != null && instance.isEnabled() && instance.bool("sinceMessages"); }

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
		out.add("§e§lDiana Stats");
		boolean showLs = bool("showLs");
		for (Stat stat : STATS) {
			if (!bool("line_" + stat.key())) continue;
			int value = example ? 42 : DianaData.since(stat.key());
			String line = "§7- " + stat.color() + stat.label() + ": §b" + value;
			if (showLs && stat.lsKey() != null) line += "§7, " + stat.color() + "LS: §b" + (example ? 7 : DianaData.since(stat.lsKey()));
			out.add(line);
		}
		return out;
	}

	@Override
	public List<SettingRow> getSettingRows() {
		List<SettingRow> lineRows = new ArrayList<>();
		for (Stat stat : STATS) lineRows.add(toggle(stat.label(), "line_" + stat.key(), "Show the " + stat.label() + " line."));
		return List.of(
			toggle("Only While Holding Spade", "spadeOnly", "Only show while holding a spade. Off: shows in the Hub whenever the Diana event is active."),
			toggle("Show Lootshare Stats", "showLs", "Adds the lootshare counter to lines that have one."),
			toggle("Chat Messages", "sinceMessages", "\"Took 120 Mobs to get an Inquis!\" and b2b messages in chat."),
			new SettingRow.Group("Lines", "lines", lineRows),
			new SettingRow.Button("Reset Stats", () -> {
				DianaData.resetSince();
				ChatText.clientMessage("§b[SBS] §aDiana stats reset.");
			}));
	}
}
