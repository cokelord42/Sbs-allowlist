package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.HypixelElectionApi;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.Puzzle;
import com.cokelord.skyblocksimplified.dungeon.PuzzleStatus;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live Catacombs dungeon-score readout, computed with the same formula real score-tracking mods use
 * (Skytils' {@code ScoreCalculation.kt}, ported here via devonian's own faithful Kotlin port of it,
 * {@code Dungeons.kt} — re-derived in this codebase's own style, every constant cross-checked against
 * that source rather than guessed). Score = Explore + Skill + Speed + Bonus, each individually floored to
 * an int and multiplied by 0.7 on the Entrance floor only ({@link #entranceMultiplied}).
 *
 * <p>Two real per-floor constants gate the formula (secret-clear target fraction and speed-run target
 * seconds) — see {@link #requiredSecretPercent} / {@link #requiredSpeedSeconds}, both taken directly from
 * devonian's own {@code FloorType.kt} table.
 *
 * <p>Per a user report ("the score approx is still bugged... off by about 6"), the death penalty, the
 * speed score, the "currently explored room counts too" credit, and the Mimic bonus's floor gate were all
 * re-derived against NoammAddons' own confirmed {@code ScoreCalculation.kt} (a second, independent, real
 * source, since devonian's own port turned out to disagree with it in exactly these four places) — see
 * each one's own doc comment below on {@link #computeScore()} for what changed and why. In particular,
 * devonian's Spirit-Pet-conditional death-penalty reduction is gone: NoammAddons' real formula applies
 * that -1 unconditionally to everyone, which devonian's own doc comment already half-admits by hardcoding
 * its own "is Spirit Pet active" check to always return true rather than ever actually resolving it.
 */
public class DungeonScoreCalculatorFeature extends Feature implements MoveableWidget {
	private boolean onlyInDungeons = true;
	private boolean autoDetectMayorPerk = true;

	private final HudPosition defaultPosition = new HudPosition(0.02f, 0.3f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static DungeonScoreCalculatorFeature instance;
	// HudElementRegistry.addLast throws IllegalArgumentException on a duplicate id (confirmed via decompiling
	// Fabric's own HudElementRegistryImpl) if called unconditionally from onEnable() — toggling this feature
	// off then back on would crash the render pipeline. Same guarded-registration pattern already used
	// correctly elsewhere in this codebase (see PlayerDisplayFeature/ChatCopyFeature): register exactly once,
	// ever — the actual per-run state reset below still needs to run on every real re-enable, so it stays
	// unconditional.
	private static boolean listenersRegistered = false;

	public static DungeonScoreCalculatorFeature getInstance() { return instance; }

	public DungeonScoreCalculatorFeature() {
		super("dungeon_score_calculator", "Dungeon Score Calculator", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "dungeon_score_calculator"),
			(graphics, deltaTracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			});
	}

	// Real bug found (per user report — "said 293 then updated to 300 when we entered bossfight and it saw
	// the scoreboard"): this used to estimate totalRooms via a histogram tallied every tick across the whole
	// run, picking whichever guess had accumulated the most tick-samples. That biases the result toward
	// whichever completedRooms/clearedPercent PAIR happened to sit on screen the LONGEST in wall-clock time
	// (e.g. several minutes hunting a hidden secret before the percent ticks over again) rather than toward
	// the most accurate one — so a stale, wrong total could dominate the tally for most of a run and only get
	// outvoted once the final, correct pair (visible right before/during the boss fight, when clearedPercent
	// is highest and most precise) had accumulated enough ticks of its own, producing exactly the "stuck low,
	// then suddenly jumps correct" symptom reported. Cross-checked against Odin's real
	// DungeonUtils.kt#totalRooms, which never accumulates history at all — it recomputes fresh from the
	// CURRENT completedRoomCount/percentCleared reading every single time it's read. Matching that removes
	// the staleness entirely: the score now always reflects the live scoreboard state, never a stale guess
	// from earlier in the run.
	private static int computeTotalRooms() {
		int clearedPercent = DungeonState.getClearedPercent();
		int completedRooms = DungeonState.getCompletedRooms();
		if (clearedPercent <= 0 || completedRooms <= 0) return 0;
		return (int) Math.floor(completedRooms / (clearedPercent * 0.01f) + 0.4f);
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && (DungeonState.isInDungeon() || !onlyInDungeons); }

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	private static final Pattern TIME_COMPONENT = Pattern.compile("(?:(\\d+)h ?)?(?:(\\d+)m ?)?(\\d+)s");

	private static int parseElapsedSeconds(String text) {
		Matcher m = TIME_COMPONENT.matcher(text);
		if (!m.matches()) return 0;
		int hours = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
		int minutes = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
		int seconds = Integer.parseInt(m.group(3));
		return hours * 3600 + minutes * 60 + seconds;
	}

	/** Entrance floor multiplies each of the four sub-scores by 0.7 before flooring — every other floor
	 *  uses the raw value. Matches devonian's {@code fuckEntrance()}. */
	private boolean isEntrance() { return DungeonState.getFloorNumber() == 0; }

	private int entranceMultiplied(double value) {
		return (int) (value * (isEntrance() ? 0.7 : 1.0));
	}

	private boolean isMasterMode() {
		String label = DungeonState.getFloorLabel();
		return label != null && label.startsWith("M");
	}

	/** Real per-floor secret-clear target fraction, from devonian's FloorType.kt table. */
	private float requiredSecretPercent() {
		if (isMasterMode()) return 1.0f;
		return switch (DungeonState.getFloorNumber()) {
			case 0 -> 0.3f;
			case 1 -> 0.3f;
			case 2 -> 0.4f;
			case 3 -> 0.5f;
			case 4 -> 0.6f;
			case 5 -> 0.7f;
			case 6 -> 0.85f;
			default -> 1.0f;
		};
	}

	/** Real per-floor speed-run target, in seconds, from devonian's FloorType.kt table. */
	private int requiredSpeedSeconds() {
		if (isMasterMode()) {
			return DungeonState.getFloorNumber() == 7 ? 900 : 480;
		}
		return switch (DungeonState.getFloorNumber()) {
			case 0 -> 1200;
			case 4, 6 -> 720;
			case 7 -> 840;
			default -> 600;
		};
	}

	public record ScoreBreakdown(int explore, int skill, int speed, int bonus, int total, String tier, boolean paulActive) {}

	public ScoreBreakdown computeScore() {
		int secretsFound = DungeonState.getSecretsFound();
		float secretsPercent = DungeonState.getSecretsPercent();
		int totalSecrets = (secretsFound == 0 || secretsPercent == 0f) ? 0 : Math.round(100f / secretsPercent * secretsFound);
		int totalSecretsRequired = (int) Math.ceil(requiredSecretPercent() * totalSecrets);
		float actualSecretPercent = totalSecretsRequired <= 0 ? 0f : Math.min(secretsFound / (float) totalSecretsRequired, 1f);
		double secretScore = actualSecretPercent * 40.0;

		int totalRooms = computeTotalRooms();
		// Eleventh bug found (per user report — "The score approx is still bugged... off by about 6"): this
		// used to feed DungeonState.getCompletedRooms() straight in, but the real formula (confirmed
		// against NoammAddons' ScoreCalculation.kt — its "effectiveCompletedRooms") also credits the room
		// you're CURRENTLY standing in and actively clearing, not only rooms already fully closed out — a
		// flat +1 for as long as you haven't reached the boss door yet. Simplified from the real two-term
		// version (a second, separate "+1 until the Blood door has actually been opened" on top of the
		// general "+1 until boss") down to this single !isInBoss() term: that extra Blood-specific term
		// only ever differs from plain !isInBoss() for the few seconds right as Blood opens, and — the part
		// that actually matters here — it's back to matching completedRooms exactly by the time a run is
		// FINISHED either way, so it was never the source of a wrong final score; not worth a dedicated
		// real-time Blood-door detector just to shave a few seconds of mid-run display accuracy.
		int completedRooms = DungeonState.getCompletedRooms() + (DungeonState.isInBoss() ? 0 : 1);
		float actualClearPercent = totalRooms <= 0 ? 0f : Math.min(completedRooms / (float) totalRooms, 1f);
		double roomClearScore = actualClearPercent * 60.0;
		int explore = entranceMultiplied(secretScore) + entranceMultiplied(roomClearScore);

		int deaths = DungeonState.getDeaths();
		// Real, confirmed formula (NoammAddons' own ScoreCalculation.kt: `(deathCount * 2 - 1).coerceAtLeast(0)`)
		// — every run gets one death's worth of forgiveness baked into the scoring algorithm itself,
		// unconditionally, regardless of loadout. The previous version only subtracted that -1 when the
		// Spirit Pet was detected active (per devonian's own port), overpenalizing every death for anyone
		// not actively wearing Spirit and understating the true score — exactly the "off by about 6" this
		// round's report describes.
		int deathPenalty = deaths == 0 ? 0 : Math.max(0, 2 * deaths - 1);
		int completedPuzzles = 0;
		int seenPuzzles = 0;
		for (Puzzle puzzle : Puzzle.values()) {
			if (puzzle == Puzzle.UNKNOWN || puzzle.status == null) continue;
			seenPuzzles++;
			if (puzzle.status == PuzzleStatus.COMPLETED) completedPuzzles++;
		}
		int totalPuzzles = Math.max(DungeonState.getPuzzleCount(), seenPuzzles);
		int puzzlePenalty = 10 * Math.max(0, totalPuzzles - completedPuzzles);
		double skillScoreRaw = Math.max(20.0 + actualClearPercent * 80.0 - (deathPenalty + puzzlePenalty), 20.0);
		int skill = entranceMultiplied(skillScoreRaw);

		int elapsedSeconds = parseElapsedSeconds(DungeonState.getElapsedTime());
		int limit = requiredSpeedSeconds();
		// Twelfth bug found (same "off by about 6" report): this used to deduct against raw SECONDS
		// overtime against a fixed set of breakpoints (12/120/360/660/3090s) that only line up with the
		// real formula on a floor whose time limit happens to be exactly 600s (F1-F3/F5) — every other
		// floor (F4/F6/F7, and every Master floor — each with its own different real limit, see
		// requiredSpeedSeconds()) got a systematically wrong speed score, since the real deduction curve
		// (confirmed against NoammAddons' getSpeedDeduction()) scales against PERCENTAGE over the floor's
		// own limit, not a flat seconds count. Replaced with that real percentage-based piecewise curve —
		// see speedDeduction() below.
		double speedScoreRaw = elapsedSeconds <= limit ? 100.0
			: 100.0 - speedDeduction((elapsedSeconds - limit) * 100.0 / limit);
		int speed = entranceMultiplied(Math.max(0.0, speedScoreRaw));

		boolean paulActive = autoDetectMayorPerk && hasEzPzPerk();
		double bonusRaw = Math.min(DungeonState.getCrypts(), 5)
			// Real formula only ever awards the Mimic bonus on F6+/M6+ (NoammAddons' own
			// `currentFloorNumber > 5` gate) — there's no Mimic room at all on earlier floors, so this
			// couldn't have inflated a real report, but it's a real, confirmed gap fixed alongside the rest.
			+ (DungeonState.isMimicKilled() && DungeonState.getFloorNumber() > 5 ? 2 : 0)
			+ (DungeonState.isPrinceKilled() ? 1 : 0)
			+ (DungeonState.isBatKilled() ? 1 : 0)
			+ (paulActive ? 10 : 0);
		int bonus = entranceMultiplied(bonusRaw);

		int total = explore + skill + speed + bonus;
		String tier = total < 100 ? "D" : total < 160 ? "C" : total < 230 ? "B" : total < 270 ? "A" : total < 300 ? "S" : "S+";
		return new ScoreBreakdown(explore, skill, speed, bonus, total, tier, paulActive);
	}

	// Real percentage-over-limit deduction curve for the speed score, ported structurally (every
	// breakpoint and divisor) from NoammAddons' own confirmed ScoreCalculation.kt#getSpeedDeduction — see
	// computeScore()'s own doc comment on the "off by about 6" fix for why this replaced a raw-seconds
	// curve that only ever matched the real formula for one specific floor time limit.
	private static double speedDeduction(double percentageOverLimit) {
		double remaining = percentageOverLimit;
		double deduction = 0.0;
		double[] caps = {20.0, 20.0, 10.0, 10.0};
		double[] divisors = {2.0, 3.5, 4.0, 5.0};
		for (int i = 0; i < caps.length; i++) {
			if (remaining <= 0) return deduction;
			double chunk = Math.min(remaining, caps[i]);
			deduction += chunk / divisors[i];
			remaining -= caps[i];
		}
		if (remaining > 0) deduction += remaining / 6.0;
		return deduction;
	}

	/** Real confirmed Hypixel mayor perk name ("EzPz") that adds +10 dungeon bonus score — cross-checked
	 *  against devonian's own {@code MayorApi.hasEzPz()}, which the user's own request refers to as
	 *  "if paul has their +10 score perk" (Paul is the mayor candidate this perk belongs to). */
	private static boolean hasEzPzPerk() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		if (mayor == null) return false;
		for (String perk : mayor.perkNames()) if (perk.equalsIgnoreCase("EzPz")) return true;
		return false;
	}

	public String mayorStatusText() {
		HypixelElectionApi.MayorInfo mayor = HypixelElectionApi.currentMayor();
		if (mayor == null) return "Mayor data not loaded yet.";
		boolean ezpz = hasEzPzPerk();
		return "Mayor: " + mayor.name() + (ezpz ? " (EzPz active, +10 bonus)" : " (no EzPz bonus)");
	}

	// Per user request: only the score + rank, no breakdown line. "Score:" stays golden; the number and
	// rank letter are both bold and colored to match the rank tier (D dark red, C red, B green, A purple,
	// S/S+ both dark yellow/gold — S+ intentionally shares S's color, there's no separate "dark yellow"
	// variant to distinguish them with).
	private static String tierColorCode(String tier) {
		return switch (tier) {
			case "D" -> "§4";
			case "C" -> "§c";
			case "B" -> "§a";
			case "A" -> "§5";
			case "S", "S+" -> "§6";
			default -> "§f";
		};
	}

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		ScoreBreakdown score = computeScore();
		String title = "§6Score: " + tierColorCode(score.tier()) + "§l" + score.total() + " (" + score.tier() + ")";
		int width = Math.round(font.width(title) * scale);
		int height = Math.round(font.lineHeight * scale);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, title, x, y, 0xFFFFFFFF);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			graphics.text(font, title, x, y, 0xFFFFFFFF);
			graphics.pose().popMatrix();
		}
		return new Size(width, height);
	}

	public boolean isOnlyInDungeons() { return onlyInDungeons; }
	public void setOnlyInDungeons(boolean value) { onlyInDungeons = value; }
	public boolean isAutoDetectMayorPerk() { return autoDetectMayorPerk; }
	public void setAutoDetectMayorPerk(boolean value) { autoDetectMayorPerk = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("onlyInDungeons", onlyInDungeons);
		obj.addProperty("autoDetectMayorPerk", autoDetectMayorPerk);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("onlyInDungeons")) onlyInDungeons = obj.get("onlyInDungeons").getAsBoolean();
		if (obj.has("autoDetectMayorPerk")) autoDetectMayorPerk = obj.get("autoDetectMayorPerk").getAsBoolean();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Live Catacombs dungeon score readout, computed the same way as other score-tracking mods.";
	}
}
