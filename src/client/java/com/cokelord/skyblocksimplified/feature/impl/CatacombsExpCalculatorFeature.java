package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.HypixelElectionApi;
import com.cokelord.skyblocksimplified.api.SkyblockStatsApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Per user request ("add a catacombs experience calculator... only a cog not a toggle"): a pure
 *  calculator, no world/HUD presence at all — {@link #isToggleable()} false the same way
 *  {@link PanelThemeFeature} is, so the module row is always "on" and only ever shows a settings cog.
 *
 *  <p>Pulls what it can from the real Hypixel API (via {@link SkyblockStatsApi}, the same keyless proxy
 *  Party Finder already uses) and {@link HypixelElectionApi} for the current mayor, and leaves the rest —
 *  Bonzo's Shard level, the floor being farmed, the target level, and the average run length — as manual
 *  fields, since none of those are ever exposed by any API. Bonzo's Shard specifically has no SkyBlock API
 *  field at all (it's a Bonzo-masterwork accessory upgrade tracked purely client-side by Hypixel's own GUI),
 *  so it can only ever be user-entered.
 *
 *  <p>Real per-level Catacombs (dungeon) XP requirements below — independently confirmed against
 *  NotEnoughUpdates-REPO's own {@code constants/leveling.json} "catacombs" array, the same public, actively
 *  maintained constant set every other SkyBlock tool sources this from — rather than guessed, since
 *  {@link SkyblockStatsApi}'s own class doc comment explicitly flagged this table as the one thing blocking
 *  a real "Catacombs Level" number from being computed at all. */
public class CatacombsExpCalculatorFeature extends Feature {
	// Real per-level XP cost, level 1 through 50 (index 0 = level 0->1, ... index 49 = level 49->50) — see
	// this class's own doc comment for the source. Catacombs (like every dungeon class) caps at level 50;
	// XP earned past that no longer raises a displayed level, so this table intentionally stops there.
	private static final long[] LEVEL_XP = {
		50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000,
		27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000,
		1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000,
		24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000
	};
	private static final int MAX_LEVEL = LEVEL_XP.length;

	public enum Floor {
		F1("F1", 75), F2("F2", 180), F3("F3", 400), F4("F4", 1500), F5("F5", 3500), F6("F6", 6000), F7("F7", 26000),
		M1("M1", 14000), M2("M2", 17000), M3("M3", 35000), M4("M4", 53000), M5("M5", 100000), M6("M6", 200000), M7("M7", 550000);

		public final String label;
		public final int baseXp;
		Floor(String label, int baseXp) { this.label = label; this.baseXp = baseXp; }
	}

	/** One computed answer, per the user's exact spec: multiplier breakdown, XP still needed, and a
	 *  rounded-up run count accounting for the first-30-minutes boost window (see {@link #computeResult}). */
	public record Result(double multiplierPercent, double xpNeeded, int currentLevel, double currentLevelProgressXp,
						  long currentLevelXpForNext, int runsNeeded, double xpPerNormalRun, double xpPerEarlyRun,
						  int earlyBoostedRuns) {}

	private String bonzoShardInput = "";
	private Floor floor = Floor.F7;
	private String targetLevelInput = "50";
	private boolean includeEarlyBoost = false;
	private String runMinutesInput = "5";
	private String runSecondsInput = "0";
	// Manual override per user request ("just add a checkbox for owning the catacombs expert ring instead") —
	// the live API-based stats().hasCatacombsExpertRing() detection was reported broken in practice, so this
	// checkbox is now the sole source of truth for the ring bonus rather than a fallback.
	private boolean ownsExpertRing = false;

	public CatacombsExpCalculatorFeature() {
		// Cog-only module (see this class's own doc comment) — enabledByDefault=true since there's no toggle
		// to ever flip it on later, same convention PanelThemeFeature's own constructor already established.
		super("catacombs_exp_calculator", "Catacombs Experience Calculator", FeatureCategory.COMBAT, true);
		// Idempotent (see HypixelElectionApi.start()'s own guard) — safe to call unconditionally here so
		// mayor data starts loading as soon as the mod does, not only once this module's cog is first opened.
		HypixelElectionApi.start();
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	public boolean isToggleable() { return false; }

	@Override
	public String getDescription() {
		return "Estimates how many runs of a floor you need to reach a target Catacombs level, factoring in your real API-detected bonuses plus manually entered ones.";
	}

	/** Called every frame the settings cog is open (see MainScreen's own dispatch) — request() is already a
	 *  no-op once cached or in flight, so this is safe to call unconditionally rather than needing its own
	 *  once-per-session gate. */
	public void refreshStats() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		SkyblockStatsApi.request(mc.player.getUUID().toString());
	}

	public SkyblockStatsApi.PlayerStats stats() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		return SkyblockStatsApi.get(mc.player.getUUID().toString());
	}

	/** Per user correction: Derpy's Catacombs XP boost is her own mayor ability, "MOAR SKILLZ!!!" — always
	 *  active for the whole term whenever she's the elected mayor (unlike a minister perk, which can be
	 *  active under a DIFFERENT mayor). So this checks the mayor's own name directly, not the perk list —
	 *  the perk-list check used before this correction was a guess that this session couldn't verify live and
	 *  turned out wrong (there's no separate opt-in perk for it at all). */
	public boolean isDerpyXpBoostActive() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		return mayor != null && "Derpy".equalsIgnoreCase(mayor.name());
	}

	// ---- GUI editing surface (called from MainScreen) ----

	public String getBonzoShardInput() { return bonzoShardInput; }
	public void setBonzoShardInput(String value) { bonzoShardInput = value; }
	public Floor getFloor() { return floor; }
	public void setFloor(Floor value) { floor = value; }
	public String getTargetLevelInput() { return targetLevelInput; }
	public void setTargetLevelInput(String value) { targetLevelInput = value; }
	public boolean isIncludeEarlyBoost() { return includeEarlyBoost; }
	public void setIncludeEarlyBoost(boolean value) { includeEarlyBoost = value; }
	public String getRunMinutesInput() { return runMinutesInput; }
	public void setRunMinutesInput(String value) { runMinutesInput = value; }
	public String getRunSecondsInput() { return runSecondsInput; }
	public void setRunSecondsInput(String value) { runSecondsInput = value; }
	public boolean isOwnsExpertRing() { return ownsExpertRing; }
	public void setOwnsExpertRing(boolean value) { ownsExpertRing = value; }

	/** Bonzo's Shard: 0 (no upgrade) through 10, 1% more Catacombs EXP per level — empty/invalid input reads
	 *  as 0 per user spec ("Bonzo shard should be assumed 0 if nothing is input"). */
	public int bonzoShardLevel() {
		try {
			return Math.max(0, Math.min(10, Integer.parseInt(bonzoShardInput.trim())));
		} catch (Exception e) {
			return 0;
		}
	}

	private int targetLevel() {
		try {
			return Math.max(1, Math.min(MAX_LEVEL, Integer.parseInt(targetLevelInput.trim())));
		} catch (Exception e) {
			return MAX_LEVEL;
		}
	}

	/** Empty/invalid reads as the user's own stated default ("run length... default 5 minutes per run"). */
	private double runLengthMinutes() {
		int minutes, seconds;
		try { minutes = Integer.parseInt(runMinutesInput.trim()); } catch (Exception e) { minutes = 5; }
		try { seconds = Integer.parseInt(runSecondsInput.trim()); } catch (Exception e) { seconds = 0; }
		double total = Math.max(0, minutes) + Math.max(0, seconds) / 60.0;
		return total > 0 ? total : 5.0;
	}

	/** Bonzo's Shard: level N gives N% more Catacombs EXP (10 -> 10%, 0 -> 0%) — per user spec, obvious/linear
	 *  between the two given endpoints. */
	private static double bonzoPercent(int level) { return level * 0.01; }

	/** Real Hecatomb enchant Catacombs-EXP bonus, level 1-10: 0.28% at level 1, +0.08% per level, reaching
	 *  exactly 1% at level 10 — per user spec's own worked example. Level 0 (no Hecatomb helmet) is 0%. */
	private static double hecatombPercent(int level) {
		if (level <= 0) return 0;
		int clamped = Math.min(10, level);
		return 0.0028 + 0.0008 * (clamped - 1);
	}

	/** Current level derived from raw XP using {@link #LEVEL_XP} — the real number
	 *  {@link SkyblockStatsApi}'s own class doc comment says this codebase previously had no verified table
	 *  to compute at all. */
	private static int currentLevel(double xp) {
		double remaining = xp;
		int level = 0;
		while (level < MAX_LEVEL && remaining >= LEVEL_XP[level]) {
			remaining -= LEVEL_XP[level];
			level++;
		}
		return level;
	}

	private static long cumulativeXpForLevel(int level) {
		long total = 0;
		for (int i = 0; i < level && i < MAX_LEVEL; i++) total += LEVEL_XP[i];
		return total;
	}

	/** The whole calculator, per the user's exact worked spec: every percentage bonus adds onto a 100% base
	 *  (confirmed against the user's own "121% total" example: 100 + 10 bonzo + 10 ring + 1 hecatomb = 121,
	 *  with Derpy and the early-boost deliberately excluded from that illustration), the first-30-minutes
	 *  boost is a SEPARATE +50% that only applies to however many whole runs fit in a real 30-minute window
	 *  (derived from the average run length, not a flat permanent bonus), and the run count is always rounded
	 *  up. Returns null only while API data hasn't loaded yet (nothing to compute against). */
	public Result computeResult() {
		SkyblockStatsApi.PlayerStats s = stats();
		if (s == null) return null;

		double currentXp = s.catacombsExperience();
		int curLevel = currentLevel(currentXp);
		int target = targetLevel();
		long targetXpTotal = cumulativeXpForLevel(target);
		double xpNeeded = Math.max(0, targetXpTotal - currentXp);

		double multiplier = 1.0
			+ bonzoPercent(bonzoShardLevel())
			+ (ownsExpertRing ? 0.10 : 0)
			+ (isDerpyXpBoostActive() ? 0.50 : 0)
			+ hecatombPercent(s.maxHecatombLevel());

		double normalRunXp = floor.baseXp * multiplier;
		double earlyRunXp = floor.baseXp * (multiplier + 0.50);

		int earlyRuns = 0;
		if (includeEarlyBoost) {
			double runMinutes = runLengthMinutes();
			earlyRuns = (int) Math.floor(30.0 / runMinutes);
		}

		int runsNeeded;
		if (xpNeeded <= 0) {
			runsNeeded = 0;
		} else if (earlyRuns <= 0) {
			runsNeeded = (int) Math.ceil(xpNeeded / normalRunXp);
		} else {
			double earlyTotalXp = earlyRuns * earlyRunXp;
			if (xpNeeded <= earlyTotalXp) {
				runsNeeded = (int) Math.ceil(xpNeeded / earlyRunXp);
			} else {
				double remainder = xpNeeded - earlyTotalXp;
				runsNeeded = earlyRuns + (int) Math.ceil(remainder / normalRunXp);
			}
		}

		double progressIntoLevel = currentXp - cumulativeXpForLevel(curLevel);
		long xpForNextLevel = curLevel < MAX_LEVEL ? LEVEL_XP[curLevel] : 0;
		return new Result((multiplier - 1.0) * 100.0, xpNeeded, curLevel, progressIntoLevel, xpForNextLevel,
			runsNeeded, normalRunXp, earlyRunXp, earlyRuns);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("bonzoShardInput", bonzoShardInput);
		obj.addProperty("floor", floor.name());
		obj.addProperty("targetLevelInput", targetLevelInput);
		obj.addProperty("includeEarlyBoost", includeEarlyBoost);
		obj.addProperty("runMinutesInput", runMinutesInput);
		obj.addProperty("runSecondsInput", runSecondsInput);
		obj.addProperty("ownsExpertRing", ownsExpertRing);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("bonzoShardInput")) bonzoShardInput = obj.get("bonzoShardInput").getAsString();
		if (obj.has("floor")) { try { floor = Floor.valueOf(obj.get("floor").getAsString()); } catch (Exception ignored) {} }
		if (obj.has("targetLevelInput")) targetLevelInput = obj.get("targetLevelInput").getAsString();
		if (obj.has("includeEarlyBoost")) includeEarlyBoost = obj.get("includeEarlyBoost").getAsBoolean();
		if (obj.has("runMinutesInput")) runMinutesInput = obj.get("runMinutesInput").getAsString();
		if (obj.has("runSecondsInput")) runSecondsInput = obj.get("runSecondsInput").getAsString();
		if (obj.has("ownsExpertRing")) ownsExpertRing = obj.get("ownsExpertRing").getAsBoolean();
	}
}
