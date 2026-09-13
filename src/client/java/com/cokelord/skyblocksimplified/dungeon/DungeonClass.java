package com.cokelord.skyblocksimplified.dungeon;

/** Ported from Odin's {@code DungeonClass} enum. Colors are the classic Minecraft formatting-code colors
 *  Hypixel itself uses for each class (matching Odin's {@code Colors.MINECRAFT_*} constants), as plain
 *  ARGB per this codebase's convention (see {@link com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature}). */
public enum DungeonClass {
	ARCHER(0xFFFFAA00, 2),
	BERSERK(0xFFAA0000, 0),
	HEALER(0xFFFF55FF, 2),
	MAGE(0xFF55FFFF, 2),
	TANK(0xFF00AA00, 1),
	EMPTY(0xFFFFFFFF, 0);

	public final int color;
	public int priority;

	DungeonClass(int color, int priority) {
		this.color = color;
		this.priority = priority;
	}
}
