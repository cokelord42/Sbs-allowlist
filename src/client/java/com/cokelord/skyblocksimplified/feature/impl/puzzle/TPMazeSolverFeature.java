package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** F2 "Teleport Maze" solver — tracks which end-portal-frame teleport pads have been visited, narrows
 *  down the "correct" (still-plausible) pads for the current group of 4 by look-ray triangulation across
 *  successive teleports, and highlights pads + a tracer to the best next guess. Ported from Odin's
 *  {@code TPMazeSolver.kt}.
 *
 * <p>Odin detects each teleport from the raw {@code ClientboundPlayerPositionPacket} (reading the exact
 * synced position/yaw/pitch at the instant of arrival). This codebase has no generic incoming-packet
 * listener registry for that without a new Mixin, so this instead polls the player entity's position each
 * tick (vanilla already applies a teleport packet to the entity immediately on receipt, so this lags by
 * at most one tick — imperceptible for a puzzle solved over many seconds). */
public class TPMazeSolverFeature extends Feature {
	private static final List<BlockPos> FRAME_LOCATIONS = List.of(
		new BlockPos(4, 69, 12), new BlockPos(4, 69, 6), new BlockPos(10, 69, 12), new BlockPos(10, 69, 6),
		new BlockPos(4, 69, 20), new BlockPos(4, 69, 14), new BlockPos(10, 69, 20), new BlockPos(10, 69, 14),
		new BlockPos(4, 69, 28), new BlockPos(4, 69, 22), new BlockPos(10, 69, 28), new BlockPos(10, 69, 22),
		new BlockPos(12, 69, 28), new BlockPos(12, 69, 22), new BlockPos(18, 69, 28), new BlockPos(18, 69, 22),
		new BlockPos(20, 69, 28), new BlockPos(20, 69, 22), new BlockPos(26, 69, 28), new BlockPos(26, 69, 22),
		new BlockPos(26, 69, 20), new BlockPos(26, 69, 14), new BlockPos(20, 69, 20), new BlockPos(20, 69, 14),
		new BlockPos(26, 69, 12), new BlockPos(26, 69, 6), new BlockPos(20, 69, 12), new BlockPos(20, 69, 6),
		new BlockPos(15, 69, 14), new BlockPos(15, 69, 12)
	);

	private List<BlockPos> tpPads = List.of();
	private List<BlockPos> correctPortals = new ArrayList<>();
	private final Set<BlockPos> visited = new HashSet<>();
	private BlockPos best;
	private Vec3 lastPlayerPos = Vec3.ZERO;

	private int colorOne = 0xFF55FF55;
	private int colorMultiple = 0xFFFFFF55;
	private int colorVisited = 0xFFFF5555;

	private static boolean listenersRegistered = false;
	private static TPMazeSolverFeature instance;

