package com.cokelord.skyblocksimplified.dungeon.map;

/** Integer 2D grid coordinate (x, z) — dungeon-map tile/chunk coordinates only, not world block
 *  coordinates. Ported from Odin's {@code IVec2} value class; a plain record here since the JVM already
 *  optimizes small immutable value-holders well and this project has no need for the packed-long trick. */
public record IVec2(int x, int z) {
	public int sortKey() {
		return x * 1000 + z;
	}

	public IVec2 plus(int scalar) {
		return new IVec2(x + scalar, z + scalar);
	}

	public IVec2 plus(IVec2 other) {
		return new IVec2(x + other.x, z + other.z);
	}

	public IVec2 divide(int scalar) {
		return new IVec2(Math.floorDiv(x, scalar), Math.floorDiv(z, scalar));
	}

	public IVec2 times(int scalar) {
		return new IVec2(x * scalar, z * scalar);
	}
}
