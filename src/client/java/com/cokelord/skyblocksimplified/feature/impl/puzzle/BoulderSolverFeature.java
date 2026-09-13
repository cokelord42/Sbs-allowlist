package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** F6/M6 "Boulder" puzzle solver — reads the floor's pressure-plate/air pattern as a bit string, looks up
 *  the matching click solution from a bundled table, and highlights the next block(s) to click. Ported
 *  from Odin's {@code BoulderSolver.kt}. Odin detects each correct click via the outgoing
 *  {@code ServerboundUseItemOnPacket}; this uses the equivalent local block-interact hook this codebase
 *  already has ({@code UseBlockCallback}, the same one {@code DungeonBlockDetector} is built on). */
public class BoulderSolverFeature extends Feature {
	private record Solution(AABB render, BlockPos click) {}

	private final Map<String, List<List<Integer>>> solutions = DungeonJsonAssets.load(
		"/assets/skyblocksimplified/dungeon/puzzles/boulderSolutions.json",
		new TypeToken<Map<String, List<List<Integer>>>>() {}.getType(), new HashMap<>());

	private final List<Solution> currentPositions = new ArrayList<>();

	private boolean showAllClicks = false;
	// Outline-only default per user request, matching Etherwarp's own default box style.
	private WorldRenderUtil.RenderStyle style = WorldRenderUtil.RenderStyle.OUTLINE;
	private int color = 0xFF55FF55;
	// Same meaning/default as BlazeSolverFeature's own occlusion3D field: true draws real depth-tested boxes
	// (hidden behind walls), false uses the ThroughWalls variants (ESP-style, visible through walls).
	private boolean occlusion = true;

	private static boolean listenersRegistered = false;
	private static BoulderSolverFeature instance;

	public BoulderSolverFeature() {
		super("puzzle_boulder", "Boulder Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		currentPositions.clear();
		if (!listenersRegistered) {
			listenersRegistered = true;
			WorldScan.addRoomEnterListener(room -> { if (instance != null && instance.isEnabled()) instance.onRoomEnter(room); });
			UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
				if (instance != null && instance.isEnabled()) instance.onBlockClicked(hitResult.getBlockPos());
				return InteractionResult.PASS;
			});
			// Real depth-tested 3D rendering (World3DRenderer) instead of WorldRenderUtil's screen-space
			// projection, per user request ("make the boulder solver also use a block highlight and not a
			// 2d box"), matching every other puzzle solver's own earlier conversion.
			World3DRenderer.addRenderCallback(BoulderSolverFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { currentPositions.clear(); }

	private void onRoomEnter(DungeonRoom room) {
		currentPositions.clear();
		if (room == null || room.data == null || !"Boulder".equals(room.data.getName())) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		StringBuilder pattern = new StringBuilder();
		for (int z = 24; z >= 9; z -= 3) {
			for (int x = 24; x >= 6; x -= 3) {
				boolean air = mc.level.getBlockState(room.getRealCoords(new BlockPos(x, 66, z))).isAir();
				pattern.append(air ? '0' : '1');
			}
		}

		List<List<Integer>> solution = solutions.get(pattern.toString());
		if (solution == null) return;
		for (List<Integer> sol : solution) {
			if (sol.size() < 4) continue;
			BlockPos render = room.getRealCoords(new BlockPos(sol.get(0), 65, sol.get(1)));
			BlockPos click = room.getRealCoords(new BlockPos(sol.get(2), 65, sol.get(3)));
			currentPositions.add(new Solution(new AABB(render), click));
		}
	}

	private void onBlockClicked(BlockPos pos) {
		currentPositions.removeIf(s -> s.click.equals(pos));
	}

	private static void renderStatic() {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Boulder solver render failed, skipping this frame", e);
		}
	}

	private void renderInner() {
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (room == null || room.data == null || !"Boulder".equals(room.data.getName()) || currentPositions.isEmpty()) return;

		if (showAllClicks) {
			for (Solution s : currentPositions) drawBox(s.render);
		} else {
			drawBox(currentPositions.get(0).render);
		}
	}

	// Mirrors BeamsSolverFeature's own drawBox opacity rules (this used to call drawStyledBox with a flat
	// 50% opacity) — the wire outline keeps the color's own literal alpha, only the fill gets the opacity.
	private void drawBox(AABB box) {
		if (style == WorldRenderUtil.RenderStyle.FILLED || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			int opacityPercent = style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE ? 25 : 50;
			int alpha = Math.round(opacityPercent / 100f * 255);
			int fillArgb = (alpha << 24) | (color & 0xFFFFFF);
			if (occlusion) World3DRenderer.drawFilledBox(box, fillArgb);
			else World3DRenderer.drawFilledBoxThroughWalls(box, fillArgb);
		}
		if (style == WorldRenderUtil.RenderStyle.OUTLINE || style == WorldRenderUtil.RenderStyle.FILLED_OUTLINE) {
			if (occlusion) World3DRenderer.drawWireBox(box, color, 2f);
			else World3DRenderer.drawWireBoxThroughWalls(box, color, 2f);
		}
	}

	public boolean isShowAllClicks() { return showAllClicks; }
	public void setShowAllClicks(boolean value) { showAllClicks = value; }
	public WorldRenderUtil.RenderStyle getStyle() { return style; }
	public void setStyle(WorldRenderUtil.RenderStyle value) { style = value; }
	public int getColor() { return color; }
	public void setColor(int value) { color = value; }
	public boolean isOcclusion() { return occlusion; }
	public void setOcclusion(boolean value) { occlusion = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showAllClicks", showAllClicks);
		obj.addProperty("style", style.name());
		obj.addProperty("color", color);
		obj.addProperty("occlusion", occlusion);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("showAllClicks")) showAllClicks = obj.get("showAllClicks").getAsBoolean();
		if (obj.has("style")) { try { style = WorldRenderUtil.RenderStyle.valueOf(obj.get("style").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("color")) color = obj.get("color").getAsInt();
		if (obj.has("occlusion")) occlusion = obj.get("occlusion").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Solves the F6/M6 Boulder puzzle: reads the pressure-plate pattern and highlights the next block(s) to click.";
	}
}
