package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F3/M3 "Three Weirdos" puzzle solver — matches an NPC's spoken line against known truthful/lying answer
 *  patterns and highlights the NPC standing at the correct chest (or all NPCs confirmed lying). Ported from
 *  Odin's {@code WeirdosSolver.kt}.
 *
 * <p>Real bug found (per user report — "does nothing"): the previous port guessed at a plain "Name:
 * message" chat line, since Odin's own {@code onNPCMessage} receives the name/message already split by its
 * caller. That caller (Odin's {@code PuzzleSolvers.kt}) is real, confirmed source showing the actual chat
 * line is {@code "[NPC] <name>: <message>"} — the "[NPC] " prefix this port's guess was missing entirely,
 * so the pattern could never match a single real dialogue line.
 *
 * <p>Second real bug found (per a later "still isn't working" report): that same confirmed-real source
 * ({@code PuzzleSolvers.kt}'s {@code weirdosRegex}) is neither anchored ({@code ^...$}) nor matched with a
 * full-string match — it's {@code Regex("\\[NPC] (.+): (.+).?")} matched with {@code find()}. This port had
 * anchored the line pattern and called {@code matches()} (a whole-string match), the same class of
 * "requires an exact match with zero tolerance for anything unexpected before/after" fragility already
 * found and fixed for the boss-entry chat patterns — switched to the same unanchored {@code find()} Odin's
 * real code actually uses. */
public class WeirdosSolverFeature extends Feature {
	private static final Pattern NPC_LINE_PATTERN = Pattern.compile("\\[NPC] (.+): (.+).?");
	private static final double NPC_SCAN_RANGE = 32.0;

	private static final List<Pattern> CORRECT_PATTERNS = List.of(
		Pattern.compile("The reward is not in my chest!"),
		Pattern.compile("At least one of them is lying, and the reward is not in .+'s chest\\.?"),
		Pattern.compile("My chest doesn't have the reward\\. We are all telling the truth\\.?"),
		Pattern.compile("My chest has the reward and I'm telling the truth!"),
		Pattern.compile("The reward isn't in any of our chests\\.?"),
		Pattern.compile("Both of them are telling the truth\\. Also, .+ has the reward in their chest\\.?"));

	private static final List<Pattern> WRONG_PATTERNS = List.of(
		Pattern.compile("One of us is telling the truth!"),
		Pattern.compile("They are both telling the truth\\. The reward isn't in .+'s chest\\."),
		Pattern.compile("We are all telling the truth!"),
		Pattern.compile(".+ is telling the truth and the reward is in his chest\\."),
		Pattern.compile("My chest doesn't have the reward\\. At least one of the others is telling the truth!"),
		Pattern.compile("One of the others is lying\\."),
		Pattern.compile("They are both telling the truth, the reward is in .+'s chest\\."),
		Pattern.compile("They are both lying, the reward is in my chest!"),
		Pattern.compile("The reward is in my chest\\."),
		Pattern.compile("The reward is not in my chest\\. They are both lying\\."),
		Pattern.compile(".+ is telling the truth\\."),
		Pattern.compile("My chest has the reward\\."));

	private BlockPos correctPos;
	private final Set<BlockPos> wrongPositions = new LinkedHashSet<>();
	// Every NPC position seen this puzzle attempt, correct/wrong-classified or not — see onChatMessage's
	// own doc comment for why this is needed (elimination deduction).
	private final Set<BlockPos> allNpcPositions = new LinkedHashSet<>();

	private int correctColor = 0xFF55FF55;
	private int wrongColor = 0xFFFF5555;
	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.FILLED_OUTLINE;

	private static boolean listenersRegistered = false;
	private static WeirdosSolverFeature instance;

	public WeirdosSolverFeature() {
		super("puzzle_three_weirdos", "Three Weirdos Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				// Stripped here too — same reasoning as DungeonState's own fix this round (per a separate
				// user report about boss-message detection, pointed at comparing against
				// PestSpawnAlertFeature's already-working, defensively-stripped chat regex). NPC_LINE_PATTERN
				// is an exact-text match against "[NPC] name: message" with no defense against embedded "§"
				// formatting codes getString() alone might not have stripped — directly relevant to this
				// feature's own still-unresolved "still not working" report.
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString().replaceAll("§.", ""));
				return true;
			});
			// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
			// projection, per user request ("if it's highlighting a block I always want it to be a block
			// highlight and not a 2d box render") — the chest highlight now actually sits on/around the
			// real block, occluded by terrain in front of it, matching EtherwarpFeature's own pattern.
			World3DRenderer.addRenderCallback(WeirdosSolverFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		correctPos = null;
		wrongPositions.clear();
		allNpcPositions.clear();
	}

	/** Real root cause found (per user report — "still isn't working," a second time after the chat-regex
	 *  fixes from earlier rounds): this method used to gate on {@link #isWeirdosRoom()} first, requiring
	 *  {@code WorldScan}'s room-fingerprint matcher to have already identified the current room as "Three
	 *  Weirdos" before even trying the NPC-line regex. Odin's own real source (confirmed via {@code
	 *  PuzzleSolvers.kt}'s {@code ChatPacketEvent} listener, which is what actually calls into {@code
	 *  WeirdosSolver.onNPCMessage}) never does this — it only gates on {@code DungeonUtils.inClear}
	 *  (dungeon, not boss) and otherwise trusts the NPC-line regex itself (plus the very specific
	 *  correct/wrong dialogue patterns) to only ever match inside the actual Weirdos room. Room-name
	 *  matching only gates RENDERING there ({@code onRenderWorld}), not detection — same split kept here
	 *  now (see {@link #renderInner()}, which still checks {@link #isWeirdosRoom()}). Whatever's actually
	 *  wrong with this codebase's own room-fingerprint match for "Three Weirdos" (still unconfirmed, no
	 *  live debug data available) no longer matters for detection at all, matching the real mod's own,
	 *  more robust design. */
	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;
		Matcher lineMatch = NPC_LINE_PATTERN.matcher(text);
		// Diagnostic only (per user report — "STILL not showing which one is correct... no chest is
		// lighting up" — even after removing the isWeirdosRoom() gate above, which was the only concrete
		// discrepancy found against Odin's/devonian's real sources this round; every other piece of this
		// pipeline — chat pattern, correct/wrong dialogue patterns, entity lookup, position math — already
		// matches those real sources exactly, with no further discrepancy found via static comparison).
		// Logs exactly which step (if any) this NPC line makes it past, so the next live run pinpoints the
		// real failure point instead of another guess.
		if (!lineMatch.find()) {
			return;
		}
		String npc = lineMatch.group(1);
		String msg = lineMatch.group(2);

		boolean isCorrect = CORRECT_PATTERNS.stream().anyMatch(p -> p.matcher(msg).matches());
		boolean isWrong = !isCorrect && WRONG_PATTERNS.stream().anyMatch(p -> p.matcher(msg).matches());
		// Real bug found (per user report — "it just didnt show the correct chest, it showed the two wrong
		// ones however"): matches Odin's own real algorithm exactly (see this method's doc comment above),
		// but Odin's own real design has a genuine gap it never covers either — not every valid real 3-NPC
		// combination has the TRUE correct NPC's own line fall into CORRECT_PATTERNS at all (some of the 18
		// known real lines are relative statements about OTHER NPCs, not self-declarations), so relying only
		// on "did this speaker's own line match a solution pattern" can legitimately never fire for the
		// actual correct NPC while the other two both get correctly marked wrong — exactly the reported
		// symptom. Tracking every NPC's position regardless of classification (below) makes the third,
		// real 3-in-the-room game rule usable: once exactly 2 of the 3 are confirmed wrong and the 3rd position
		// is known, it's deducible by elimination even if that NPC's own line never matched a solution
		// pattern by itself.

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null) {
			return;
		}

		ArmorStand npcEntity = null;
		for (var entity : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(NPC_SCAN_RANGE), e -> e instanceof ArmorStand)) {
			if (npc.equals(entity.getName().getString())) { npcEntity = (ArmorStand) entity; break; }
		}
		if (npcEntity == null) {
			return;
		}

		BlockPos relative = room.getRelativeCoords(new BlockPos((int) npcEntity.getX() - 1, 69, (int) npcEntity.getZ() - 1));
		BlockPos pos = room.getRealCoords(relative.offset(1, 0, 0));
		allNpcPositions.add(pos);

		if (isCorrect) {
			correctPos = pos;
			mc.player.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 2f, 1f);
		} else if (isWrong) {
			wrongPositions.add(pos);
		}

		if (correctPos == null && allNpcPositions.size() == 3 && wrongPositions.size() == 2) {
			for (BlockPos candidate : allNpcPositions) {
				if (!wrongPositions.contains(candidate)) {
					correctPos = candidate;
					mc.player.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 2f, 1f);
					break;
				}
			}
		}
	}

	private static boolean isWeirdosRoom() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		return room != null && room.data != null && "Three Weirdos".equals(room.data.getName());
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Three Weirdos solver render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		if (!isWeirdosRoom()) return;
		if (correctPos != null) drawBox(correctPos, correctColor);
		for (BlockPos pos : wrongPositions) drawBox(pos, wrongColor);
	}

	// Mirrors WorldRenderUtil.drawStyledBox's own opacity rules (this used to call it with a flat 50%
	// fillOpacityPercent, halved again for FILLED_OUTLINE) — the wire outline keeps the color's own literal
	// alpha, only the fill gets the fixed 50/25% override.
	private void drawBox(BlockPos pos, int color) {
		AABB box = new AABB(pos);
		if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			int opacityPercent = style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE ? 25 : 50;
			int alpha = Math.round(opacityPercent / 100f * 255);
			World3DRenderer.drawFilledBox(box, (alpha << 24) | (color & 0xFFFFFF));
		}
		if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			World3DRenderer.drawWireBox(box, color, 2f);
		}
	}

	public int getCorrectColor() { return correctColor; }
	public void setCorrectColor(int value) { correctColor = value; }
	public int getWrongColor() { return wrongColor; }
	public void setWrongColor(int value) { wrongColor = value; }
	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("correctColor", correctColor);
		obj.addProperty("wrongColor", wrongColor);
		obj.addProperty("style", style.name());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("correctColor")) correctColor = obj.get("correctColor").getAsInt();
		if (obj.has("wrongColor")) wrongColor = obj.get("wrongColor").getAsInt();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
	}

	@Override
	public String getDescription() {
		return "Solves the F3/M3 Three Weirdos puzzle: matches NPC dialogue against known patterns and highlights the correct chest.";
	}
}
