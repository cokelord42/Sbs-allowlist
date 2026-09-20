package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** F7 P3 "Arrow Align" device solver — matches the current 5x5 item-frame rotation grid against a table
 *  of known valid end-states and shows how many more clicks each frame needs. Ported from Odin's
 *  {@code ArrowAlign.kt}. Odin also tracks each frame's rotation increment for ~1s after a click to avoid
 *  a stale read before the server echoes it back; this port keeps that same debounce via a per-frame
 *  timestamp map. */
public class ArrowAlignFeature extends Feature {
	private static final BlockPos FRAME_GRID_CORNER = new BlockPos(-2, 120, 75);
	private static final BlockPos CENTER_BLOCK = new BlockPos(0, 120, 77);

	private static final int[][] POSSIBLE_SOLUTIONS = {
		{7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1},
		{-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1},
		{7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3},
		{5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1},
		{5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
		{7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
		{-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1},
		{-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1},
		{-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1},
	};

	private final Map<Integer, Long> recentClickTimestamps = new HashMap<>();
	private final Map<Integer, Integer> clicksRemaining = new HashMap<>();
	private int[] currentFrameRotations = null;
	private int[] targetSolution = null;

	private static boolean listenersRegistered = false;
	private static ArrowAlignFeature instance;

	// Per user request (add a subtoggle for the "Predevice" block described below, with a hover explanation
	// of what it does): default true, preserving the previously-unconditional behavior for anyone who
	// already relies on it.
	private boolean predevSupport = true;

	public ArrowAlignFeature() {
		super("arrow_align", "Arrow Align", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	public boolean isPredevSupport() { return predevSupport; }
	public void setPredevSupport(boolean value) { predevSupport = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("predevSupport", predevSupport);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("predevSupport")) predevSupport = obj.get("predevSupport").getAsBoolean();
	}

	/** Per user follow-up ("The levers device should not end the guide... Same with the align device, the
	 *  same way the clicks are detected in the align device should be detected in predev too. It should
	 *  detect that all arrows are 0 (except 1 of them being 1) before moving on to the next step"): a direct,
	 *  phase-independent read of the live 25-frame grid — true once exactly 24 frames read rotation 0 and
	 *  exactly one reads rotation 1, the real Hypixel solved end-state, regardless of which of the
	 *  {@link #POSSIBLE_SOLUTIONS} candidates (if any) that matches.
	 *
	 *  <p>Replaces the previous {@code targetSolution != null && clicksRemaining.isEmpty()} check, which only
	 *  ever updated inside {@link #tick()} — itself gated on {@code DungeonState.getF7Phase() == P3}, real
	 *  Hypixel scoreboard state a predev/test server doesn't reliably set the same way, which meant Boss
	 *  Guide's Align PD Mode silently never fired there at all. This reads the frames straight off the world
	 *  every call, with no phase dependency, so it works anywhere the actual item-frame grid exists. */
	public static boolean isDeviceComplete() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		if (mc.player.blockPosition().distSqr(CENTER_BLOCK) > 200) return false;
		int zeros = 0, ones = 0;
		for (var entity : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(32), e -> e instanceof ItemFrame)) {
			ItemFrame frame = (ItemFrame) entity;
			if (frame.getItem().getItem() != Items.ARROW) continue;
			int rotation = frame.getRotation();
			if (rotation == 0) zeros++;
			else if (rotation == 1) ones++;
		}
		if (zeros == 24 && ones == 1) return true;
		// Real bug found (per user report — "The align pd mode doesnt seem to work, and i think its because
		// it doesnt account for the single unsolved arrow the mod leaves with the solver"): onEntityInteract's
		// own predeviceBlock (see its doc comment) deliberately withholds the very last click on one frame
		// before the real terminals phase starts, specifically so a Healer can insta-finish the device the
		// moment terminals begin instead of needing to unalign/realign a frame that finished too early. That
		// means the raw "zeros==24 && ones==1" solved shape above is NEVER reached while predeviceBlock is
		// holding a click back — even though the device is, in every sense Boss Guide cares about, done. PD
		// Mode now also accepts "the solver's own live state says every frame is aligned except exactly one,
		// which itself needs exactly one more click" as complete, since that's precisely predeviceBlock's own
		// "ready to insta-finish" condition.
		return instance != null && instance.targetSolution != null && instance.clicksRemaining.size() == 1
			&& instance.clicksRemaining.values().iterator().next() == 1;
	}

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			// Real bug found (per user report — "The arrow align device doesn't block clicks until I open the
			// mod menu and check if its on and then it does for some reason"): onEntityInteract used to run
			// completely unguarded inside this callback. UseEntityCallback.EVENT is invoked directly from
			// vanilla's own click-processing path (MultiPlayerGameMode's interact handling), with no exception
			// isolation of its own — an uncaught exception here (e.g. a stale/resized currentFrameRotations
			// array read mid-tick, right as a phase/reset transition is happening) propagates straight out of
			// that vanilla call, which can leave Minecraft's own click/interact-cooldown bookkeeping stuck
			// mid-update for the rest of that click cycle. Every click on the frame grid PASSes through
			// unblocked from then on (blockWrongClicks's own FAIL never gets a chance to matter) until
			// something else resets that input state — opening a Screen is exactly such a reset (Minecraft
			// releases/regrabs mouse input on every Screen transition), which is why merely opening the mod
			// menu was enough to make blocking start working again. Wrapped so a failure here can never escape
			// into vanilla's own interaction pipeline in the first place.
			UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
				if (instance == null || !instance.isEnabled()) return InteractionResult.PASS;
				try {
					return instance.onEntityInteract(entity);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Arrow Align interact handling failed, passing the click through", e);
					return InteractionResult.PASS;
				}
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance != null && instance.isEnabled()) instance.tick(client);
			});
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "arrow_align"), ArrowAlignFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		clicksRemaining.clear();
		recentClickTimestamps.clear();
		currentFrameRotations = null;
		targetSolution = null;
	}

	// Real bug found (per user report — "Arrow align solver doesnt work in predev... either that or its
	// fully broken overall"): tick(), onEntityInteract() and renderInner() all used to require
	// DungeonState.getF7Phase() == P3 before doing anything at all. computeF7Phase() short-circuits to
	// UNKNOWN unless both isFloor(7) AND inBoss are true, and inBoss itself is gated on
	// bossEntryMessageSeen — a real Hypixel boss-entry chat line a predev/test server never sends. So on
	// predev the phase never becomes P3, and the ENTIRE solver (frame reading, click-count rendering, and
	// wrong-click blocking) silently no-opped — not "broken overall", broken specifically wherever F7Phase
	// can't reach P3. isDeviceComplete() above already worked around this exact problem for Boss Guide's
	// Align PD Mode by reading the live frame grid directly with no phase dependency; that fix was never
	// carried over to this class's own tick/interact/render gates. Replaced the phase check with the same
	// proximity-to-device check tick() already used as its secondary guard (CENTER_BLOCK is unique to the
	// align room in both live F7 and its predev replica) plus, in onEntityInteract(), the frame's own
	// (already very narrowly-scoped) grid-column position check — no F7Phase dependency left anywhere in
	// the solver, so it now works identically on live Hypixel and on predev.
	private void tick(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		clicksRemaining.clear();
		if (client.player.blockPosition().distSqr(CENTER_BLOCK) > 200) {
			currentFrameRotations = null;
			targetSolution = null;
			return;
		}

		currentFrameRotations = readFrames(client);

		for (int[] candidate : POSSIBLE_SOLUTIONS) {
			boolean incompatible = false;
			for (int i = 0; i < candidate.length; i++) {
				boolean eitherWild = candidate[i] == -1 || currentFrameRotations[i] == -1;
				if (eitherWild && candidate[i] != currentFrameRotations[i]) { incompatible = true; break; }
			}
			if (incompatible) continue;

			targetSolution = candidate;
			for (int i = 0; i < candidate.length; i++) {
				int needed = clicksNeeded(currentFrameRotations[i], candidate[i]);
				if (needed != 0) clicksRemaining.put(i, needed);
			}
			break;
		}
	}

	private int[] readFrames(Minecraft client) {
		List<ItemFrame> frames = new java.util.ArrayList<>();
		for (var entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(32), e -> e instanceof ItemFrame)) {
			ItemFrame frame = (ItemFrame) entity;
			if (frame.getItem().getItem() == Items.ARROW) frames.add(frame);
		}
		int[] result = new int[25];
		for (int index = 0; index < 25; index++) {
			Long clickedAt = recentClickTimestamps.get(index);
			if (clickedAt != null && System.currentTimeMillis() - clickedAt < 1000 && currentFrameRotations != null) {
				result[index] = currentFrameRotations[index];
				continue;
			}
			BlockPos pos = framePositionFromIndex(index);
			int rotation = -1;
			for (ItemFrame frame : frames) {
				if (frame.blockPosition().equals(pos)) { rotation = frame.getRotation(); break; }
			}
			result[index] = rotation;
		}
		return result;
	}

	private static BlockPos framePositionFromIndex(int index) {
		return FRAME_GRID_CORNER.offset(0, index % 5, index / 5);
	}

	private static int clicksNeeded(int current, int target) {
		return (8 - current + target) % 8;
	}

	/** Same "match the local player's name against the live teammate list" pattern DungeonsCopilotFeature's
	 *  own selfClass() already uses — null if the player's class hasn't resolved yet (e.g. very early in a
	 *  run, or no real party in singleplayer). */
	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) return teammate.clazz;
		}
		return null;
	}

	private InteractionResult onEntityInteract(net.minecraft.world.entity.Entity entity) {
		// See tick()'s own doc comment above: no F7Phase check here anymore (predev never reaches P3) — the
		// exact-column/index checks right below are already a tight enough filter to only the real align
		// device's own frames.
		if (!(entity instanceof ItemFrame frame) || frame.getItem().getItem() != Items.ARROW) return InteractionResult.PASS;
		BlockPos pos = frame.blockPosition();
		if (pos.getX() != FRAME_GRID_CORNER.getX()) return InteractionResult.PASS;

		int frameIndex = (pos.getY() - FRAME_GRID_CORNER.getY()) + (pos.getZ() - FRAME_GRID_CORNER.getZ()) * 5;
		if (frameIndex < 0 || frameIndex > 24) return InteractionResult.PASS;
		if (currentFrameRotations != null && currentFrameRotations[frameIndex] == -1) return InteractionResult.PASS;

		boolean alreadyAligned = !clicksRemaining.containsKey(frameIndex);

		// Real bug found (per user report — "The arrow align still isnt blocking clicks for some reason...
		// only when I actually go in the mod menu and check if its on that it seems to do it"): blocking used
		// to be gated behind a separate "Block Wrong Clicks" subtoggle (defaulted OFF) plus a shift-key
		// modifier, so opening the mod menu never actually mattered — the toggle was just never on. Per the
		// user's exact new spec ("I want it to always block clicks... tie the code to that, so when the number
		// is 0 it shouldn't be able to be clicked anymore"), blocking is now unconditional on an already-
		// aligned frame — no subtoggle, no shift modifier.
		//
		// Exception (Healer's Predevice workflow, per the same report): before the real terminals phase
		// starts, completing the device outright is ALSO blocked — this forces exactly one arrow to be left
		// at 1 click remaining instead of 0, so the Healer can insta-complete it the moment terminals actually
		// start rather than needing to spin/unalign and realign a frame that finished too early. Once
		// terminals have started, this exception no longer applies — only the plain "at 0" block above does.
		boolean terminalsStarted = DungeonState.getTerminalSection() != DungeonState.TerminalSection.NONE;
		boolean wouldCompleteDevice = !alreadyAligned && clicksRemaining.size() == 1 && clicksRemaining.get(frameIndex) == 1;
		boolean predeviceBlock = predevSupport && !terminalsStarted && wouldCompleteDevice && selfClass() == DungeonClass.HEALER;

		if (alreadyAligned || predeviceBlock) {
			return InteractionResult.FAIL;
		}

		recentClickTimestamps.put(frameIndex, System.currentTimeMillis());
		// Real bug found (per user report — "if i spam the last one during predev it thinks it solved it
		// entirely... doesn't automatically leave 1 because i spammed it before it could cancel"): this used
		// to only ever REMOVE a frame's entry from clicksRemaining once it reached exactly 0 clicks needed —
		// a click that merely reduced a frame from, say, 2 needed to 1 needed left the STALE "2" sitting in
		// the map, since only tick() (once per CLIENT TICK) ever recomputed the real per-frame count.
		// predeviceBlock's own "would this click finish the device" check reads clicksRemaining.get(index)
		// == 1 directly — two real clicks landing within the same tick (trivial with a fast clicker) could
		// both see that same stale "2" and both get ALLOWED through, since neither ever looked like "the
		// last click" to the check, even though the second one actually was. Now updates the stored count on
		// every click, not just the completing one, so the very next click (even the same tick) sees the
		// correct number immediately.
		if (currentFrameRotations != null && targetSolution != null) {
			currentFrameRotations[frameIndex] = (currentFrameRotations[frameIndex] + 1) % 8;
			int needed = clicksNeeded(currentFrameRotations[frameIndex], targetSolution[frameIndex]);
			if (needed == 0) clicksRemaining.remove(frameIndex);
			else clicksRemaining.put(frameIndex, needed);
		}
		return InteractionResult.PASS;
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Arrow Align render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		// No F7Phase check here either (see tick()'s doc comment) — clicksRemaining is only ever populated
		// by tick() while near the device, so an empty map already means "nothing to draw".
		if (clicksRemaining.isEmpty()) return;
		for (var entry : clicksRemaining.entrySet()) {
			int needed = entry.getValue();
			if (needed == 0) continue;
			String colorCode = needed < 3 ? "§a" : needed < 5 ? "§6" : "§c";
			Vec3 pos = new Vec3(framePositionFromIndex(entry.getKey())).add(0.5 - 0.3, 0.5 + 0.1, 0.5);
			WorldRenderUtil.drawText(graphics, colorCode + needed, pos, 0xFFFFFFFF);
		}
	}


	@Override
	public String getDescription() {
		return "Solves the F7 Arrow Align device: matches the current rotation grid against known solutions and shows how many more clicks each frame still needs.";
	}
}
