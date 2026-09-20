package com.cokelord.skyblocksimplified.farming;

import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real Hypixel Garden plot grid geometry — ported directly from SkyHanni's GardenPlotApi.kt (read from its
 * actual source this round, not guessed): the Garden's 25 plots sit on a FIXED, hardcoded 5x5 grid, not
 * something scanned from in-world blocks at runtime (an earlier doc comment in this project claimed
 * "GardenPlotApi scans the world's actual barn block positions at runtime" — that was never true of the
 * real mod and has been corrected here). Every profile's Garden instance has the exact same layout, so
 * hardcoding this is completely safe, unlike guessing at other per-profile world data would be.
 *
 * <p>Confirmed real constants: each plot is a 96x96 square, the grid spans world X/Z from -240 to 240, and
 * plot id 0 (dead center of the grid) is always the Barn:
 * <pre>
 * {21, 13,  9, 14, 22}
 * {15,  5,  1,  6, 16}
 * {10,  2,  0,  3, 11}
 * {17,  7,  4,  8, 18}
 * {23, 19, 12, 20, 24}
 * </pre>
 *
 * <p>{@code inventorySlot} is the exact same layout's real mapping into Hypixel's "Configure Plots" menu
 * (SkyHanni's own {@code var slot = 2}, +1 per plot across each row, +4 gap between rows — confirmed
 * directly from source, see ConfigurePlotsReader) — used to read that specific plot's item out of the
 * inventory the instant the player opens it.
 */
public final class GardenPlotApi {
	private GardenPlotApi() {}

	private static final double PLOT_SIZE = 96.0;
	private static final int PLOT_GRID_SIZE = 5;

	// Outer index = grid Z axis, inner index = grid X axis — matches SkyHanni's own row/column order exactly.
	private static final int[][] PLOT_MAP = {
		{21, 13, 9, 14, 22},
		{15, 5, 1, 6, 16},
		{10, 2, 0, 3, 11},
		{17, 7, 4, 8, 18},
		{23, 19, 12, 20, 24},
	};

	public record Plot(int id, Vec3 middle, int inventorySlot) {}

	private static final Map<Integer, Plot> PLOTS_BY_ID = buildPlots();
	// Learned live from the real "Configure Plots" menu (ConfigurePlotsReader) — a plot's real Hypixel
	// label, which for a never-renamed plot is just its own number as a string. Defaults to the numeric id
	// until the player has actually opened that menu this session.
	private static final Map<Integer, String> PLOT_NAMES = new ConcurrentHashMap<>();
	// Real, player-facing plot LABEL (what chat/tptoplot actually use — a plain number for an un-renamed
	// plot, but confirmed by user report to genuinely be alphanumeric for some plots, e.g. "e12", not
	// always a pure integer) -> this grid's own internal Plot object. The only place this correspondence
	// can be observed at all is by reading Configure Plots (see ConfigurePlotsReader), since the internal
	// grid-position id isn't guaranteed to equal Hypixel's own real plot label for every plot. Empty until
	// the player opens that menu.
	private static final Map<String, Plot> PLOTS_BY_REAL_NUMBER = new ConcurrentHashMap<>();

	private static Map<Integer, Plot> buildPlots() {
		Map<Integer, Plot> map = new HashMap<>();
		int slot = 2;
		for (int gridZ = 0; gridZ < PLOT_GRID_SIZE; gridZ++) {
			for (int gridX = 0; gridX < PLOT_GRID_SIZE; gridX++) {
				int id = PLOT_MAP[gridZ][gridX];
				double middleX = (gridX - 2) * PLOT_SIZE;
				double middleZ = (gridZ - 2) * PLOT_SIZE;
				// y=10 is a fixed, reasonable ground-level reference (matches SkyHanni's own middle.y) — only
				// used for distance ranking between candidate plots, not for any precise vertical check.
				map.put(id, new Plot(id, new Vec3(middleX, 10.0, middleZ), slot));
				slot++;
			}
			slot += 4;
		}
		return map;
	}

	public static Plot getPlotById(int id) {
		return PLOTS_BY_ID.get(id);
	}

	/** Geometry lookup by Hypixel's real, player-facing plot label (chat/tptoplot both use this exact
	 *  string, not necessarily the same as the internal grid id — see class doc comment) — prefers the
	 *  learned mapping (see learnRealNumber); if nothing's been learned yet this session, falls back to
	 *  treating the label AS a grid id only when it's a plain integer (true for every un-renamed plot
	 *  before Configure Plots has ever been opened, and correct for the common case where they happen to
	 *  be the same number anyway); returns null (unknown geometry) for a genuinely alphanumeric label with
	 *  no learned mapping yet — callers should treat that as "can't rank by distance yet", not an error. */
	public static Plot getPlotByRealNumber(String realNumber) {
		Plot learned = PLOTS_BY_REAL_NUMBER.get(realNumber);
		if (learned != null) return learned;
		try {
			return PLOTS_BY_ID.get(Integer.parseInt(realNumber));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Records that Hypixel's real plot label {@code realNumber} corresponds to this grid position —
	 *  learned live from Configure Plots (ConfigurePlotsReader), the only place this can be observed. */
	public static void learnRealNumber(String realNumber, Plot plot) {
		PLOTS_BY_REAL_NUMBER.put(realNumber, plot);
	}

	/** For persistence (see PestPlotTeleportFeature) — real label -> grid id, a plain snapshot rather
	 *  than the live map. Per user request: this is what lets the mod skip needing the player to reopen
	 *  Configure Plots every single session just to relearn a mapping that never actually changes. */
	public static Map<String, Integer> allLearnedRealNumbers() {
		Map<String, Integer> snapshot = new HashMap<>();
		for (Map.Entry<String, Plot> entry : PLOTS_BY_REAL_NUMBER.entrySet()) {
			snapshot.put(entry.getKey(), entry.getValue().id());
		}
		return snapshot;
	}

	/** Restores a real-label -> grid-id mapping learned in an earlier session (see PestPlotTeleportFeature's
	 *  loadPersistedData) — ignores an id outside the known 0-24 grid rather than letting a corrupted config
	 *  value silently produce a null Plot later. */
	public static void loadLearnedRealNumber(String realNumber, int gridId) {
		Plot plot = PLOTS_BY_ID.get(gridId);
		if (plot != null) PLOTS_BY_REAL_NUMBER.put(realNumber, plot);
	}

	public static Collection<Plot> allPlots() {
		return PLOTS_BY_ID.values();
	}

	public static boolean isBarn(int plotId) {
		return plotId == 0;
	}

	public static String getPlotName(int id) {
		return PLOT_NAMES.getOrDefault(id, String.valueOf(id));
	}

	public static void setPlotName(int id, String name) {
		if (name != null && !name.isBlank()) PLOT_NAMES.put(id, name);
	}

	/** For persistence (see PestPlotTeleportFeature) — a plain snapshot, not the live map. */
	public static Map<Integer, String> allPlotNames() {
		return new HashMap<>(PLOT_NAMES);
	}
}
