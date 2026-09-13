package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** F5 "Ice Fill" puzzle solver — for each of the 3 sub-floors, identifies which of several known layout
 *  variants is currently active (by checking a pair of reference blocks' air/solid state) and draws the
 *  matching ice-plate walking path. Ported from Odin's {@code IceFillSolver.kt}. */
public class IceFillSolverFeature extends Feature {
	private record JsonPos(int x, int y, int z) {
		BlockPos toBlockPos() { return new BlockPos(x, y, z); }
	}

	private record IceFillData(List<List<List<JsonPos>>> identifier, List<List<List<JsonPos>>> easy, List<List<List<JsonPos>>> hard) {}

	private final IceFillData data = DungeonJsonAssets.load(
		"/assets/skyblocksimplified/dungeon/puzzles/iceFillFloors.json",
		IceFillData.class,
		new IceFillData(List.of(), List.of(), List.of()));

	private final List<Vec3> currentPatterns = new ArrayList<>();
	private int lineColor = 0xFF55FFFF;
	private boolean optimizedPatterns = false;

	private static boolean listenersRegistered = false;
	private static IceFillSolverFeature instance;

	public IceFillSolverFeature() {
		super("puzzle_ice_fill", "Ice Fill Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	@Override
	protected void onEnable() {
		currentPatterns.clear();
		if (!listenersRegistered) {
			listenersRegistered = true;
			WorldScan.addRoomEnterListener(room -> { if (instance != null && instance.isEnabled()) instance.onRoomEnter(room); });
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "puzzle_ice_fill"), IceFillSolverFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() { currentPatterns.clear(); }

	private void onRoomEnter(DungeonRoom room) {
		if (room == null || room.data == null || !"Ice Fill".equals(room.data.getName()) || !currentPatterns.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		List<List<List<JsonPos>>> patterns = optimizedPatterns ? data.hard : data.easy;

		for (int index = 0; index < 3 && index < data.identifier.size(); index++) {
			List<List<JsonPos>> floorIdentifiers = data.identifier.get(index);
			boolean found = false;
			for (int patternIndex = 0; patternIndex < floorIdentifiers.size(); patternIndex++) {
				List<JsonPos> pair = floorIdentifiers.get(patternIndex);
				if (pair.size() < 2) continue;
				boolean firstAir = mc.level.getBlockState(room.getRealCoords(pair.get(0).toBlockPos())).isAir();
				boolean secondAir = mc.level.getBlockState(room.getRealCoords(pair.get(1).toBlockPos())).isAir();
				if (firstAir && !secondAir) {
					if (index < patterns.size() && patternIndex < patterns.get(index).size()) {
						for (JsonPos p : patterns.get(index).get(patternIndex)) {
							BlockPos real = room.getRealCoords(p.toBlockPos());
							currentPatterns.add(new Vec3(real.getX(), real.getY(), real.getZ()).add(0.5, 0.1, 0.5));
						}
					}
					found = true;
					break;
				}
			}
			if (!found) SkyblockSimplified.LOGGER.warn("Ice Fill: failed to identify layout for floor {}", index);
		}
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		try {
			if (instance.currentPatterns.isEmpty()) return;
			DungeonRoom room = WorldScan.getCurrentRoom();
			if (room == null || room.data == null || !"Ice Fill".equals(room.data.getName())) return;
			for (int i = 0; i < instance.currentPatterns.size() - 1; i++) {
				WorldRenderUtil.drawLine(graphics, instance.currentPatterns.get(i), instance.currentPatterns.get(i + 1), instance.lineColor, 2);
			}
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Ice Fill solver render failed, skipping this frame", e);
		}
	}

	public int getLineColor() { return lineColor; }
	public void setLineColor(int value) { lineColor = value; }
	public boolean isOptimizedPatterns() { return optimizedPatterns; }
	public void setOptimizedPatterns(boolean value) { optimizedPatterns = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("lineColor", lineColor);
		obj.addProperty("optimizedPatterns", optimizedPatterns);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("lineColor")) lineColor = obj.get("lineColor").getAsInt();
		if (obj.has("optimizedPatterns")) optimizedPatterns = obj.get("optimizedPatterns").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Solves the F5 Ice Fill puzzle: identifies the active layout for each sub-floor and draws the matching ice path.";
	}
}
