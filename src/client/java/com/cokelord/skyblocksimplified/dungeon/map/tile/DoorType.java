package com.cokelord.skyblocksimplified.dungeon.map.tile;

public enum DoorType {
	NORMAL, WITHER, BLOOD, FAIRY;

	public static DoorType fromColor(byte color) {
		if (color == (byte) 119) return WITHER;
		if (color == RoomType.BLOOD.getMapColor()) return BLOOD;
		if (color == RoomType.FAIRY.getMapColor()) return FAIRY;
		return NORMAL;
	}
}
