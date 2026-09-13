package com.cokelord.skyblocksimplified.feature.impl.puzzle;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonBlockDetector;
import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.cokelord.skyblocksimplified.dungeon.map.WorldScan;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** F3 "Water Board" lever puzzle solver — reads the extended-wool-slot pattern + a hardcoded reference
 *  block per puzzle "identifier", looks up the correct per-lever click order/timings from a bundled
 *  solution table, and shows a countdown label + tracer over each lever. Ported from Odin's
 *  {@code WaterSolver.kt}, one of 8 originally-combined puzzle solvers split into standalone toggleable
 *  features per the port plan. */
public class WaterSolverFeature extends Feature {
	private enum WoolColor {
		PURPLE(new BlockPos(15, 56, 19)), ORANGE(new BlockPos(15, 56, 18)), BLUE(new BlockPos(15, 56, 17)),
		GREEN(new BlockPos(15, 56, 16)), RED(new BlockPos(15, 56, 15));

		final BlockPos relativePosition;
		WoolColor(BlockPos relativePosition) { this.relativePosition = relativePosition; }

		boolean isExtended(DungeonRoom room) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return false;
			return !mc.level.getBlockState(room.getRealCoords(relativePosition)).isAir();
		}
	}

	private enum LeverBlock {
		COAL(new BlockPos(20, 61, 10)), GOLD(new BlockPos(20, 61, 15)), QUARTZ(new BlockPos(20, 61, 20)),
		DIAMOND(new BlockPos(10, 61, 20)), EMERALD(new BlockPos(10, 61, 15)), CLAY(new BlockPos(10, 61, 10)),
		WATER(new BlockPos(15, 60, 5)), NONE(new BlockPos(0, 0, 0));

		final BlockPos relativePosition;
		int clickCount = 0;
		LeverBlock(BlockPos relativePosition) { this.relativePosition = relativePosition; }

		BlockPos realPos() {
			DungeonRoom room = WorldScan.getCurrentRoom();
			return room != null ? room.getRealCoords(relativePosition) : BlockPos.ZERO;
		}
	}

	private static final Type SOLUTIONS_TYPE = new TypeToken<Map<String, Map<String, Map<String, Map<String, List<Double>>>>>>() {}.getType();
	private final Map<String, Map<String, Map<String, Map<String, List<Double>>>>> waterSolutions =
		DungeonJsonAssets.load("/assets/skyblocksimplified/dungeon/puzzles/waterSolutions.json", SOLUTIONS_TYPE, new HashMap<>());

	private final Map<LeverBlock, double[]> solutions = new HashMap<>();
	private int patternIdentifier = -1;
	private int openedWaterTicks = -1;
	private int tickCounter = 0;

	private boolean showTracer = true;
	private boolean optimized = true;
	private int tracerColor = 0xFF55FF55;
	private int secondColor = 0xFFFFFF55;

	private static boolean listenersRegistered = false;
	private static WaterSolverFeature instance;

	public WaterSolverFeature() {
		super("puzzle_water_board", "Water Board Solver", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Puzzles"; }

	// Real race found (same class of bug as Splits): addRoomEnterListener fires the instant the player is
	// judged to have "entered" the room, but the actual wool-block states it reads may not have finished
	// loading/rendering client-side at that exact tick — scan() would then see fewer than 3 "extended"
	// wool positions and silently give up forever, since nothing ever re-triggered it. Retried for a few
	// seconds after entry instead of only once.
	private DungeonRoom pendingScanRoom;
	private int pendingScanRetryTicks = 0;

	@Override
	protected void onEnable() {
		reset();
		if (!listenersRegistered) {
			listenersRegistered = true;
			WorldScan.addRoomEnterListener(room -> {
				if (instance == null || !instance.isEnabled()) return;
				instance.scan(room);
				if (instance.patternIdentifier == -1 && room != null && room.data != null && "Water Board".equals(room.data.getName())) {
					instance.pendingScanRoom = room;
					instance.pendingScanRetryTicks = 100; // ~5s at 20 ticks/s
				}
			});
			DungeonBlockDetector.addListener((pos, type) -> {
				if (instance == null || !instance.isEnabled() || type != DungeonBlockDetector.ClickedBlockType.LEVER) return;
				instance.onLeverClicked(pos);
			});
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (instance == null || !instance.isEnabled()) return;
				instance.tickCounter++;
				if (instance.pendingScanRetryTicks > 0) {
					instance.pendingScanRetryTicks--;
					if (instance.patternIdentifier == -1 && instance.pendingScanRoom != null) {
						instance.scan(instance.pendingScanRoom);
						if (instance.patternIdentifier != -1) instance.pendingScanRetryTicks = 0;
					} else {
						instance.pendingScanRetryTicks = 0;
					}
				}
			});
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "puzzle_water_board"), WaterSolverFeature::renderStatic);
		}
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		for (LeverBlock lever : LeverBlock.values()) lever.clickCount = 0;
		patternIdentifier = -1;
		solutions.clear();
		openedWaterTicks = -1;
		tickCounter = 0;
	}

	private void scan(DungeonRoom room) {
		if (room == null || room.data == null || !"Water Board".equals(room.data.getName()) || patternIdentifier != -1) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		StringBuilder extended = new StringBuilder();
		for (WoolColor color : WoolColor.values()) if (color.isExtended(room)) extended.append(color.ordinal());
		if (extended.length() != 3) {
			return;
		}

		var block14_77 = mc.level.getBlockState(room.getRealCoords(new BlockPos(14, 77, 27))).getBlock();
		var block16_78 = mc.level.getBlockState(room.getRealCoords(new BlockPos(16, 78, 27))).getBlock();
		var block14_78 = mc.level.getBlockState(room.getRealCoords(new BlockPos(14, 78, 27))).getBlock();

		if (block14_77 == net.minecraft.world.level.block.Blocks.TERRACOTTA) patternIdentifier = 0;
		else if (block16_78 == net.minecraft.world.level.block.Blocks.EMERALD_BLOCK) patternIdentifier = 1;
		else if (block14_78 == net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK) patternIdentifier = 2;
		else if (block14_78 == net.minecraft.world.level.block.Blocks.QUARTZ_BLOCK) patternIdentifier = 3;
		else {
			SkyblockSimplified.LOGGER.warn("Water Board: failed to identify pattern (was the puzzle already started?)");
			return;
		}

		solutions.clear();
		Map<String, List<Double>> entry = waterSolutions.getOrDefault(String.valueOf(optimized), Map.of())
			.getOrDefault(String.valueOf(patternIdentifier), Map.of())
			.get(extended.toString());
		if (entry == null) return;
		for (var mapEntry : entry.entrySet()) {
			LeverBlock lever = switch (mapEntry.getKey()) {
				case "diamond_block" -> LeverBlock.DIAMOND;
				case "emerald_block" -> LeverBlock.EMERALD;
				case "hardened_clay" -> LeverBlock.CLAY;
				case "quartz_block" -> LeverBlock.QUARTZ;
				case "gold_block" -> LeverBlock.GOLD;
				case "coal_block" -> LeverBlock.COAL;
				case "water" -> LeverBlock.WATER;
				default -> LeverBlock.NONE;
			};
			double[] times = mapEntry.getValue().stream().mapToDouble(Double::doubleValue).toArray();
			solutions.put(lever, times);
		}
	}

	private void onLeverClicked(BlockPos pos) {
		if (solutions.isEmpty()) return;
		for (LeverBlock lever : LeverBlock.values()) {
			if (lever.realPos().equals(pos)) {
				if (lever == LeverBlock.WATER && openedWaterTicks == -1) openedWaterTicks = tickCounter;
				lever.clickCount++;
				return;
			}
		}
	}

	private static void renderStatic(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (instance == null || !instance.isEnabled()) return;
		try {
			instance.renderInner(graphics);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Water Board solver render failed, skipping this frame", e);
		}
	}

	private void renderInner(GuiGraphicsExtractor graphics) {
		DungeonRoom room = WorldScan.getCurrentRoom();
		if (patternIdentifier == -1 || solutions.isEmpty() || room == null || room.data == null
			|| !"Water Board".equals(room.data.getName())) return;

		record Candidate(LeverBlock lever, double time) {}
		List<Candidate> candidates = new ArrayList<>();
		for (var entry : solutions.entrySet()) {
			LeverBlock lever = entry.getKey();
			double[] times = entry.getValue();
			for (int i = lever.clickCount; i < times.length; i++) candidates.add(new Candidate(lever, times[i]));
		}
		candidates.sort((a, b) -> {
			boolean aZero = a.time == 0.0, bZero = b.time == 0.0;
			if (aZero != bZero) return aZero ? -1 : 1;
			if (aZero) return Integer.compare(a.lever.ordinal(), b.lever.ordinal());
			return Double.compare(a.time, b.time);
		});

		if (showTracer && !candidates.isEmpty()) {
			LeverBlock first = candidates.get(0).lever;
			Vec3 firstPos = Vec3.atCenterOf(first.realPos());
			WorldRenderUtil.drawTracer(graphics, firstPos, tracerColor, 2);
			if (candidates.size() > 1 && !candidates.get(1).lever.realPos().equals(first.realPos())) {
				WorldRenderUtil.drawLine(graphics, firstPos, Vec3.atCenterOf(candidates.get(1).lever.realPos()), secondColor, 2);
			}
		}

		for (var entry : solutions.entrySet()) {
			LeverBlock lever = entry.getKey();
			double[] times = entry.getValue();
			for (int index = 0; index < times.length - lever.clickCount; index++) {
				double time = times[lever.clickCount + index];
				int timeInTicks = (int) (time * 20);
				String label;
				if (openedWaterTicks == -1) {
					label = timeInTicks == 0 ? "§a§lCLICK ME!" : "§e" + time + "s";
				} else {
					int remaining = openedWaterTicks + timeInTicks - tickCounter;
					label = remaining > 0 ? String.format(Locale.ROOT, "§e%.1fs", remaining / 20f) : "§a§lCLICK ME!";
				}
				Vec3 pos = Vec3.atCenterOf(lever.realPos()).add(0, (index + lever.clickCount) * 0.5 + 0.5, 0);
				WorldRenderUtil.drawText(graphics, label, pos, 0xFFFFFFFF);
			}
		}
	}

	public boolean isShowTracer() { return showTracer; }
	public void setShowTracer(boolean value) { showTracer = value; }
	public boolean isOptimized() { return optimized; }
	public void setOptimized(boolean value) { optimized = value; }
	public int getTracerColor() { return tracerColor; }
	public void setTracerColor(int value) { tracerColor = value; }
	public int getSecondColor() { return secondColor; }
	public void setSecondColor(int value) { secondColor = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("showTracer", showTracer);
		obj.addProperty("optimized", optimized);
		obj.addProperty("tracerColor", tracerColor);
		obj.addProperty("secondColor", secondColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("showTracer")) showTracer = obj.get("showTracer").getAsBoolean();
		if (obj.has("optimized")) optimized = obj.get("optimized").getAsBoolean();
		if (obj.has("tracerColor")) tracerColor = obj.get("tracerColor").getAsInt();
		if (obj.has("secondColor")) secondColor = obj.get("secondColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Solves the F3 Water Board lever puzzle: highlights the correct levers to pull, in order.";
	}
}