	public TPMazeSolverFeature() {
		super("puzzle_tp_maze", "Teleport Maze Solver", FeatureCategory.COMBAT, false);
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
			// Per user request ("Remove the tracer support from the teleport pad solver, it does nothing"):
			// the pad boxes render via World3DRenderer (real depth-tested 3D); the tracer line to the "best"
			// guess pad was a separate screen-space overlay that's been removed entirely.
			World3DRenderer.addRenderCallback(TPMazeSolverFeature::renderBoxes);
		}
	}

	@Override
	protected void onDisable() { reset(); }

	private void reset() {
		correctPortals = new ArrayList<>();
		visited.clear();
		best = null;
	}

	private void onRoomEnter(DungeonRoom room) {
		if (room != null && room.data != null && "Teleport Maze".equals(room.data.getName())) {
			List<BlockPos> pads = new ArrayList<>();
			for (BlockPos rel : FRAME_LOCATIONS) pads.add(room.getRealCoords(rel));
			tpPads = pads;
		}
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.player == null) return;
		if (!"Teleport Maze".equals(currentRoomName()) || tpPads.isEmpty()) { lastPlayerPos = client.player.position(); return; }

		Vec3 pos = client.player.position();
		boolean teleported = pos.x % 0.5 == 0.0 && pos.y == 69.5 && pos.z % 0.5 == 0.0 && pos.distanceToSqr(lastPlayerPos) > 4.0;
		lastPlayerPos = pos;
		if (!teleported) return;

		float yaw = client.player.getYRot();
		float pitch = client.player.getXRot();

		AABB posAabb = AABB.unitCubeFromLowerCorner(pos).inflate(1.0, 0.0, 1.0);
		for (BlockPos pad : tpPads) {
			if (posAabb.intersects(new AABB(pad)) || client.player.getBoundingBox().inflate(1.0, 0.0, 1.0).intersects(new AABB(pad))) {
				visited.add(pad);
			}
		}
		narrowCorrectPortals(pos, yaw, pitch);

		BlockPos currentPad = null;
		for (BlockPos pad : tpPads) if (posAabb.intersects(new AABB(pad))) { currentPad = pad; break; }
		if (currentPad == null) return;

		int index = tpPads.indexOf(currentPad);
		if (index >= 28 && index <= 29) { best = null; return; }

		int groupStart = index / 4 * 4;
		if (groupStart + 4 > tpPads.size()) return;

		List<BlockPos> candidates = new ArrayList<>();
		for (int i = groupStart; i < groupStart + 4; i++) {
			BlockPos pad = tpPads.get(i);
			if (!pad.equals(currentPad) && !visited.contains(pad)) candidates.add(pad);
		}

		best = null;
		for (BlockPos candidate : candidates) if (correctPortals.contains(candidate)) { best = candidate; break; }
		if (best == null && !candidates.isEmpty()) {
			double bestDiff = Double.MAX_VALUE;
			for (BlockPos candidate : candidates) {
				double centerX = candidate.getX() + 0.5, centerZ = candidate.getZ() + 0.5;
				float targetYaw = (float) (Math.toDegrees(Math.atan2(centerZ - pos.z, centerX - pos.x))) - 90f;
				double diff = Math.abs(Mth.wrapDegrees(targetYaw) - Mth.wrapDegrees(yaw));
				if (diff < bestDiff) { bestDiff = diff; best = candidate; }
			}
		}
	}

	private void narrowCorrectPortals(Vec3 pos, float yaw, float pitch) {
		if (correctPortals.isEmpty()) correctPortals = new ArrayList<>(tpPads);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		List<BlockPos> filtered = new ArrayList<>();
		for (BlockPos pad : correctPortals) {
			if (visited.contains(pad)) continue;
			AABB box = new AABB(pad.getX(), pad.getY(), pad.getZ(), pad.getX() + 1.0, pad.getY() + 4.0, pad.getZ() + 1.0).inflate(0.75, 0.0, 0.75);
			if (!isInterceptable(box, 32.0, pos, yaw, pitch)) continue;
			if (new AABB(pad).inflate(0.5, 0.0, 0.5).intersects(mc.player.getBoundingBox())) continue;
			filtered.add(pad);
		}
		correctPortals = filtered;
	}

	private static boolean isInterceptable(AABB box, double range, Vec3 pos, float yaw, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		double eyeY = mc.player != null ? mc.player.getEyeY() : pos.y;
		Vec3 start = new Vec3(pos.x, eyeY, pos.z);
		Vec3 look = viewVector(yaw, pitch);
		Vec3 goal = start.add(look.scale(range));
		return box.clip(start, goal).isPresent();
	}

	private static Vec3 viewVector(float yaw, float pitch) {
		float f = pitch * ((float) Math.PI / 180F);
		float g = -yaw * ((float) Math.PI / 180F);
		float cosG = Mth.cos(g), sinG = Mth.sin(g), cosF = Mth.cos(f), sinF = Mth.sin(f);
		return new Vec3(sinG * cosF, -sinF, cosG * cosF);
	}

	private static String currentRoomName() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		return room != null && room.data != null ? room.data.getName() : null;
	}

	private static void renderBoxes() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderBoxesInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Teleport Maze solver box render failed, skipping this frame", e);
		}
	}

	private void renderBoxesInner() {
		if (!"Teleport Maze".equals(currentRoomName())) return;

		for (BlockPos pad : tpPads) {
			AABB box = new AABB(pad);
			int color;
			int opacity;
			if (correctPortals.contains(pad)) {
				color = correctPortals.size() == 1 ? colorOne : colorMultiple;
				opacity = 60;
			} else if (visited.contains(pad)) {
				color = colorVisited;
				opacity = 60;
			} else {
				color = 0xFFFFFFFF;
				opacity = 30;
			}
			int alpha = Math.round(opacity / 100f * 255);
			World3DRenderer.drawFilledBox(box, (alpha << 24) | (color & 0xFFFFFF));
		}
	}

	public int getColorOne() { return colorOne; }
	public void setColorOne(int value) { colorOne = value; }
	public int getColorMultiple() { return colorMultiple; }
	public void setColorMultiple(int value) { colorMultiple = value; }
	public int getColorVisited() { return colorVisited; }
	public void setColorVisited(int value) { colorVisited = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("colorOne", colorOne);
		obj.addProperty("colorMultiple", colorMultiple);
		obj.addProperty("colorVisited", colorVisited);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("colorOne")) colorOne = obj.get("colorOne").getAsInt();
		if (obj.has("colorMultiple")) colorMultiple = obj.get("colorMultiple").getAsInt();
		if (obj.has("colorVisited")) colorVisited = obj.get("colorVisited").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Solves the F2 Teleport Maze puzzle: tracks visited pads and narrows down which remaining pad is correct.";
	}
}
