package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.farming.CropBreakTracker;
import com.cokelord.skyblocksimplified.farming.FarmingLane;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects a farming lane's boundary by watching where you turn around while farming, and warns before
 * you reach the edge — ported from SkyHanni's FarmingLaneCreator.kt (detection state machine) and
 * FarmingLaneFeatures.kt (distance/switch-warning display). Uses this project's own CropBreakTracker
 * (real client-side block-break events) instead of SkyHanni's CropClickEvent, and approximates player
 * speed from position deltas between ticks instead of SkyHanni's own dedicated speed-tracking feature.
 * Run "/sbarlane" to toggle detection mode, then farm from one end of a row to the other and back a
 * little — the same two-pass walk SkyHanni's own detection needs.
 */
public class FarmingLaneDetectionFeature extends TabWidgetOverlayFeature {
	private static final double MOVE_EPSILON = 0.5;
	private static final double CONFIRM_EPSILON = 2.0;
	private static final long WARN_REPEAT_MILLIS = 5000;

	private final Map<String, FarmingLane> lanes = new HashMap<>();
	private boolean detecting = false;

	private double[] start;
	private double[] lastLocation;
	private double[] potentialEnd;
	private String detectingCrop;
	private double maxDistance;

	private String currentCrop;
	private double[] previousTickPos;
	private String display;
	private long lastWarnMillis = Long.MIN_VALUE;

	@Override
	protected boolean requiresGarden() {
		return true;
	}

	public FarmingLaneDetectionFeature() {
		super("farming_lane_detection", "Farming Lane Detection", FeatureCategory.FARMING, "Farming", 0.01f, 0.56f);
		CropBreakTracker.start();
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			String cropName = CropBreakTracker.cropNameForBlock(state.getBlock());
			if (cropName == null) return;
			currentCrop = cropName;
			if (!detecting || !isEnabled()) return;
			onCropBreak(cropName, player.getX(), player.getY(), player.getZ());
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommands.literal("sbarlane").executes(ctx -> {
				toggleDetection();
				return 1;
			})));
	}

	private void toggleDetection() {
		detecting = !detecting;
		resetDetectionState();
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(detecting
			? "§eEnabled lane detection. Farm two lengths of the row to detect its border."
			: "§eStopped lane detection."));
	}

	private void onCropBreak(String cropName, double x, double y, double z) {
		double[] location = {x, y, z};
		if (lastLocation == null) {
			start = location;
			lastLocation = location;
			maxDistance = 0;
			detectingCrop = cropName;
			return;
		}

		if (!cropName.equals(detectingCrop)) {
			var player = Minecraft.getInstance().player;
			if (player != null) Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§cDifferent crop broken, stopping lane detection"));
			resetDetectionState();
			return;
		}

		if (distance(lastLocation, location) < MOVE_EPSILON) return;
		lastLocation = location;

		double distanceFromStart = distance(start, location);
		if (distanceFromStart > maxDistance) {
			maxDistance = distanceFromStart;
			potentialEnd = null;
		} else if (potentialEnd == null) {
			potentialEnd = location;
		} else if (distance(potentialEnd, location) > CONFIRM_EPSILON) {
			saveLane(start, potentialEnd, cropName);
		}
	}

	private void saveLane(double[] a, double[] b, String cropName) {
		double diffX = a[0] - b[0];
		double diffZ = a[2] - b[2];
		FarmingLane.Axis axis = Math.abs(diffZ) > Math.abs(diffX) ? FarmingLane.Axis.NORTH_SOUTH : FarmingLane.Axis.EAST_WEST;
		double valueA = axis == FarmingLane.Axis.NORTH_SOUTH ? a[2] : a[0];
		double valueB = axis == FarmingLane.Axis.NORTH_SOUTH ? b[2] : b[0];
		lanes.put(cropName, new FarmingLane(axis, Math.min(valueA, valueB), Math.max(valueA, valueB)));

		var player = Minecraft.getInstance().player;
		if (player != null) Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§a" + cropName + " lane saved! Farming Lane features are now working."));
		detecting = false;
		resetDetectionState();
	}

	private void resetDetectionState() {
		start = null;
		lastLocation = null;
		potentialEnd = null;
		detectingCrop = null;
		maxDistance = 0;
	}

	private double distance(double[] a, double[] b) {
		double dx = a[0] - b[0];
		double dy = a[1] - b[1];
		double dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.player == null) {
			display = null;
			previousTickPos = null;
			return;
		}

		double x = client.player.getX();
		double y = client.player.getY();
		double z = client.player.getZ();
		double speedPerSecond = 0;
		if (previousTickPos != null) {
			speedPerSecond = distance(previousTickPos, new double[]{x, y, z}) * 20;
		}
		previousTickPos = new double[]{x, y, z};

		FarmingLane lane = currentCrop != null ? lanes.get(currentCrop) : null;
		if (lane == null) {
			display = null;
			return;
		}

		double position = lane.valueOf(x, z);
		if (position < lane.min() || position > lane.max()) {
			display = null;
			return;
		}
		double distanceToNearEdge = Math.min(position - lane.min(), lane.max() - position);
		display = "§7Distance to lane edge: §e" + String.format("%.1f", distanceToNearEdge);

		if (speedPerSecond > 0.1) {
			double secondsRemaining = distanceToNearEdge / speedPerSecond;
			if (secondsRemaining < 3 && System.currentTimeMillis() - lastWarnMillis > WARN_REPEAT_MILLIS) {
				lastWarnMillis = System.currentTimeMillis();
				client.gui.hud.getChat().addClientSystemMessage(Component.literal("§cApproaching the end of your farming lane!"));
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		return display == null ? List.of() : List.of(display);
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): this used to build a brand new top-level JsonObject (with each lane name itself as a
	// top-level key), discarding the position/scale the TabWidgetOverlayFeature base class now persists
	// (see that class's own doc comment) — lanes now nest under their own "lanes" key on that same object
	// instead of sharing the top level with anchorX/anchorY/scale, so an unluckily-named lane can never
	// collide with those reserved keys.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		JsonObject lanesObj = new JsonObject();
		for (Map.Entry<String, FarmingLane> entry : lanes.entrySet()) {
			JsonObject laneObj = new JsonObject();
			laneObj.addProperty("axis", entry.getValue().axis().name());
			laneObj.addProperty("min", entry.getValue().min());
			laneObj.addProperty("max", entry.getValue().max());
			lanesObj.add(entry.getKey(), laneObj);
		}
		obj.add("lanes", lanesObj);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		JsonElement lanesEl = obj.has("lanes") ? obj.get("lanes") : obj;
		if (!lanesEl.isJsonObject()) return;
		lanes.clear();
		for (Map.Entry<String, JsonElement> entry : lanesEl.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			JsonObject laneObj = entry.getValue().getAsJsonObject();
			if (!laneObj.has("axis") || !laneObj.has("min") || !laneObj.has("max")) continue;
			FarmingLane.Axis axis = FarmingLane.Axis.valueOf(laneObj.get("axis").getAsString());
			double min = laneObj.get("min").getAsDouble();
			double max = laneObj.get("max").getAsDouble();
			lanes.put(entry.getKey(), new FarmingLane(axis, min, max));
		}
	}

	@Override
	public String getDescription() {
		return "Detects the edges of the farming lane you're on and warns you before you reach the end.";
	}
}
