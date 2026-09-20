package com.cokelord.skyblocksimplified.dungeon.map.tile;

import com.cokelord.skyblocksimplified.dungeon.map.IVec2;

public class DungeonTile {
	public final IVec2 position;
	public DungeonRoom room;

	public DungeonTile(IVec2 position) {
		this.position = position;
	}
}
