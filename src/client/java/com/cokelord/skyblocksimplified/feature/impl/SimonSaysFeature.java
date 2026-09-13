package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F7 P3 "Simon Says" device solver — highlights the buttons still needing a click, in order, ranked
 * (1st/2nd/rest) by color. Real replacement (per user request — "Simon says solver, replace ours since
 * theirs has triple skip compability"), ported from NoammAddons' confirmed {@code SimonSays.kt}, the
 * cheat-screened solving half only — its {@code triggerBot}/{@code autoStart} settings (auto-clicking the
 * correct button / auto-clicking the start button for you) live behind that source's own
 * {@code //#if CHEAT} block and are deliberately NOT ported, per the user's explicit warning that this
 * source tree may contain a cheat build's code.
 *
 * <p>The real fix this replacement carries: this codebase's own previous version detected a lit lantern by
 * watching for a SEA_LANTERN-back-to-OBSIDIAN transition and had no concept of Hypixel's real "SS skip"
 * mechanic at all (perfectly clicking the first two revealed buttons quickly can make the device reveal
 * the rest of the sequence without a normal one-by-one reveal) — its only handling for that case was to
 * detect 3 clicks on the start button and freeze tracking entirely rather than actually keep solving
 * through it. NoammAddons' real, confirmed algorithm instead: detects the correct OBSIDIAN&#8594;SEA_LANTERN
 * reveal direction, and — when "SS skip Compatibility" is on (default, matching the user's ask) — assumes
 * a skip already happened once the tracked solution reaches 2 entries, dropping the first so the highlight
 * stays aligned with the real remaining sequence; a click that lands on the SECOND still-tracked button
 * while 3 are queued is treated the same way (both are consumed, not just the one clicked), covering the
 * skip landing mid-solve instead of only at the very start.
 *
 * <p>Real bug found (per user report — "Remove the SS break notification since its very broken and says SS
 * broke even when it completed"): this used to also port NoammAddons' break/restart alert — "the device has
 * broken" was inferred purely from every lantern being back to OBSIDIAN and every button back to AIR for a
 * short debounce window. That exact block state is indistinguishable from a normal, freshly-reset device
 * after a SUCCESSFUL completion, so the alert fired just as often on a clean solve as on a real failure.
 * Removed entirely, rather than trying to patch a heuristic that has no real signal to tell the two apart —
 * see NECRON_WARNING/Terminal Solver's own "no notification for this" precedent this round for the same
 * reasoning applied elsewhere.
 */
public class SimonSaysFeature extends Feature {
	private static final BlockPos START_BUTTON = new BlockPos(110, 121, 91);
	private static final BlockPos BUTTON_CHECK_POS = new BlockPos(110, 120, 93);
	private static final Set<BlockPos> GRID = Set.of(
		new BlockPos(110, 123, 92), new BlockPos(110, 123, 93), new BlockPos(110, 123, 94), new BlockPos(110, 123, 95),
		new BlockPos(110, 122, 92), new BlockPos(110, 122, 93), new BlockPos(110, 122, 94), new BlockPos(110, 122, 95),
		new BlockPos(110, 121, 92), new BlockPos(110, 121, 93), new BlockPos(110, 121, 94), new BlockPos(110, 121, 95),
		new BlockPos(110, 120, 92), new BlockPos(110, 120, 93), new BlockPos(110, 120, 94), new BlockPos(110, 120, 95)
	);
	private static final List<BlockPos> LANTERN_POSITIONS = GRID.stream().map(p -> p.offset(1, 0, 0)).toList();

	private int firstColor = 0xFF55FF55;
	private int secondColor = 0xFFFFAA00;
	private int thirdColor = 0xFFFF5555;
	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.FILLED_OUTLINE;
	private boolean announceProgress = true;
	private boolean blockWrongClicks = false;
	private boolean ssSkipCompat = true;
	private boolean progressDisplay = true;
	private boolean blockWrongStart = false;
	private int maxStartClicks = 4;

	// Ordered list of still-needed BUTTON positions (not lantern positions) — mirrors NoammAddons' `solution`.
	private final List<BlockPos> solution = new ArrayList<>();
	private boolean skipOver = false;
	private int sequenceLength = 0;
	private int stage = 0;
	private final Map<BlockPos, net.minecraft.world.level.block.Block> lastGridState = new HashMap<>();
	private int startClickCounter = 0;

	private static boolean listenersRegistered = false;
	private static SimonSaysFeature instance;

	public SimonSaysFeature() {
		super("simon_says", "Simon Says", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		resetSolution();
		startClickCounter = 0;
		if (!listenersRegistered) {
			listenersRegistered = true;

			UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
				if (instance == null || !instance.isEnabled()) return InteractionResult.PASS;
				return instance.onBlockInteract(hitResult.getBlockPos());
			});

			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});

			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance != null && instance.isEnabled()) instance.tick(client);
			});

			com.cokelord.skyblocksimplified.highlight.World3DRenderer.addRenderCallback(SimonSaysFeature::renderStatic);

			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "simon_says_progress"), (graphics, tracker) -> {
				if (instance != null && instance.isEnabled()) instance.renderProgressHud(graphics);
			});
		}
	}

	@Override
	protected void onDisable() {
		resetSolution();
	}

	private void resetSolution() {
		solution.clear();
		skipOver = false;
		sequenceLength = 0;
		stage = 0;
		lastGridState.clear();
	}

	private void onChatMessage(String rawText) {
		String text = rawText.replaceAll("§.", "");
		if ("[BOSS] Goldor: Who dares trespass into my domain?".equals(text)) {
			resetSolution();
			startClickCounter = 0;
		}
	}

	private void tick(Minecraft client) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3 || client.level == null) return;

		for (BlockPos lanternPos : LANTERN_POSITIONS) {
			var block = client.level.getBlockState(lanternPos).getBlock();
			var previous = lastGridState.get(lanternPos);
			lastGridState.put(lanternPos, block);
			if (previous == Blocks.OBSIDIAN && block == Blocks.SEA_LANTERN) {
				BlockPos buttonPos = lanternPos.offset(-1, 0, 0);
				if (ssSkipCompat && solution.size() == 2 && !skipOver) {
					solution.remove(0);
					sequenceLength--;
				}
				solution.add(buttonPos);
				sequenceLength = Math.min(5, sequenceLength + 1);
			}
		}

		var checkState = client.level.getBlockState(BUTTON_CHECK_POS);
		var checkBlock = checkState.getBlock();
		var previousCheck = lastGridState.get(BUTTON_CHECK_POS);
		lastGridState.put(BUTTON_CHECK_POS, checkBlock);
		if (checkBlock == Blocks.AIR) {
			sequenceLength = 0;
		} else if (checkBlock == Blocks.STONE_BUTTON && previousCheck != Blocks.STONE_BUTTON) {
			skipOver = true;
		}
	}

	private InteractionResult onBlockInteract(BlockPos pos) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return InteractionResult.PASS;
		Minecraft mc = Minecraft.getInstance();
		boolean shiftDown = mc.player != null && mc.player.isShiftKeyDown();
		if (pos.equals(START_BUTTON)) {
			// Ported from Odin's SimonSays.kt (blockWrongStart/maxStartClicks) — solving hasn't started yet at
			// this point (no lanterns revealed), so "wrong" here just means "clicked start too many times."
			// Real value: repeatedly mashing the start button (misclicks, impatience) keeps re-rolling the
			// device's random sequence, which can make an already-memorized/partially-solved attempt harder.
			if (blockWrongStart && startClickCounter++ >= maxStartClicks && !shiftDown) {
				return InteractionResult.FAIL;
			}
			resetSolution();
			return InteractionResult.PASS;
		}
		if (!GRID.contains(pos) || solution.isEmpty()) return InteractionResult.PASS;

		BlockPos expected = solution.get(0);

		if (!pos.equals(expected)) {
			if (blockWrongClicks && !shiftDown) return InteractionResult.FAIL;
			// Real "SS skip" landing mid-solve: the player correctly skipped the first tracked button and
			// clicked the second one directly — consume both rather than leaving a stale first entry behind.
			if (ssSkipCompat && solution.size() == 3 && pos.equals(solution.get(1))) {
				solution.remove(1);
				solution.remove(0);
			}
		} else {
			solution.remove(0);
			// Real bug found (per user report — "it just spams whenever a user clicks a button"): this used to
			// fire on EVERY correct click, not just once the tracked sequence is actually finished — announcing
			// "SS 1/5", then "SS 2/5" (or whatever `stage` was at that moment), on every single button press.
			// `solution` emptying out is the real "done" signal (nothing left the player still needs to click),
			// so the announcement now fires exactly once per solve instead of once per click.
			//
			// Real bug found (per user report — "the simon says progress announcer seems to detect when the
			// current set of buttons is over but doesnt update and therefore just says 0/5 always"): `stage`
			// used to only get set from tick()'s BUTTON_CHECK_POS block-transition watcher, which — per
			// NoammAddons' own ported design — fires AFTER the round the player just solved, not at the moment
			// `solution` itself empties. That means every announcement read whatever `stage` was left over from
			// the PREVIOUS round's (already-late) update — 0 on the very first round, always one round behind
			// after that. `sequenceLength` (how many lanterns were revealed for the round currently being
			// solved, tracked live by the same tick() loop) already holds the exact right number the instant
			// the player finishes clicking it — read straight from that instead of the stale, one-step-behind
			// `stage` field.
			if (solution.isEmpty()) {
				stage = sequenceLength;
				if (announceProgress && mc.player != null && mc.player.connection != null) {
					mc.player.connection.sendCommand("pc SS " + stage + "/5");
				}
			}
		}
		return InteractionResult.PASS;
	}

	private void renderProgressHud(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
		if (!progressDisplay || DungeonState.getF7Phase() != DungeonState.F7Phase.P3 || stage <= 0) return;
		Minecraft mc = Minecraft.getInstance();
		String text = "§7Simon Says: §a" + stage + "§7/§a5";
		int x = mc.getWindow().getGuiScaledWidth() / 2 - mc.font.width(text) / 2;
		graphics.text(mc.font, text, x, 4, 0xFFFFFFFF);
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Simon Says render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3 || solution.isEmpty()) return;
		for (int index = 0; index < solution.size(); index++) {
			BlockPos buttonPos = solution.get(index);
			int color = index == 0 ? firstColor : index == 1 ? secondColor : thirdColor;
			AABB box = new AABB(buttonPos.getX() + 1.05, buttonPos.getY() + 0.37, buttonPos.getZ() + 0.3,
				buttonPos.getX() + 0.85, buttonPos.getY() + 0.63, buttonPos.getZ() + 0.7);
			if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
				int opacityPercent = style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE ? 25 : 50;
				int alpha = Math.round(opacityPercent / 100f * 255);
				com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawFilledBox(box, (alpha << 24) | (color & 0xFFFFFF));
			}
			if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
				com.cokelord.skyblocksimplified.highlight.World3DRenderer.drawWireBox(box, color, 2f);
			}
		}
	}

	public int getFirstColor() { return firstColor; }
	public void setFirstColor(int value) { firstColor = value; }
	public int getSecondColor() { return secondColor; }
	public void setSecondColor(int value) { secondColor = value; }
	public int getThirdColor() { return thirdColor; }
	public void setThirdColor(int value) { thirdColor = value; }
	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }
	public boolean isAnnounceProgress() { return announceProgress; }
	public void setAnnounceProgress(boolean value) { announceProgress = value; }
	public boolean isBlockWrongClicks() { return blockWrongClicks; }
	public void setBlockWrongClicks(boolean value) { blockWrongClicks = value; }
	public boolean isSsSkipCompat() { return ssSkipCompat; }
	public void setSsSkipCompat(boolean value) { ssSkipCompat = value; }
	public boolean isProgressDisplay() { return progressDisplay; }
	public void setProgressDisplay(boolean value) { progressDisplay = value; }
	public boolean isBlockWrongStart() { return blockWrongStart; }
	public void setBlockWrongStart(boolean value) { blockWrongStart = value; }
	public int getMaxStartClicks() { return maxStartClicks; }
	public void setMaxStartClicks(int value) { maxStartClicks = Math.max(1, Math.min(10, value)); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("firstColor", firstColor);
		obj.addProperty("secondColor", secondColor);
		obj.addProperty("thirdColor", thirdColor);
		obj.addProperty("style", style.name());
		obj.addProperty("announceProgress", announceProgress);
		obj.addProperty("blockWrongClicks", blockWrongClicks);
		obj.addProperty("ssSkipCompat", ssSkipCompat);
		obj.addProperty("progressDisplay", progressDisplay);
		obj.addProperty("blockWrongStart", blockWrongStart);
		obj.addProperty("maxStartClicks", maxStartClicks);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("firstColor")) firstColor = obj.get("firstColor").getAsInt();
		if (obj.has("secondColor")) secondColor = obj.get("secondColor").getAsInt();
		if (obj.has("thirdColor")) thirdColor = obj.get("thirdColor").getAsInt();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("announceProgress")) announceProgress = obj.get("announceProgress").getAsBoolean();
		if (obj.has("blockWrongClicks")) blockWrongClicks = obj.get("blockWrongClicks").getAsBoolean();
		if (obj.has("ssSkipCompat")) ssSkipCompat = obj.get("ssSkipCompat").getAsBoolean();
		if (obj.has("progressDisplay")) progressDisplay = obj.get("progressDisplay").getAsBoolean();
		if (obj.has("blockWrongStart")) blockWrongStart = obj.get("blockWrongStart").getAsBoolean();
		if (obj.has("maxStartClicks")) maxStartClicks = obj.get("maxStartClicks").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Solves the F7 Simon Says device: highlights the buttons you still need to press, in the correct order.";
	}
}
