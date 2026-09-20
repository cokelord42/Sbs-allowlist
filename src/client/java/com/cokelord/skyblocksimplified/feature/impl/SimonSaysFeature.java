package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 *
 * <p>Design correction (per user request — "if the mod uses the background to detect how the simon says is
 * going, can we not just have a simon says display? Remove the SS progress announce and display, and instead
 * make a simon says display exactly like the melody but with the lanterns lighting up in order and the
 * buttons being clicked showing aswell... just show the green red and yellow highlights"): the party-chat "SS
 * N/5" announcement and the old plain-text "Simon Says: N/5" readout are both gone — replaced by a single
 * {@link MoveableWidget} grid (see {@link #render}) that mirrors the device's own real 4x4 button layout,
 * reusing the exact same {@link #firstColor}/{@link #secondColor}/{@link #thirdColor} order-coding
 * {@link #renderInner} already draws in the world, so the two views always agree. Per a same-round follow-up
 * ("make it safe for restarting aswell, so if i turn off the device and such it should hide the display and
 * when the simon says is active it should reshow... bake it into the simon says module we already have
 * instead of making a new module"): kept as part of this same class rather than split out, and its visibility
 * is driven directly off {@link #solution} — restarting the device via the start button (or a fresh Goldor
 * greeting) calls {@link #resetSolution}, which empties {@code solution} and hides the grid immediately, and
 * it reappears the instant the next lantern reveal repopulates it, with no separate "device active" flag to
 * keep in sync.
 */
public class SimonSaysFeature extends Feature implements MoveableWidget {
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
	private boolean blockWrongClicks = false;
	private boolean ssSkipCompat = true;
	private boolean progressDisplay = true;
	private boolean blockWrongStart = false;
	private int maxStartClicks = 4;

	// Ordered list of still-needed BUTTON positions (not lantern positions) — mirrors NoammAddons' `solution`.
	private final List<BlockPos> solution = new ArrayList<>();
	private boolean skipOver = false;
	// Real bug found (per user report — "the simon says progress announcer still just says 0/5 after every
	// turn is done"): the previous `sequenceLength` field was reset to 0 by the BUTTON_CHECK_POS==AIR branch
	// below on every tick that position reads as AIR — which, per this device's own real block behavior, is
	// true for the ENTIRE window the player is allowed to click (not just at a genuine full-device reset), so
	// by the time the player's last click empties `solution` the value had already been zeroed out well
	// before onBlockInteract ever read it. It also never reset BETWEEN rounds, so even when it did survive it
	// accumulated cumulatively across rounds (round 2's reveal added its 2 events on top of round 1's
	// already-counted 1, reading "3" instead of "2"). `roundLength` fixes both: it's simply snapshotted from
	// `solution.size()` every time a lantern reveal grows the list, so it always equals exactly how many
	// buttons are tracked for the CURRENT round (round 2 legitimately re-adds round 1's button before adding
	// its own new one, so solution naturally holds 2 entries, not a cumulative total) — completely decoupled
	// from the AIR-reset watcher, so it isn't wiped out during the clicking phase.
	private int roundLength = 0;
	private int stage = 0;
	private final Map<BlockPos, net.minecraft.world.level.block.Block> lastGridState = new HashMap<>();
	private int startClickCounter = 0;
	// Per user report ("theres like a simon says 5/5 counter as a gui element, but i cant actually move it"):
	// this used to be drawn directly at a hardcoded screen-center/y=4 position via a raw HudElementRegistry
	// callback, with no MoveableWidget wiring at all — every other on-screen readout in this codebase goes
	// through the drag/resize edit-screen system, this one was simply never hooked up to it.
	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.02f, 1f);
	private final HudPosition position = defaultPosition.copy();
	// Per user request ("make it automatically hide after 3 seconds of being 5/5"): timestamp the puzzle was
	// last seen fully solved (stage reaches 5, the max — see roundLength's own doc comment on why this reads
	// as a real "the whole device is done" signal, not just one round of it), 0 while not yet at 5/5 this
	// round. isVisible() below hides the widget 3 real seconds after this, independent of resetSolution()
	// (a fresh Goldor greeting / new device attempt) which still clears it back to "not shown yet" instantly.
	private long fiveOfFiveAtMillis = 0L;

	private static boolean listenersRegistered = false;
	private static SimonSaysFeature instance;

	public SimonSaysFeature() {
		super("simon_says", "Simon Says", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
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
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
					|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
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
		roundLength = 0;
		stage = 0;
		fiveOfFiveAtMillis = 0L;
		lastGridState.clear();
	}

	private void onChatMessage(String rawText) {
		String text = rawText.replaceAll("§.", "");
		if ("[BOSS] Goldor: Who dares trespass into my domain?".equals(text)) {
			resetSolution();
			startClickCounter = 0;
		}
	}

	// Per user follow-up ("I didnt even have the simon says feature on"): last round's diagnostic print
	// (comparing this method against the progress-display rebuild, finding it byte-for-byte unchanged) turned
	// out to have nothing to explain — the module was simply never enabled. Removed; no code-level regression
	// was ever found or needed fixing here.
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
				}
				solution.add(buttonPos);
				roundLength = Math.min(5, solution.size());
			}
		}

		var checkState = client.level.getBlockState(BUTTON_CHECK_POS);
		var checkBlock = checkState.getBlock();
		var previousCheck = lastGridState.get(BUTTON_CHECK_POS);
		lastGridState.put(BUTTON_CHECK_POS, checkBlock);
		if (checkBlock == Blocks.STONE_BUTTON && previousCheck != Blocks.STONE_BUTTON) {
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
			// `solution` emptying out is the real "done" signal (nothing left the player still needs to click).
			// The party-chat announcement itself is gone now (see this class' own doc comment on the grid
			// display replacing it) — `stage` is kept only to drive the grid's own 5/5 completion flourish.
			//
			// See roundLength's own field-level doc comment for the real, now-fixed root cause of "always
			// 0/5" — this reads the reveal-time snapshot instead of the AIR-reset-corrupted counter.
			if (solution.isEmpty()) {
				stage = roundLength;
				// Per user request ("Add a new notification to dungeon notifications that notifies when the
				// simon says is complete using the same detection method"): stage reaching the real max (5) at
				// this exact detection point — solution just emptied on a correct final click — IS the device
				// actually finishing, not just one round of it (see roundLength's own doc comment). Also the
				// same instant the widget's own 3-second auto-hide countdown (see fiveOfFiveAtMillis) starts.
				if (stage >= 5) {
					fiveOfFiveAtMillis = System.currentTimeMillis();
					DungeonNotificationsFeature.fireSimonSaysComplete();
				}
			}
		}
		return InteractionResult.PASS;
	}

	// ---- MoveableWidget: a live grid mirroring the device's own 4x4 lantern/button layout — see this
	// class' own doc comment for why this replaced the old plain-text "SS N/5" readout. ----

	// The real device is a 4x4 grid: y in [120,123] (4 rows) by z in [92,95] (4 columns) at fixed x=110 — see
	// GRID's own declaration order, which already lists them in this same row-major shape. Mapped to a plain
	// 0-3/0-3 (row, column) pair purely to lay the grid out on screen; the exact top/bottom or left/right
	// orientation isn't confirmed against the real room's camera-facing direction, but doesn't need to be —
	// this is a schematic ("which button is next"), not an attempt to mirror the room's literal geometry.
	private static int gridRow(BlockPos buttonPos) { return 123 - buttonPos.getY(); }
	private static int gridCol(BlockPos buttonPos) { return buttonPos.getZ() - 92; }

	private static final int GRID_CELL = 16;
	private static final int GRID_PITCH = 18;
	private static final int GRID_PANEL_MARGIN = 6;
	private static final int GRID_PANEL_RADIUS = 8;
	private static final int GRID_CELL_RADIUS = 4;
	// A dim, empty-looking cell for a button that's neither revealed-and-pending nor part of the completion
	// flourish — per user request ("It doesnt have to show the buttons, just make it show the green red and
	// yellow highlights if that makes sense"), this is deliberately inert/undecorated rather than a fourth
	// "real button" color.
	private static final int GRID_EMPTY_COLOR = 0xFF2B2B2B;
	private static final int GRID_PANEL_SIZE = GRID_PANEL_MARGIN * 2 + 3 * GRID_PITCH + GRID_CELL;

	@Override
	public String getId() { return "simon_says_progress"; }

	// Per user follow-up ("Module is still called Simon Says: Display. The module should just be called
	// Simon Says. The display should be an option inside like it is currently"): the colon-suffixed
	// "<Module>: <Sub>" convention this codebase uses elsewhere (Network Display's "Network Display: Ping",
	// etc.) still read, to the user, like the whole module had been renamed — reverted to plain "Simon Says"
	// for the widget's own edit-screen label. The settings-panel toggle is already just "Display" (unchanged
	// by this correction), which is the "option inside" being referred to.
	@Override
	public String getDisplayName() { return "Simon Says"; }

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() {
		if (!isEnabled() || !progressDisplay || DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return false;
		// Per the mid-turn follow-up ("if i turn off the device and such it should hide the display and when
		// the simon says is active it should reshow"): tied directly to `solution` rather than a separate
		// "device active" flag — resetSolution() (start button / fresh Goldor greeting) empties it and hides
		// the grid immediately, and the very next lantern reveal repopulates it and brings the grid back.
		if (!solution.isEmpty()) return true;
		// Per user request ("make it automatically hide after 3 seconds of being 5/5"): keeps the completed
		// grid on screen briefly once the whole device (not just one round of it) is solved, same grace
		// window this widget has always used, before disappearing until the next attempt.
		return fiveOfFiveAtMillis != 0L && System.currentTimeMillis() - fiveOfFiveAtMillis < 3000L;
	}

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public boolean hasVisibleContent() { return isVisible(); }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		boolean scaled = Math.abs(scale - 1f) >= 0.01f;
		if (scaled) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
		}
		int ox = scaled ? 0 : x;
		int oy = scaled ? 0 : y;

		RenderUtil.fillRounded(graphics, ox, oy, ox + GRID_PANEL_SIZE, oy + GRID_PANEL_SIZE, GRID_PANEL_RADIUS, 0xFF000000);

		// During the post-5/5 grace window `solution` is already empty (nothing left needing a click), so
		// this is the one case a plain solution.indexOf lookup can't distinguish from "device just reset" —
		// shows every cell in firstColor as a simple "all done" flourish instead of a blank grid for those 3s.
		boolean justFinished = solution.isEmpty() && fiveOfFiveAtMillis != 0L;
		for (BlockPos buttonPos : GRID) {
			int cx = ox + GRID_PANEL_MARGIN + gridCol(buttonPos) * GRID_PITCH;
			int cy = oy + GRID_PANEL_MARGIN + gridRow(buttonPos) * GRID_PITCH;
			int index = solution.indexOf(buttonPos);
			// Same order-coding renderInner() draws in the world — see this class' own doc comment on why the
			// two are deliberately kept in lockstep.
			int color = justFinished ? firstColor
				: index < 0 ? GRID_EMPTY_COLOR
				: index == 0 ? firstColor : index == 1 ? secondColor : thirdColor;
			RenderUtil.fillRounded(graphics, cx, cy, cx + GRID_CELL, cy + GRID_CELL, GRID_CELL_RADIUS, color);
			RenderUtil.fillRoundedRing(graphics, cx, cy, cx + GRID_CELL, cy + GRID_CELL, GRID_CELL_RADIUS, 1, 0x80FFFFFF);
		}

		if (scaled) graphics.pose().popMatrix();
		return new Size(Math.round(GRID_PANEL_SIZE * scale), Math.round(GRID_PANEL_SIZE * scale));
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
		obj.addProperty("blockWrongClicks", blockWrongClicks);
		obj.addProperty("ssSkipCompat", ssSkipCompat);
		obj.addProperty("progressDisplay", progressDisplay);
		obj.addProperty("blockWrongStart", blockWrongStart);
		obj.addProperty("maxStartClicks", maxStartClicks);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
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
		if (obj.has("blockWrongClicks")) blockWrongClicks = obj.get("blockWrongClicks").getAsBoolean();
		if (obj.has("ssSkipCompat")) ssSkipCompat = obj.get("ssSkipCompat").getAsBoolean();
		if (obj.has("progressDisplay")) progressDisplay = obj.get("progressDisplay").getAsBoolean();
		if (obj.has("blockWrongStart")) blockWrongStart = obj.get("blockWrongStart").getAsBoolean();
		if (obj.has("maxStartClicks")) maxStartClicks = obj.get("maxStartClicks").getAsInt();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
	}

	@Override
	public String getDescription() {
		return "Solves the F7 Simon Says device: highlights the buttons you still need to press, in the correct order.";
	}
}
