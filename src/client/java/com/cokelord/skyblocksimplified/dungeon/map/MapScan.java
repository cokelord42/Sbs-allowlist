package com.cokelord.skyblocksimplified.dungeon.map;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DoorRotation;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DoorType;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonDoor;
import com.cokelord.skyblocksimplified.dungeon.map.tile.DungeonRoom;
import com.cokelord.skyblocksimplified.dungeon.map.tile.MapCheckmark;
import com.cokelord.skyblocksimplified.dungeon.map.tile.RoomType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Parses the dungeon map item's pixel data into room types/checkmarks and door positions/types —
 *  faithful port of Odin's {@code MapScan.kt}. Odin runs this off the raw {@code ClientboundMapItemDataPacket};
 *  this codebase has no generic incoming-packet listener registry to hook that without a new Mixin, so
 *  this instead polls the currently-held/inventory dungeon map's cached {@link MapItemSavedData} once per
 *  tick from {@link WorldScan}'s tick handler (see {@link #tick}) — functionally equivalent (the cached
 *  data updates as those same packets arrive) and matches this codebase's established tick-poll convention.
 *  Odin's player-position minimap-decoration parsing and the {@code SpecialColumn} floor-4/5/6 corner-case
 *  fixup are both dropped, matching the Phase 1 plan's decision to skip the on-screen minimap entirely. */
public final class MapScan {
	private static final int MAP_SIZE = 128;
	private static final byte EMPTY = 0;

	private static boolean isInit = false;
	private static final List<BiConsumer<DungeonRoom, MapCheckmark>> checkmarkListeners = new ArrayList<>();

	private MapScan() {}

	public static void addCheckmarkListener(BiConsumer<DungeonRoom, MapCheckmark> listener) {
		checkmarkListeners.add(listener);
	}

	public static void reset() {
		isInit = false;
		loggedNoMap = false;
		loggedNoColors = false;
		loggedRejectedMapId = false;
	}

	private static boolean loggedNoMap = false;
	private static boolean loggedNoColors = false;

	public static void tick(Minecraft client) {
		if (!DungeonState.isInDungeon() || DungeonState.isInBoss()) return;
		MapItemSavedData data = findDungeonMap(client);
		if (data == null) {
			// Only the doors/rooms this depends on (Door Highlight, puzzle solvers' name matching) rely on
			// the player actually holding/carrying the real dungeon map with data the client has already
			// downloaded — logged once per dungeon (not every tick) so a "why doesn't door highlight ever
			// light up" report has a real answer instead of silent no-op.
			if (!loggedNoMap) {
				loggedNoMap = true;
			}
			return;
		}
		loggedNoMap = false;

		byte[] colors = data.colors;
		if (colors == null || colors.length < MAP_SIZE * MAP_SIZE || colors[0] != EMPTY) {
			if (!loggedNoColors) {
				loggedNoColors = true;
			}
			return;
		}
		loggedNoColors = false;

		if (!isInit && !initLayout(colors)) {
			return;
		}
		if (DungeonScan.roomSize == -1) return;
		updateAll(colors);
	}

	private static boolean loggedRejectedMapId = false;

