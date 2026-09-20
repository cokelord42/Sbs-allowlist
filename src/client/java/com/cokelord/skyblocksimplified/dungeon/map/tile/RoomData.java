package com.cokelord.skyblocksimplified.dungeon.map.tile;

import com.cokelord.skyblocksimplified.dungeon.map.DungeonJsonAssets;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Static room-database lookup: a dungeon room's "core" (a hash of its terrain layout, computed by
 *  {@code WorldScan.getRoomCore}) maps to its known name/type/shape/secret-count via a bundled data table
 *  (ported from Odin's {@code assets/odin/rooms.json} — a plain scanned-world data table, carried over
 *  as-is under {@code assets/skyblocksimplified/dungeon/rooms.json}). */
public final class RoomData {
	private static final String RESOURCE_PATH = "/assets/skyblocksimplified/dungeon/rooms.json";
	private static final Map<Integer, RoomData> CORE_TO_ROOM_DATA = new HashMap<>();

	static {
		List<Json> entries = DungeonJsonAssets.load(RESOURCE_PATH, new TypeToken<List<Json>>() {}.getType(), new ArrayList<>());
		for (Json entry : entries) {
			RoomData room = new RoomData(entry.name, entry.type, RoomShape.fromJson(entry.shape),
				entry.cores != null ? entry.cores : List.of(),
				entry.crypts != null ? entry.crypts : 0,
				entry.secrets != null ? entry.secrets : 0,
				entry.trappedChests != null ? entry.trappedChests : 0);
			for (int core : room.cores) CORE_TO_ROOM_DATA.put(core, room);
		}
	}

	public static RoomData getRoomData(int core) {
		return CORE_TO_ROOM_DATA.get(core);
	}

	/** Every known room name in the bundled database, deduplicated (multiple "cores" — rotation/variant
	 *  hashes — commonly map to the same named room) and alphabetically sorted. Used by Dungeon Routes'
	 *  settings panel to list every room the user can build a route for, independent of any live dungeon. */
	public static List<String> getAllRoomNames() {
		java.util.TreeSet<String> names = new java.util.TreeSet<>();
		for (RoomData room : CORE_TO_ROOM_DATA.values()) {
			if (room.name != null && !room.name.isBlank()) names.add(room.name);
		}
		return new ArrayList<>(names);
	}

	private final String name;
	private final RoomType type;
	private final RoomShape shape;
	private final List<Integer> cores;
	private final int crypts;
	private final int secrets;
	private final int trappedChests;

	private RoomData(String name, RoomType type, RoomShape shape, List<Integer> cores, int crypts, int secrets, int trappedChests) {
		this.name = name;
		this.type = type;
		this.shape = shape;
		this.cores = cores;
		this.crypts = crypts;
		this.secrets = secrets;
		this.trappedChests = trappedChests;
	}

	public String getName() {
		return name;
	}

	public RoomType getType() {
		return type;
	}

	public RoomShape getShape() {
		return shape;
	}

	public int getSecrets() {
		return secrets;
	}

	public int getCrypts() {
		return crypts;
	}

	public int getTrappedChests() {
		return trappedChests;
	}

	private static final class Json {
		String name;
		RoomType type;
		String shape;
		List<Integer> cores;
		Integer crypts;
		Integer secrets;
		Integer trappedChests;
	}
}
