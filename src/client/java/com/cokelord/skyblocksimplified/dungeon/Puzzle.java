package com.cokelord.skyblocksimplified.dungeon;

public enum Puzzle {
	UNKNOWN("???"),
	BLAZE("Higher Or Lower"),
	BEAMS("Creeper Beams"),
	WEIRDOS("Three Weirdos"),
	TTT("Tic Tac Toe"),
	WATER_BOARD("Water Board"),
	TP_MAZE("Teleport Maze"),
	BOULDER("Boulder"),
	ICE_FILL("Ice Fill"),
	ICE_PATH("Ice Path"),
	QUIZ("Quiz");

	public final String displayName;
	public PuzzleStatus status;
	public String player;

	Puzzle(String displayName) {
		this.displayName = displayName;
	}
}