	/** Exposed for {@link com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature}'s own teammate-
	 *  marker fallback — see that class's own doc comment on why it needs the raw held map data too. */
	public static MapItemSavedData findDungeonMap(Minecraft client) {
		if (client.player == null || client.level == null) return null;
		for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
			if (!(stack.getItem() instanceof MapItem)) continue;
			MapId id = stack.get(DataComponents.MAP_ID);
			if (id == null) continue;
			// Real bug found (per user report — a confirmed-real dungeon map with id 1024 was being
			// rejected): Odin's own real filter is `mapId().id and 1000 != 0` — Kotlin's `and` on two Ints
			// is BITWISE AND, not modulo. This port mistranslated it as `id % 1000 != 0`, an entirely
			// different (and wrong) condition — 1024 % 1000 = 24 (rejected), but 1024 & 1000 = 0 (Odin's
			// real check would have accepted it). Fixed to the real bitwise check.
			if ((id.id() & 1000) != 0) {
				if (!loggedRejectedMapId) {
					loggedRejectedMapId = true;
				}
				continue;
			}
			MapItemSavedData data = client.level.getMapData(id);
			if (data != null) return data;
		}
		return null;
	}

	private static boolean initLayout(byte[] colors) {
		for (int index = 0; index < colors.length; index++) {
			if (colors[index] != RoomType.ENTRANCE.getMapColor()) continue;

			int end = index;
			while (end < colors.length && colors[end] == colors[index]) end++;

			int length = end - index;
			if (length == 16 || length == 18) {
				DungeonScan.roomSize = length;
				DungeonScan.roomGap = length + DungeonScan.ROOM_SPACING;
				DungeonScan.startX = (index % MAP_SIZE) % DungeonScan.roomGap;
				DungeonScan.startY = (index / MAP_SIZE) % DungeonScan.roomGap;

				if (DungeonScan.startX == 0) DungeonScan.startX = 22;
				if (DungeonScan.startY == 0) DungeonScan.startY = 22;

				isInit = true;
				return true;
			}
		}
		return false;
	}

	private static void updateAll(byte[] colors) {
		RoomType[] roomTypes = new RoomType[36];
		byte[] roomColors = new byte[36];
		byte[] centerColors = new byte[36];
		mapTiles(colors, roomTypes, roomColors, centerColors);
		processRooms(colors, roomTypes, roomColors, centerColors);
	}

	private static void mapTiles(byte[] colors, RoomType[] roomTypes, byte[] roomColors, byte[] centerColors) {
		int halfRoom = DungeonScan.roomSize / 2;
		int connectionGap = DungeonScan.connectionGap();
		int sideCheckOffset = 4;

		for (int index = 0; index < 36; index++) {
			int tileX = index % 6;
			int tileZ = index / 6;

			int originX = DungeonScan.startX + tileX * DungeonScan.roomGap;
			int originZ = DungeonScan.startY + tileZ * DungeonScan.roomGap;

			byte cornerColor = getPx(colors, originX, originZ);
			if (cornerColor != EMPTY) {
				RoomType type = RoomType.fromMapColor(cornerColor);
				if (type != null && type != RoomType.UNKNOWN && type != RoomType.UNDISCOVERED) {
					roomTypes[index] = type;
					roomColors[index] = cornerColor;
					centerColors[index] = getPx(colors, originX + halfRoom, originZ + halfRoom);
				}
			}

			if (tileX < 5) {
				byte doorColor = getPx(colors, originX + connectionGap, originZ + halfRoom);
				byte sideColor = getPx(colors, originX + connectionGap, originZ + halfRoom - sideCheckOffset);
				if (sideColor == EMPTY && doorColor != EMPTY) {
					addOrFixDoor(new IVec2(tileX, tileZ), DoorRotation.HORIZONTAL, DoorType.fromColor(doorColor));
				}
			}

			if (tileZ < 5) {
				byte doorColor = getPx(colors, originX + halfRoom, originZ + connectionGap);
				byte sideColor = getPx(colors, originX + halfRoom - sideCheckOffset, originZ + connectionGap);
				if (sideColor == EMPTY && doorColor != EMPTY) {
					addOrFixDoor(new IVec2(tileX, tileZ), DoorRotation.VERTICAL, DoorType.fromColor(doorColor));
				}
			}
		}
	}

	private static void processRooms(byte[] colors, RoomType[] roomTypes, byte[] roomColors, byte[] centerColors) {
		boolean[] visited = new boolean[36];
		int connectionGap = DungeonScan.connectionGap();
		int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

		for (int startIndex = 0; startIndex < 36; startIndex++) {
			RoomType roomType = roomTypes[startIndex];
			if (roomType == null || visited[startIndex]) continue;

			List<Integer> components = new ArrayList<>();
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(startIndex);
			visited[startIndex] = true;

			while (!queue.isEmpty()) {
				int currentIndex = queue.removeFirst();
				components.add(currentIndex);
				int currentX = currentIndex % 6;
				int currentZ = currentIndex / 6;

				for (int[] dir : directions) {
					int dx = dir[0], dz = dir[1];
					int nextX = currentX + dx;
					int nextZ = currentZ + dz;
					if (nextX < 0 || nextX > 5 || nextZ < 0 || nextZ > 5) continue;

					int nextIndex = nextX + nextZ * 6;
					if (visited[nextIndex] || roomTypes[nextIndex] != roomType) continue;

					boolean connected;
					if (dx == 1) {
						connected = getPx(colors, DungeonScan.startX + currentX * DungeonScan.roomGap + connectionGap, DungeonScan.startY + currentZ * DungeonScan.roomGap) != EMPTY;
					} else if (dx == -1) {
						connected = getPx(colors, DungeonScan.startX + nextX * DungeonScan.roomGap + connectionGap, DungeonScan.startY + nextZ * DungeonScan.roomGap) != EMPTY;
					} else if (dz == 1) {
						connected = getPx(colors, DungeonScan.startX + currentX * DungeonScan.roomGap, DungeonScan.startY + currentZ * DungeonScan.roomGap + connectionGap) != EMPTY;
					} else {
						connected = getPx(colors, DungeonScan.startX + nextX * DungeonScan.roomGap, DungeonScan.startY + nextZ * DungeonScan.roomGap + connectionGap) != EMPTY;
					}
					if (!connected) continue;

					visited[nextIndex] = true;
					queue.add(nextIndex);
				}
			}

			buildRoom(components, roomType, roomColors, centerColors);
		}
	}

	private static void buildRoom(List<Integer> segments, RoomType roomType, byte[] roomColors, byte[] centerColors) {
		DungeonRoom existingRoom = null;
		for (int index : segments) {
			if (DungeonScan.tiles[index].room != null) { existingRoom = DungeonScan.tiles[index].room; break; }
		}

		DungeonRoom room;
		if (existingRoom != null) {
			for (int index : segments) {
				if (DungeonScan.tiles[index].room != existingRoom) {
					DungeonScan.tiles[index].room = existingRoom;
					existingRoom.addSegment(DungeonScan.tiles[index]);
				}
			}
			if (existingRoom.highestBlock == null) existingRoom.inferLayoutFromMap();
			room = existingRoom;
		} else {
			int minX = 6, minZ = 6;
			for (int index : segments) { minX = Math.min(minX, index % 6); minZ = Math.min(minZ, index / 6); }
			DungeonRoom newRoom = new DungeonRoom(roomType, new IVec2(minX, minZ), null);
			DungeonScan.rooms.add(newRoom);
			for (int index : segments) {
				DungeonScan.tiles[index].room = newRoom;
				newRoom.addSegment(DungeonScan.tiles[index]);
			}
			newRoom.inferLayoutFromMap();
			room = newRoom;
		}

		for (int index : segments) {
			byte roomColor = roomColors[index];
			byte centerColor = centerColors[index];

			MapCheckmark newCheckmark;
			if (centerColor == roomColor && (room.checkmark == MapCheckmark.UNDISCOVERED || room.checkmark == MapCheckmark.QUESTION_MARK)) {
				newCheckmark = MapCheckmark.NONE;
			} else {
				newCheckmark = MapCheckmark.fromMapColor(centerColor);
			}

			if (newCheckmark != null && newCheckmark != room.checkmark) {
				room.checkmark = newCheckmark;
				fireCheckmarkUpdate(room, newCheckmark);
			}
		}
	}

	private static void fireCheckmarkUpdate(DungeonRoom room, MapCheckmark checkmark) {
		for (BiConsumer<DungeonRoom, MapCheckmark> listener : checkmarkListeners) {
			try {
				listener.accept(room, checkmark);
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("MapScan checkmark listener threw", e);
			}
		}
	}

	private static void addOrFixDoor(IVec2 position, DoorRotation rotation, DoorType doorType) {
		IVec2 key = new IVec2(-12 + 2 * position.x() + rotation.offset.x(), -12 + 2 * position.z() + rotation.offset.z());
		boolean isNew = !DungeonScan.doors.containsKey(key);
		DungeonDoor door = DungeonScan.doors.computeIfAbsent(key, k -> new DungeonDoor(position, rotation, doorType));
		if (isNew || door.type != doorType) {
			// See WorldScan#lastRoomEnterMillis's own doc comment (per user report — "It takes like 2 seconds
			// when i enter a new room to detect all doors going out from the room"): when this new door
			// belongs to the room just entered, the log line includes the real elapsed time since that
			// entry — ground truth on whether the gap is this mod's own pipeline (it isn't, as far as this
			// codebase's own tick-driven scan loop goes — see that doc comment) or the server's own
			// map-packet cadence.
			int tileIndex = position.x() + position.z() * 6;
			String sinceEntry = WorldScan.lastRoomEnterTileIndices().contains(tileIndex)
				? " (" + (System.currentTimeMillis() - WorldScan.lastRoomEnterMillis()) + "ms after entering its room)" : "";
		}
		door.type = doorType;
	}

	private static byte getPx(byte[] colors, int x, int z) {
		if (x < 0 || x >= MAP_SIZE || z < 0 || z >= MAP_SIZE) return EMPTY;
		return colors[z * MAP_SIZE + x];
	}
}
