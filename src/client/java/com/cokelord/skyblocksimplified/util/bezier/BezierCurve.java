package com.cokelord.skyblocksimplified.util.bezier;

import net.minecraft.world.phys.Vec3;

/** A parametric 3D curve, one fitted polynomial per axis — port of SkyHanni's BezierCurve.kt. */
public final class BezierCurve {
	private final double[] cx, cy, cz;

	BezierCurve(double[] cx, double[] cy, double[] cz) {
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
	}

	public Vec3 at(double t) {
		return new Vec3(evalPoly(cx, t), evalPoly(cy, t), evalPoly(cz, t));
	}

	public Vec3 derivativeAt(double t) {
		return new Vec3(evalDerivative(cx, t), evalDerivative(cy, t), evalDerivative(cz, t));
	}

	private static double evalPoly(double[] c, double t) {
		double result = 0;
		for (int i = c.length - 1; i >= 0; i--) result = result * t + c[i];
		return result;
	}

	private static double evalDerivative(double[] c, double t) {
		double result = 0;
		for (int i = c.length - 1; i >= 1; i--) result = result * t + c[i] * i;
		return result;
	}
}
