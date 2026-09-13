package com.cokelord.skyblocksimplified.dungeon.map.tile;

public enum RoomShape {
	L(3),
	ONE_BY_ONE(1),
	TWO_BY_ONE(2),
	THREE_BY_ONE(3),
	FOUR_BY_ONE(4),
	TWO_BY_TWO(4);

	private final int tileAmount;

	RoomShape(int tileAmount) {
		this.tileAmount = tileAmount;
	}

	public int getTileAmount() {
		return tileAmount;
	}

	/** Matches the room-database JSON's shape strings ("L", "1x1", "1x2", "1x3", "1x4", "2x2"). */
	public static RoomShape fromJson(String value) {
		return switch (value) {
			case "L" -> L;
			case "1x1" -> ONE_BY_ONE;
			case "1x2" -> TWO_BY_ONE;
			case "1x3" -> THREE_BY_ONE;
			case "1x4" -> FOUR_BY_ONE;
			case "2x2" -> TWO_BY_TWO;
			default -> throw new IllegalArgumentException("Unknown room shape: " + value);
		};
	}
}
