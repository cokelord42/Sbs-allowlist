package com.cokelord.skyblocksimplified.dungeon.map.tile;

import java.util.HashMap;
import java.util.Map;

public enum RoomType {
	ENTRANCE((byte) 30),
	FAIRY((byte) 82),
	NORMAL((byte) 63),
	RARE((byte) 63),
	BLOOD((byte) 18),
	CHAMPION((byte) 74),
	UNKNOWN((byte) 85),
	PUZZLE((byte) 66),
	TRAP((byte) 62),
	UNDISCOVERED((byte) -1);

	private final byte mapColor;
	private static final Map<Byte, RoomType> BY_COLOR = new HashMap<>();
	static {
		// Matches Kotlin's `entries.associateBy { }` semantics: later enum constants win on a
		// colliding key (NORMAL and RARE share mapColor 63 — RARE, declared after, wins the lookup).
		for (RoomType type : values()) BY_COLOR.put(type.mapColor, type);
	}

	RoomType(byte mapColor) {
		this.mapColor = mapColor;
	}

	public byte getMapColor() {
		return mapColor;
	}

	public static RoomType fromMapColor(byte color) {
		return BY_COLOR.get(color);
	}
}
