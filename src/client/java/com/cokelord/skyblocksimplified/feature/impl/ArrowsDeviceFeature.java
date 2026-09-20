package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F7 P3 "Sharp Shooter" device solver — highlights marked/target blocks in the 3x3x3 emerald/clay grid.
 *  Ported from Odin's {@code ArrowsDevice.kt}. The optional "show optimal aim positions" sub-feature
 *  (computing where to stand to hit multiple marked blocks in one shot) is dropped — real gameplay value
 *  (marked/target highlighting + completion alert) is kept, that one's a secondary optimization on top.
 *
 *  <p>Real addition (per user request — "Add i4 helper, but make it a part of the 'Arrow device' module in
 *  ours"): NoammAddons' confirmed {@code I4Helper.kt} targets the exact same real device (identical
 *  {@code devBlocks} coordinates as this class's own {@code DEVICE_POSITIONS}, confirming it's the same
 *  "i4" device under a different name) and adds one real feature this class didn't have — a "Show
 *  Prediction" heuristic that guesses which still-unmarked block is most likely the next real target
 *  (candidate blue-terracotta blocks in the same row exactly 2 blocks apart are favored, since Hypixel's
 *  own target selection tends toward that pattern) and highlights it in a separate color. It's a
 *  best-effort suggestion, not a guaranteed solve, matching NoammAddons' own framing of it — nothing here
 *  reads hidden server state or acts automatically. {@code AutoI4.kt} (a separate file in the same source
 *  tree that auto-shoots the target for you) was NOT ported — see this class's own repo-wide cheat-
 *  screening note on {@code I4Helper.kt} vs {@code AutoI4.kt}. */
