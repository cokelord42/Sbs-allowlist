package com.cokelord.skyblocksimplified.farming;

/** A detected farming row's bounds along whichever axis it runs on — ported from SkyHanni's
 *  FarmingLane/FarmingDirection. min/max are world coordinates along that axis (X for EAST_WEST,
 *  Z for NORTH_SOUTH). */
public record FarmingLane(Axis axis, double min, double max) {
	public enum Axis { NORTH_SOUTH, EAST_WEST }

	public double valueOf(double x, double z) {
		return axis == Axis.NORTH_SOUTH ? z : x;
	}
}
