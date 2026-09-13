package com.cokelord.skyblocksimplified.dungeon.map.tile;

public enum RoomRotation {
	NORTH(15, 15),
	SOUTH(-15, -15),
	WEST(15, -15),
	EAST(-15, 15);

	public final int dx;
	public final int dz;

	RoomRotation(int dx, int dz) {
		this.dx = dx;
		this.dz = dz;
	}
}
