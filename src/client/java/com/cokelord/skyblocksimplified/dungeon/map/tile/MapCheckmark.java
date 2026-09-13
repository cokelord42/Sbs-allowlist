package com.cokelord.skyblocksimplified.dungeon.map.tile;

public enum MapCheckmark {
	NONE, WHITE, GREEN, RED, QUESTION_MARK, UNDISCOVERED;

	public static MapCheckmark fromMapColor(byte color) {
		return switch (color) {
			case 34 -> WHITE;
			case 30 -> GREEN;
			case 18 -> RED;
			case 119 -> QUESTION_MARK;
			default -> null;
		};
	}
}
