package com.cokelord.skyblocksimplified.dungeon.map;

/** One pre-baked boss-room reference image + its real-world calibration, ported straight from BetterMap's
 *  {@code imageData.json}/{@code BossMapRenderer.js} (a ChatTriggers/JS 1.8.9 mod) — see {@code bossMaps.json}
 *  for the actual per-floor data this is deserialized from. {@code boundsMin}/{@code boundsMax} are the two
 *  real world-space corners (x, y, z) BetterMap's own {@code updateBossImage} checks the player's live position
 *  against on all 3 axes to pick which sub-stage image (floor 7 has several) is currently active. */
public class BossMapEntry {
	public String image;
	public double[] boundsMin;
	public double[] boundsMax;
	public double widthInWorld;
	public double heightInWorld;
	public double topLeftX;
	public double topLeftZ;
	public Double renderSize;
	public int imageWidth;
	public int imageHeight;

	/** Mirrors BetterMap's own {@code isBetween(number, min, max) = (number-min)*(number-max) <= 0} — order
	 *  independent, so boundsMin/boundsMax don't need to already be sorted per axis. */
	public boolean containsWorldPos(double x, double y, double z) {
		return isBetween(x, boundsMin[0], boundsMax[0])
			&& isBetween(y, boundsMin[1], boundsMax[1])
			&& isBetween(z, boundsMin[2], boundsMax[2]);
	}

	private static boolean isBetween(double n, double a, double b) {
		return (n - a) * (n - b) <= 0;
	}
}