public class ArrowsDeviceFeature extends Feature {
	private static final Pattern DEVICE_COMPLETE_PATTERN = Pattern.compile("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$");
	private static final AABB ROOM_BOUNDS = new AABB(20.0, 100.0, 30.0, 89.0, 151.0, 51.0);
	private static final List<BlockPos> DEVICE_POSITIONS = List.of(
		new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
		new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
		new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50)
	);

	private int markedColor = 0x8000AAAA;
	private int targetColor = 0x80FF55FF;
	private boolean alertOnComplete = true;
	// Per user request ("Allow users to change the opacity and color of the emerald blocks on the arrows
	// device"): the still-emerald grid positions (not yet marked/targeted — i.e. lastBlocks[i] still reads
	// EMERALD_BLOCK) previously had no highlight of their own at all, only markedPositions/targetPosition
	// ever got a box. showEmerald defaults off since a fully-highlighted 3x3x3 grid is a lot of extra
	// boxes most users won't want on by default; marked/target still always render regardless.
	private boolean showEmerald = false;
	// Per user request ("Remove the 'Emerald color' from the arrows device. The target is the emerald
	// block."): no longer user-configurable — always the real emerald-block green, opacity stays adjustable.
	private static final int EMERALD_COLOR = 0x55FF55;
	// Per user request ("Make the arrows device block opacity max. It should fully highlight."): default
	// bumped from 30 to the max of the 0-100 range this already clamps to (see setEmeraldOpacity) — still a
	// real, adjustable slider, just starting fully opaque instead of mostly transparent.
	private int emeraldOpacity = 100;

	private static final int MAX_PREDICTION_ATTEMPTS = 2;
	private boolean showPrediction = true;
	private int predictionColor = 0x80FFFF55;

	private final Set<BlockPos> markedPositions = new HashSet<>();
	private BlockPos targetPosition;
	private BlockPos prediction;
	private final Map<BlockPos, Integer> predictionAttempts = new HashMap<>();
	private boolean deviceComplete = false;
	private net.minecraft.world.level.block.Block[] lastBlocks = new net.minecraft.world.level.block.Block[DEVICE_POSITIONS.size()];

	private static boolean listenersRegistered = false;
	private static ArrowsDeviceFeature instance;

	public ArrowsDeviceFeature() {
		super("arrows_device", "Arrows Device", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance != null && instance.isEnabled()) instance.tick(client);
			});
			// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
			// projection, per user request ("if it's highlighting a block I always want it to be a block
			// highlight and not a 2d box render").
			World3DRenderer.addRenderCallback(ArrowsDeviceFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		markedPositions.clear();
		targetPosition = null;
		prediction = null;
		predictionAttempts.clear();
		deviceComplete = false;
		lastBlocks = new net.minecraft.world.level.block.Block[DEVICE_POSITIONS.size()];
	}

	private void tick(Minecraft client) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3 || client.level == null) return;

		for (int i = 0; i < DEVICE_POSITIONS.size(); i++) {
			BlockPos pos = DEVICE_POSITIONS.get(i);
			var block = client.level.getBlockState(pos).getBlock();
			var previous = lastBlocks[i];
			lastBlocks[i] = block;
			if (previous == null || previous == block) continue;

			if (previous == Blocks.EMERALD_BLOCK && isBlueTerracotta(block)) {
				markedPositions.add(pos);
				if (pos.equals(targetPosition)) targetPosition = null;
			} else if (isBlueTerracotta(previous) && block == Blocks.EMERALD_BLOCK) {
				markedPositions.remove(pos);
				targetPosition = pos;
				prediction = showPrediction ? computePrediction(pos) : null;
			}
		}
	}

	/** Best-effort guess at which still-unmarked blue-terracotta block is most likely the next real target
	 *  — ported from NoammAddons' confirmed {@code I4Helper.kt}: candidates sitting in the same row exactly
	 *  2 blocks apart (x-difference == 2) are favored as a pair, since Hypixel's real target selection tends
	 *  toward that pattern; a candidate already guessed {@link #MAX_PREDICTION_ATTEMPTS} times without
	 *  turning out correct is deprioritized in favor of ones not yet tried. Not a guaranteed solve. */
	private BlockPos computePrediction(BlockPos lastHitPos) {
		List<BlockPos> allValid = new ArrayList<>();
		for (int i = 0; i < DEVICE_POSITIONS.size(); i++) {
			BlockPos pos = DEVICE_POSITIONS.get(i);
			if (markedPositions.contains(pos) || pos.equals(lastHitPos)) continue;
			if (isBlueTerracotta(lastBlocks[i])) allValid.add(pos);
		}
		if (allValid.isEmpty()) return null;

		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos pos : allValid) if (predictionAttempts.getOrDefault(pos, 0) < MAX_PREDICTION_ATTEMPTS) candidates.add(pos);
		if (candidates.isEmpty()) candidates = allValid;

		Map<Integer, List<BlockPos>> byY = new HashMap<>();
		for (BlockPos pos : candidates) byY.computeIfAbsent(pos.getY(), k -> new ArrayList<>()).add(pos);
		List<BlockPos[]> pairs = new ArrayList<>();
		for (List<BlockPos> row : byY.values()) {
			row.sort(Comparator.comparingInt(BlockPos::getX));
			for (int i = 0; i < row.size() - 1; i++) {
				if (row.get(i + 1).getX() - row.get(i).getX() == 2) pairs.add(new BlockPos[]{row.get(i), row.get(i + 1)});
			}
		}

		ThreadLocalRandom rng = ThreadLocalRandom.current();
		BlockPos chosen = !pairs.isEmpty() ? pairs.get(rng.nextInt(pairs.size()))[rng.nextInt(2)] : candidates.get(rng.nextInt(candidates.size()));
		predictionAttempts.merge(chosen, 1, Integer::sum);
		return chosen;
	}

	// No compile-time Blocks.BLUE_TERRACOTTA constant exists for every color variant in this MC version
	// (same workaround as DungeonRoom.isBlueTerracotta).
	private static boolean isBlueTerracotta(net.minecraft.world.level.block.Block block) {
		return block != null && "minecraft:blue_terracotta".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	private void onChatMessage(String text) {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3 || !isPlayerInRoom() || deviceComplete) return;
		Matcher matcher = DEVICE_COMPLETE_PATTERN.matcher(text);
		Minecraft mc = Minecraft.getInstance();
		if (matcher.matches() && mc.player != null && matcher.group(1).equals(mc.player.getName().getString())) {
			onComplete();
		}
	}

	private boolean isPlayerInRoom() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && ROOM_BOUNDS.contains(mc.player.position());
	}

	private void onComplete() {
		if (alertOnComplete) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§aSharp shooter device complete"));
		}
		markedPositions.clear();
		targetPosition = null;
		prediction = null;
		deviceComplete = true;
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Arrows Device render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P3) return;
		if (showEmerald) {
			int emeraldAlpha = Math.round(emeraldOpacity / 100f * 255);
			for (int i = 0; i < DEVICE_POSITIONS.size(); i++) {
				if (lastBlocks[i] != Blocks.EMERALD_BLOCK) continue;
				BlockPos pos = DEVICE_POSITIONS.get(i);
				if (markedPositions.contains(pos) || pos.equals(targetPosition)) continue;
				World3DRenderer.drawFilledBox(new AABB(pos), (emeraldAlpha << 24) | EMERALD_COLOR);
			}
		}
		int markedAlpha = Math.round(50 / 100f * 255);
		for (BlockPos pos : markedPositions) {
			World3DRenderer.drawFilledBox(new AABB(pos), (markedAlpha << 24) | (markedColor & 0xFFFFFF));
		}
		if (targetPosition != null) {
			World3DRenderer.drawFilledBox(new AABB(targetPosition), (markedAlpha << 24) | (targetColor & 0xFFFFFF));
			// Real target and predicted-next target can land on the same block — only draw it once, in the
			// target's own color, matching NoammAddons' own collapse-if-equal behavior.
			if (showPrediction && prediction != null && !prediction.equals(targetPosition)) {
				World3DRenderer.drawFilledBox(new AABB(prediction), (markedAlpha << 24) | (predictionColor & 0xFFFFFF));
			}
		}
	}

	public int getMarkedColor() { return markedColor; }
	public void setMarkedColor(int value) { markedColor = value; }
	public int getTargetColor() { return targetColor; }
	public void setTargetColor(int value) { targetColor = value; }
	public boolean isAlertOnComplete() { return alertOnComplete; }
	public void setAlertOnComplete(boolean value) { alertOnComplete = value; }
	public boolean isShowEmerald() { return showEmerald; }
	public void setShowEmerald(boolean value) { showEmerald = value; }
	public int getEmeraldOpacity() { return emeraldOpacity; }
	public void setEmeraldOpacity(int value) { emeraldOpacity = Math.max(0, Math.min(100, value)); }
	public boolean isShowPrediction() { return showPrediction; }
	public void setShowPrediction(boolean value) { showPrediction = value; }
	public int getPredictionColor() { return predictionColor; }
	public void setPredictionColor(int value) { predictionColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("markedColor", markedColor);
		obj.addProperty("targetColor", targetColor);
		obj.addProperty("alertOnComplete", alertOnComplete);
		obj.addProperty("showEmerald", showEmerald);
		obj.addProperty("emeraldOpacity", emeraldOpacity);
		obj.addProperty("showPrediction", showPrediction);
		obj.addProperty("predictionColor", predictionColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("markedColor")) markedColor = obj.get("markedColor").getAsInt();
		if (obj.has("targetColor")) targetColor = obj.get("targetColor").getAsInt();
		if (obj.has("alertOnComplete")) alertOnComplete = obj.get("alertOnComplete").getAsBoolean();
		if (obj.has("showEmerald")) showEmerald = obj.get("showEmerald").getAsBoolean();
		if (obj.has("emeraldOpacity")) emeraldOpacity = obj.get("emeraldOpacity").getAsInt();
		if (obj.has("showPrediction")) showPrediction = obj.get("showPrediction").getAsBoolean();
		if (obj.has("predictionColor")) predictionColor = obj.get("predictionColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Solves the F7 Sharp Shooter device: highlights the target blocks in the 3x3x3 grid you still need to shoot.";
	}
}
