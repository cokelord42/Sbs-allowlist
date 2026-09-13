package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomData;
import com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature;
import com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.PearlShot;
import com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.RouteItem;
import com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature.StepType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per user request ("Find a way to import routes from SecretRoutes mod, will provide source code including
 * all routes... make our routes syntax into their syntax so it automatically gets [supported]"): parses a
 * raw SecretRoutes-mod route file (its {@code pearlroutes.json}/{@code routes.json}/{@code fowroutes.json}/
 * etc — same schema across all of them, confirmed against the mod's real source) directly into this
 * codebase's own {@link DungeonRoutesFeature.RouteItem} model, room by room.
 *
 * <p>Coordinate system: confirmed by reading SecretRoutes' own {@code RoomRotationUtils}/{@code
 * RotationUtils} line for line — its room-relative X/Y/Z convention (origin at the room's own clay corner,
 * rotated per S/N/E/W the exact same way for every letter) is IDENTICAL to {@link DungeonRoutesFeature}'s
 * own ({@code rotateVecAroundNorth}, room corner = SBS's own clayPos). This means every raw coordinate in
 * their JSON can be used as-is, with zero transformation — the user's own hope ("coordinates based on the
 * room detection system we currently have, atleast i hope its the same") holds up under direct comparison.
 *
 * <p>Room name matching: their keys are hyphenated with an optional trailing secret-count and an optional
 * {@code :N} variant suffix (e.g. {@code "Big-Red-Flag-2"}, {@code "Altar-6:1"}) — matched against {@link
 * RoomData#getAllRoomNames()} (space-separated, no count) by stripping both suffixes and swapping hyphens
 * for spaces, case-insensitively.
 *
 * <p>Field mapping per room-step object, straight off their real {@code Room.WAYPOINT_TYPES}/{@code
 * SecretUtils.renderingCallback}: {@code etherwarps} → one {@link StepType#ENDERWARP_WAYPOINT} item (AOTV
 * waypoints), {@code mines} → one {@link StepType#BREAKABLE_BLOCKS} item ("Stonk" is just community slang
 * for the old ghost-block mining item, not a mechanically distinct category from Breakable Blocks — per
 * user correction), {@code interacts} →
 * one {@link StepType#INTERACT_WAYPOINT} item (Interact waypoints), {@code tnts} → one {@link
 * StepType#SUPERBOOM_BLOCK} item per point (Superboom waypoints), {@code enderpearls}+{@code
 * enderpearlangles} → one {@link StepType#ENDERPEARL_WAYPOINT} item carrying every pearl as a {@link
 * PearlShot} (Ender pearl waypoints / launch angle lines), {@code locations} → attached as cosmetic {@link
 * RouteItem#pathPoints} on the first item created for that step (see its own doc comment for why this
 * replaces creating a chain of generic WAYPOINT items), {@code secret} → the real completable target
 * ({@code item}→SECRET_PICKUP, {@code bat}→SECRET_BAT, {@code interact}→SECRET_CHEST — SecretRoutes doesn't
 * distinguish a chest from a lever under "interact", and a locked chest's own lever is a real, separate,
 * earlier {@code interacts} entry in practice — {@code exit}/{@code exitroute} isn't a real secret at all,
 * just a room-exit signpost, so it produces no item of its own). Every action item on a step gets {@code
 * advancesStep=false} except the real terminal secret item, so the step only truly completes once the
 * actual secret is taken — exactly how {@link DungeonRoutesFeature.RouteItem#advancesStep}'s own doc
 * comment says it should be used for a multi-item step.
 */
public final class SecretRoutesImporter {
	private SecretRoutesImporter() {}

	public record ImportResult(int roomsMatched, int roomsUnmatched, int stepsImported, List<String> unmatchedKeys) {}

	public static ImportResult importJson(String rawJson, DungeonRoutesFeature feature) {
		JsonObject root;
		try {
			root = JsonParser.parseString(rawJson).getAsJsonObject();
		} catch (Exception e) {
			return new ImportResult(0, 0, 0, List.of());
		}

		Map<String, String> normalizedToRealName = new LinkedHashMap<>();
		for (String real : RoomData.getAllRoomNames()) normalizedToRealName.put(normalize(real), real);

		Map<String, List<RouteItem>> byRoom = new LinkedHashMap<>();
		int roomsMatched = 0, roomsUnmatched = 0, stepsImported = 0;
		List<String> unmatchedKeys = new ArrayList<>();

		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			String key = entry.getKey();
			if (key.startsWith("#") || key.equalsIgnoreCase("Version")) continue;
			if (!entry.getValue().isJsonArray()) continue;

			String variant = "";
			String roomKey = key;
			int colon = key.indexOf(':');
			if (colon >= 0) {
				roomKey = key.substring(0, colon);
				variant = key.substring(colon + 1);
			}
			// Strip a trailing "-<digits>" secret-count suffix (e.g. "Big-Red-Flag-2" -> "Big-Red-Flag").
			String withoutCount = roomKey.replaceAll("-\\d+$", "");
			String realName = normalizedToRealName.get(normalize(withoutCount));
			if (realName == null) {
				roomsUnmatched++;
				unmatchedKeys.add(key);
				continue;
			}
			roomsMatched++;

			List<RouteItem> roomItems = byRoom.computeIfAbsent(realName, n -> new ArrayList<>());
			JsonArray steps = entry.getValue().getAsJsonArray();
			int stepNumber = 0;
			List<RouteItem> keyItems = new ArrayList<>();
			for (JsonElement stepEl : steps) {
				if (!stepEl.isJsonObject()) continue;
				stepNumber++;
				importStep(stepEl.getAsJsonObject(), realName, variant, stepNumber, keyItems);
			}
			// Per user report ("The secrets sometimes import multiple steps on the exact same coordinates and
			// same trigger. Make dupes like these get removed automatically"): SecretRoutes' own source data
			// occasionally repeats the identical real-world trigger (same step type, same position) across two
			// separate step entries within the same room/variant — most consequentially for a real secret
			// (SECRET_PICKUP/SECRET_BAT/SECRET_CHEST), since only ONE physical secret actually exists to trigger
			// it: a duplicate stepNumber slot waiting on that same secret can never itself complete, which is
			// exactly the "skips from step 2 to step 4" symptom reported separately — the real secret pickup at
			// step 2 also happens to be a byte-for-byte duplicate of whatever step 3 wanted, so step 3 never
			// triggers and advanceStep's own "next higher stepNumber with a real trigger" logic jumps straight
			// to step 4. Deduped here (not at save time) so already-imported/already-saved routes are unaffected
			// unless re-imported.
			dedupeAndRenumber(keyItems);
			java.util.Set<Integer> distinctSteps = new java.util.TreeSet<>();
			for (RouteItem item : keyItems) distinctSteps.add(item.stepNumber);
			stepsImported += distinctSteps.size();
			roomItems.addAll(keyItems);
		}

		feature.importRouteItems(byRoom);
		return new ImportResult(roomsMatched, roomsUnmatched, stepsImported, unmatchedKeys);
	}

	/** Returns 1 if this step produced at least one item (for the caller's step-count tally), 0 if it was
	 *  entirely empty (no action categories and no real secret) — such a step contributes nothing and isn't
	 *  worth a stepNumber slot, but stepNumber itself still advances 1:1 with the source array so later
	 *  steps' own numbering isn't disturbed by an earlier empty one. */
	private static int importStep(JsonObject step, String roomName, String variant, int stepNumber, List<RouteItem> out) {
		List<RouteItem> created = new ArrayList<>();

		List<BlockPos> etherwarps = readBlockList(step, "etherwarps");
		if (!etherwarps.isEmpty()) created.add(listItem(roomName, variant, stepNumber, StepType.ENDERWARP_WAYPOINT, etherwarps));

		List<BlockPos> mines = readBlockList(step, "mines");
		if (!mines.isEmpty()) created.add(listItem(roomName, variant, stepNumber, StepType.BREAKABLE_BLOCKS, mines));

		// Per user request ("The interact module can be deleted since we already have a lever module in
		// dungeon routes"): INTERACT_WAYPOINT is no longer produced by new imports — every "interacts" entry
		// now maps onto the existing SECRET_LEVER type instead, which already covers "an intermediate
		// button/plate/lever to click" just as well and avoids the redundant, largely-overlapping type. The
		// enum value itself is kept (see StepType's own doc comment) purely so anyone's already-imported/
		// already-saved INTERACT_WAYPOINT steps from before this change keep loading and rendering correctly.
		List<BlockPos> interacts = readBlockList(step, "interacts");
		if (!interacts.isEmpty()) created.add(listItem(roomName, variant, stepNumber, StepType.SECRET_LEVER, interacts));

		if (step.has("tnts") && step.get("tnts").isJsonArray()) {
			for (JsonElement el : step.getAsJsonArray("tnts")) {
				BlockPos pos = readBlockPos(el);
				if (pos == null) continue;
				RouteItem item = positionalItem(roomName, variant, stepNumber, StepType.SUPERBOOM_BLOCK, pos.getX(), pos.getY(), pos.getZ());
				created.add(item);
			}
		}

		if (step.has("enderpearls") && step.get("enderpearls").isJsonArray() && !step.getAsJsonArray("enderpearls").isEmpty()) {
			JsonArray pearls = step.getAsJsonArray("enderpearls");
			JsonArray angles = step.has("enderpearlangles") && step.get("enderpearlangles").isJsonArray()
				? step.getAsJsonArray("enderpearlangles") : new JsonArray();
			RouteItem item = new RouteItem();
			item.roomName = roomName; item.variant = variant; item.stepNumber = stepNumber;
			item.type = StepType.ENDERPEARL_WAYPOINT;
			item.advancesStep = false;
			for (int i = 0; i < pearls.size(); i++) {
				JsonArray loc = pearls.get(i).getAsJsonArray();
				PearlShot shot = new PearlShot();
				shot.x = loc.get(0).getAsDouble(); shot.y = loc.get(1).getAsDouble(); shot.z = loc.get(2).getAsDouble();
				if (i < angles.size()) {
					JsonArray angle = angles.get(i).getAsJsonArray();
					// Their own storage order is [pitch, yaw] (confirmed against SecretUtils#renderEnderPearls).
					shot.pitch = angle.get(0).getAsFloat();
					shot.yaw = angle.get(1).getAsFloat();
				}
				item.pearlShots.add(shot);
			}
			if (!item.pearlShots.isEmpty()) {
				item.x = item.pearlShots.get(0).x; item.y = item.pearlShots.get(0).y; item.z = item.pearlShots.get(0).z;
				item.xInput = fmt(item.x); item.yInput = fmt(item.y); item.zInput = fmt(item.z);
			}
			DungeonRoutesFeature.applyDefaultColor(item);
			created.add(item);
		}

		if (step.has("secret") && step.get("secret").isJsonObject()) {
			JsonObject secret = step.getAsJsonObject("secret");
			String type = secret.has("type") ? secret.get("type").getAsString() : "";
			BlockPos loc = secret.has("location") ? readBlockPos(secret.get("location")) : null;
			if (loc != null) {
				StepType secretType = switch (type) {
					case "item" -> StepType.SECRET_PICKUP;
					case "bat" -> StepType.SECRET_BAT;
					case "interact" -> StepType.SECRET_CHEST;
					default -> null; // "exit"/"exitroute" — a room-exit signpost, not a real secret
				};
				if (secretType != null) {
					RouteItem item = positionalItem(roomName, variant, stepNumber, secretType, loc.getX(), loc.getY(), loc.getZ());
					item.advancesStep = true;
					created.add(item);
				}
			}
		}

		if (created.isEmpty()) return 0;

		// Attach the step's own walking path (their "locations") as a cosmetic line on whichever item was
		// created first — see RouteItem#pathPoints' own doc comment.
		List<BlockPos> path = readBlockList(step, "locations");
		if (!path.isEmpty()) created.get(0).pathPoints.addAll(path);

		out.addAll(created);
		return 1;
	}

	/** Removes items whose (type, position) exactly duplicates an earlier item in the SAME room/variant list —
	 *  see this method's call site for why. Order-preserving; when dedup empties out every item that originally
	 *  shared a stepNumber, that step's slot simply disappears. Afterward, renumbers stepNumber to a gapless
	 *  1..N sequence, keeping items that shared an original stepNumber grouped under the same new one (multiple
	 *  items CAN legitimately share one step — see {@link RouteItem#advancesStep}'s own doc comment). */
	private static void dedupeAndRenumber(List<RouteItem> keyItems) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		keyItems.removeIf(item -> !seen.add(identitySignature(item)));

		int newStep = 0;
		int lastOldStep = Integer.MIN_VALUE;
		for (RouteItem item : keyItems) {
			int oldStep = item.stepNumber;
			if (oldStep != lastOldStep) {
				newStep++;
				lastOldStep = oldStep;
			}
			item.stepNumber = newStep;
		}
	}

	/** "Same trigger" for dedup purposes: same step type at the same real-world block(s) — a block-list item
	 *  (Breakable Blocks/Etherwarp/Lever) compares its full block set, a pearl waypoint compares every pearl's
	 *  rounded landing spot, everything else (including the real secret types) compares its single rounded
	 *  x/y/z. Rounded, not exact-double, since these all ultimately come from block-grid JSON coordinates
	 *  already floored in {@link #readBlockPos} — this just guards the couple of fields (pearls) that aren't. */
	private static String identitySignature(RouteItem item) {
		StringBuilder sb = new StringBuilder(item.type.name());
		if (!item.blocks.isEmpty()) {
			for (BlockPos p : item.blocks) sb.append('|').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
		} else if (!item.pearlShots.isEmpty()) {
			for (PearlShot shot : item.pearlShots) {
				sb.append('|').append(Math.round(shot.x)).append(',').append(Math.round(shot.y)).append(',').append(Math.round(shot.z));
			}
		} else {
			sb.append('|').append(Math.round(item.x)).append(',').append(Math.round(item.y)).append(',').append(Math.round(item.z));
		}
		return sb.toString();
	}

	private static List<BlockPos> readBlockList(JsonObject step, String key) {
		List<BlockPos> result = new ArrayList<>();
		if (!step.has(key) || !step.get(key).isJsonArray()) return result;
		for (JsonElement el : step.getAsJsonArray(key)) {
			BlockPos pos = readBlockPos(el);
			if (pos != null) result.add(pos);
		}
		return result;
	}

	private static BlockPos readBlockPos(JsonElement el) {
		if (el == null || !el.isJsonArray()) return null;
		JsonArray arr = el.getAsJsonArray();
		if (arr.size() < 3) return null;
		try {
			return new BlockPos((int) Math.floor(arr.get(0).getAsDouble()), (int) Math.floor(arr.get(1).getAsDouble()), (int) Math.floor(arr.get(2).getAsDouble()));
		} catch (Exception e) {
			return null;
		}
	}

	private static RouteItem listItem(String roomName, String variant, int stepNumber, StepType type, List<BlockPos> blocks) {
		RouteItem item = new RouteItem();
		item.roomName = roomName; item.variant = variant; item.stepNumber = stepNumber; item.type = type;
		item.advancesStep = false;
		item.blocks.addAll(blocks);
		DungeonRoutesFeature.applyDefaultColor(item);
		return item;
	}

	private static RouteItem positionalItem(String roomName, String variant, int stepNumber, StepType type, double x, double y, double z) {
		RouteItem item = new RouteItem();
		item.roomName = roomName; item.variant = variant; item.stepNumber = stepNumber; item.type = type;
		item.advancesStep = false;
		item.x = x; item.y = y; item.z = z;
		item.xInput = fmt(x); item.yInput = fmt(y); item.zInput = fmt(z);
		DungeonRoutesFeature.applyDefaultColor(item);
		return item;
	}

	private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

	private static String normalize(String s) {
		return s.replace('-', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}
}
