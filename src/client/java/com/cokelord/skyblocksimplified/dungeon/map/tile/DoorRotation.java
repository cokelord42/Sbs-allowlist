package com.cokelord.skyblocksimplified.dungeon.map.tile;

import com.cokelord.skyblocksimplified.dungeon.map.IVec2;

public enum DoorRotation {
	HORIZONTAL(new IVec2(1, 0)),
	VERTICAL(new IVec2(0, 1));

	public final IVec2 offset;

	DoorRotation(IVec2 offset) {
		this.offset = offset;
	}
}
