package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.Puzzle;
import com.cokelord.skyblocksimplified.dungeon.PuzzleStatus;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** F7/M7-adjacent (and other floors') "Trivia"/"Quiz" puzzle solver — matches Oruo's spoken question
 *  against a bundled answer bank, watches for the matching lettered answer line, and highlights the block
 *  under the correct answer position. Ported from Odin's {@code QuizSolver.kt}. */
public class QuizSolverFeature extends Feature {
	private static final BlockPos[] OPTION_POSITIONS = {
		new BlockPos(20, 70, 6), new BlockPos(15, 70, 9), new BlockPos(10, 70, 6)
	};
	private static final char[] OPTION_LETTERS = {'ⓐ', 'ⓑ', 'ⓒ'};

	private final Map<String, List<String>> answers = DungeonJsonAssets.load(
		"/assets/skyblocksimplified/dungeon/puzzles/quizAnswers.json",
		new TypeToken<Map<String, List<String>>>() {}.getType(), new HashMap<>());

	private BlockPos[] optionPositions = new BlockPos[3];
	private final boolean[] optionCorrect = new boolean[3];
	private List<String> triviaAnswers;

	private int highlightColor = 0xFF55FF55;
	private boolean depthCheck = true;

	private static boolean listenersRegistered = false;
	private static QuizSolverFeature instance;

	public QuizSolverFeature() {
		super("puzzle_quiz", "Quiz Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			WorldScan.addRoomEnterListener(room -> { if (instance != null && instance.isEnabled()) instance.onRoomEnter(room); });
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance == null || !instance.isEnabled()) return true;
				return instance.onChatMessage(message.getString());
			});
			// Beacon beam stays screen-space (HudElementRegistry/WorldRenderUtil) — it's a supplementary
			// visual effect, not literally "the block", same reasoning as BeamsSolverFeature's tracer line.
			// The box highlight itself moves to World3DRenderer (real depth-tested 3D) per user request
			// ("if it's highlighting a block I always want it to be a block highlight and not a 2d box
			// render") — matches Odin's own real QuizSolver.kt, which already supports depth=true.
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "puzzle_quiz"), QuizSolverFeature::renderStatic);
			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(QuizSolverFeature::renderBoxes);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		optionCorrect[0] = optionCorrect[1] = optionCorrect[2] = false;
		triviaAnswers = null;
	}

	private void onRoomEnter(DungeonRoom room) {
		if (room == null || room.data == null || !"Quiz".equals(room.data.getName())) return;
		for (int i = 0; i < 3; i++) optionPositions[i] = room.getRealCoords(OPTION_POSITIONS[i]);
	}

	// §-code stripping (Oruo's option lines carry real color codes around the circled letter/answer text)
	// and case-folded letter comparison were both already confirmed correct against a real captured example
	// ("ⓐ 12 Fairy Souls" / "ⓑ 16 Fairy Souls" / "ⓒ 8 Fairy Souls", matched=true for the right one) via the
	// owner-only diagnostic logging added last round — the actual matching logic was never the problem.
	//
	// Real bug found (per user's actual complaint once matching was confirmed working — "it should highlight
	// the correct answer in chat and show that option and hide the other nametags... it hides all the
	// nametags of the options meaning it didn't leave the right one shown"): this feature never touched chat
	// rendering OR entity nametags at all — its only visual output was a beacon beam + block outline under a
	// fixed BlockPos computed from the room's clay/rotation data (see onRoomEnter), which is a completely
	// separate, unverified pipeline from the chat-based matching above. Two real, independent gaps:
	//   1. Nothing here ever recolored the correct chat line or hid the wrong ones — onChatMessage only ever
	//      read chat, never cancelled/modified it.
	//   2. On Hypixel, each of the three quiz options is a real NPC entity whose own name IS the option text
	//      ("ⓐ 12 Fairy Souls" etc., the exact same text as the chat line) — this mod never looked at those
	//      entities at all. If the user's own "Hide Nametags" Performance toggle is on (a blanket per-tick
	//      setCustomNameVisible(false) sweep over every entity, see HideNametagsFeature), it hides these three
	//      NPCs' nametags indiscriminately with no exemption for Quiz Solver, which is exactly "it hides all
	//      the nametags of the options" — nothing was ever re-showing/recoloring the correct one to override
	//      that. Fixed by matching entities directly by their own nametag's leading circled letter (identical
	//      comparison to the chat-line one below, no dependency on the room-coordinate pipeline at all — see
	//      onTick/computeOverrideName/isManagingNameVisibility) and by cancelling+recoloring the matched chat
	//      line here.
	private static final java.util.regex.Pattern COLOR_CODE = java.util.regex.Pattern.compile("§.");

	/** @return false to cancel/hide this chat line (already reprinted a colorized version), true to let it
	 *  render normally. */
	private boolean onChatMessage(String text) {
		String clean = COLOR_CODE.matcher(text).replaceAll("");
		if (clean.startsWith("[STATUE] Oruo the Omniscient: ") && clean.endsWith("correctly!")) {
			if (clean.contains("answered the final question")) {
				Puzzle.QUIZ.status = PuzzleStatus.COMPLETED;
				reset();
				return true;
			}
			if (clean.contains("answered Question #")) {
				optionCorrect[0] = optionCorrect[1] = optionCorrect[2] = false;
			}
		}

		String trimmed = clean.trim();
		int letterIndex = trimmed.isEmpty() ? -1 : indexOfLetter(trimmed.charAt(0));
		if (letterIndex != -1 && triviaAnswers != null) {
			boolean isCorrect = false;
			for (String answer : triviaAnswers) {
				if (trimmed.endsWith(answer)) { isCorrect = true; break; }
			}
			if (isCorrect) {
				optionCorrect[letterIndex] = true;
				// Real bug found (per user report — "the correct answer is like 5 characters before the
				// matching lines above and below"): reprinting `trimmed` dropped Hypixel's own leading
				// padding spaces that right-align the option text against the other two lines, so the
				// recolored line rendered shifted left of its still-untouched siblings. Reprint `clean`
				// (color codes stripped only, original spacing kept intact) instead.
				Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
					Component.literal(clean).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			// Real bug found (per user report — "not hiding the wrong answers"): the two incorrect
			// option lines were never cancelled at all, so all three stayed visible in chat with only
			// the correct one recolored. Cancel every recognized option line here — the wrong ones
			// disappear entirely, the correct one is reprinted above in its recolored form.
			return false;
		}

		if (trimmed.equals("What SkyBlock year is it?")) {
			int year = (int) (((System.currentTimeMillis() / 1000) - 1560276000L) / 446400L) + 1;
			triviaAnswers = List.of("Year " + year);
			return true;
		}
		for (var entry : answers.entrySet()) {
			if (clean.contains(entry.getKey())) {
				triviaAnswers = entry.getValue();
				return true;
			}
		}
		return true;
	}

	/** Matches an entity's own nametag against the same circled-letter format the chat lines use — the real
	 *  Hypixel NPCs standing at each option ARE named this way, so no room-coordinate lookup is needed to
	 *  find them. Returns -1 if this entity isn't one of the three quiz-option NPCs (or the puzzle isn't
	 *  currently active). */
	private int optionIndexOf(Entity entity) {
		if (triviaAnswers == null || !entity.hasCustomName()) return -1;
		String stripped = COLOR_CODE.matcher(entity.getName().getString()).replaceAll("").trim();
		return stripped.isEmpty() ? -1 : indexOfLetter(stripped.charAt(0));
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.level == null || triviaAnswers == null) return;
		for (Entity entity : client.level.entitiesForRendering()) {
			int idx = optionIndexOf(entity);
			if (idx != -1) entity.setCustomNameVisible(optionCorrect[idx]);
		}
	}

	/** Lets {@link com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature}'s own blanket per-tick
	 *  hide sweep skip these three NPCs, the same exemption pattern it already grants
	 *  {@code InactiveWaypointsFeature} — otherwise that feature's unconditional
	 *  {@code setCustomNameVisible(false)} would fight this class's own onTick every frame and the correct
	 *  answer could never actually stay visible while "Hide Nametags" is on. */
	public static boolean isManagingNameVisibility(Entity entity) {
		return instance != null && instance.isEnabled() && instance.optionIndexOf(entity) != -1;
	}

	/** Per-frame nametag text override (same contract/wiring as {@code DamageTruncatorFeature}'s own, via
	 *  {@code EntityRendererMixin}): recolors the correct answer's real overhead nametag red+bold so it's
	 *  visually distinct from the two (hidden) wrong ones, without altering its actual text. */
	public static Component computeOverrideName(Entity entity) {
		QuizSolverFeature f = instance;
		if (f == null || !f.isEnabled()) return null;
		int idx = f.optionIndexOf(entity);
		if (idx == -1 || !f.optionCorrect[idx]) return null;
		String stripped = COLOR_CODE.matcher(entity.getName().getString()).replaceAll("").trim();
		return Component.literal(stripped).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
	}

	// Real bug found (per task tracker #581 + Odin's own real QuizSolver.kt, checked against user-provided
	// source): Odin's equivalent letter check explicitly passes ignoreCase = true, unlike this one's original
	// exact-codepoint comparison — Unicode's circled Latin letters have a real upper/lowercase pair (ⓐ
	// U+24D0 vs Ⓐ U+24B6, etc.), so if Hypixel ever sends the capital-circled form the exact match here would
	// silently never fire at all. Case-folds both sides the same way before comparing.
	private static int indexOfLetter(char c) {
		char folded = Character.toLowerCase(c);
		for (int i = 0; i < OPTION_LETTERS.length; i++) if (Character.toLowerCase(OPTION_LETTERS[i]) == folded) return i;
		return -1;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Quiz solver render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		if (triviaAnswers == null) return;
		for (int i = 0; i < 3; i++) {
			if (!optionCorrect[i] || optionPositions[i] == null) continue;
			BlockPos below = optionPositions[i].offset(0, -1, 0);
			WorldRenderUtil.drawBeacon(graphics, net.minecraft.world.phys.Vec3.atCenterOf(below), highlightColor, 40);
		}
	}

	private static void renderBoxes() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderBoxesInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Quiz solver box render failed, skipping this frame", e);
		}
	}

	private void renderBoxesInner() {
		if (triviaAnswers == null) return;
		for (int i = 0; i < 3; i++) {
			if (!optionCorrect[i] || optionPositions[i] == null) continue;
			BlockPos below = optionPositions[i].offset(0, -1, 0);
			int alpha = Math.round(50 / 100f * 255);
			int argb = (alpha << 24) | (highlightColor & 0xFFFFFF);
			// Real bug found (per Odin's own QuizSolver.kt, which passes its equivalent "depth" toggle
			// straight into drawFilledBox): this always called the depth-tested overload regardless of
			// depthCheck's actual value, silently making the "Depth Check" setting a dead no-op — flipping it
			// off never had any effect.
			AABB box = new AABB(below);
			if (depthCheck) {
				com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBox(box, argb);
			} else {
				com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBoxThroughWalls(box, argb);
			}
		}
	}

	public int getHighlightColor() { return highlightColor; }
	public void setHighlightColor(int value) { highlightColor = value; }
	public boolean isDepthCheck() { return depthCheck; }
	public void setDepthCheck(boolean value) { depthCheck = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("highlightColor", highlightColor);
		obj.addProperty("depthCheck", depthCheck);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("highlightColor")) highlightColor = obj.get("highlightColor").getAsInt();
		if (obj.has("depthCheck")) depthCheck = obj.get("depthCheck").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Solves dungeon trivia/quiz puzzles: matches the spoken question against a known answer bank and highlights the correct answer.";
	}
}
