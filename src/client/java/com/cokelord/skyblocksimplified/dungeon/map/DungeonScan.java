package com.cokelord.skyblocksimplified.dungeon.map;

import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonDoor;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonTile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Central dungeon room/door/tile-graph state for the current run — the shared foundation nearly every
 *  dungeon feature reads room-relative coordinates and current-room data from. Faithful port of Odin's
 *  {@code DungeonScan.kt}, trimmed to the parts load-bearing for the currently-ported feature set: the
 *  minimap-specific viewable-door-coloring/path-hint logic (tied to Odin's on-screen minimap, which this
 *  port deliberately doesn't reproduce — see the port plan) is dropped; door coloring for Door Highlight
 *  is implemented directly in that feature instead of through a shared cache. */
public final class DungeonScan {
	public static final int ROOM_SPACING = 4;

	public static volatile int roomSize = 16;
	public static volatile int roomGap = 20;
	public static volatile int startX = 5;
	public static volatile int startY = 5;

	public static int connectionGap() {
		return roomSize + ROOM_SPACING / 2;
	}

	public static final DungeonTile[] tiles = new DungeonTile[36];
	public static final List<com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom> rooms = new CopyOnWriteArrayList<>();
	public static final Map<IVec2, DungeonDoor> doors = new ConcurrentHashMap<>();

	static {
		resetTiles();
	}

	private DungeonScan() {}

	public static void reset() {
		resetTiles();
		rooms.clear();
		doors.clear();
		roomSize = 16;
		roomGap = 20;
		startX = 5;
		startY = 5;
	}

	private static void resetTiles() {
		for (int index = 0; index < 36; index++) {
			tiles[index] = new DungeonTile(new IVec2(index % 6, index / 6));
		}
	}

	/** Called on floor entry (floor number 0 = entrance, 1-7 normal, matching Odin's Floor.floorNumber)
	 *  to set the room-grid spacing that changes between the early (bigger-room) and late floors. */
	public static void initClient(int floorNumber) {
		roomSize = floorNumber <= 3 ? 18 : 16;
		roomGap = roomSize + ROOM_SPACING;

		startX = floorNumber <= 1 ? 22 : floorNumber <= 3 ? 11 : 5;

		startY = switch (floorNumber) {
			case 0 -> 22;
			case 4 -> 16;
			case 1, 2, 3 -> 11;
			default -> 5;
		};
	}
}
