package com.cokelord.skyblocksimplified.dungeon.map.tile;

import com.cokelord.skyblocksimplified.dungeon.map.IVec2;

public class DungeonDoor {
	public final IVec2 position;
	public final DoorRotation rotation;
	public DoorType type;
	/** ARGB, defaults to opaque white (matches Odin's default door color before {@code DungeonScan}
	 *  recolors it per-type in {@code updateViewableDoors}). */
	public int color = 0xFFFFFFFF;

	public final int originTileIndex;
	public final int destinationTileIndex;
	public final int worldX;
	public final int worldZ;

	public DungeonDoor(IVec2 position, DoorRotation rotation, DoorType type) {
		this.position = position;
		this.rotation = rotation;
		this.type = type;
		this.originTileIndex = position.x() + position.z() * 6;
		this.destinationTileIndex = (position.x() + rotation.offset.x()) + (position.z() + rotation.offset.z()) * 6;
		this.worldX = (position.x() - 6) * 32 + 7 + rotation.offset.x() * 16;
		this.worldZ = (position.z() - 6) * 32 + 7 + rotation.offset.z() * 16;
	}
}
