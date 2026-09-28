package com.cokelord.skyblocksimplified.diana;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Math used by the burrow guessers: least-squares polynomial fitting (spade guess) and ray helpers (arrow
 *  guess). Ported from SBO's PolynomialFitter/Matrix and SkyHanni's RaycastUtils (via SBO). */
public final class DianaMath {
	private DianaMath() {}

	public record Ray(Vec3 origin, Vec3 direction) {}

	/** Least-squares fit of a degree-{@code degree} polynomial through (x, y) points. Returns coefficients
	 *  lowest order first, or null if the normal matrix is singular. */
	public static double[] fitPolynomial(double[] xs, double[] ys, int degree) {
		int n = degree + 1;
		double[][] ata = new double[n][n];
		double[] aty = new double[n];
		for (int k = 0; k < xs.length; k++) {
			double[] pow = new double[n];
			pow[0] = 1;
			for (int i = 1; i < n; i++) pow[i] = pow[i - 1] * xs[k];
			for (int i = 0; i < n; i++) {
				aty[i] += pow[i] * ys[k];
				for (int j = 0; j < n; j++) ata[i][j] += pow[i] * pow[j];
			}
		}
		return solve(ata, aty);
	}

	/** Gaussian elimination with partial pivoting. */
	private static double[] solve(double[][] a, double[] b) {
		int n = b.length;
		for (int col = 0; col < n; col++) {
			int pivot = col;
			for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
			if (Math.abs(a[pivot][col]) < 1e-12) return null;
			double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
			double tb = b[col]; b[col] = b[pivot]; b[pivot] = tb;
			for (int r = 0; r < n; r++) {
				if (r == col) continue;
				double f = a[r][col] / a[col][col];
				if (f == 0) continue;
				for (int c = col; c < n; c++) a[r][c] -= f * a[col][c];
				b[r] -= f * b[col];
			}
		}
		double[] x = new double[n];
		for (int i = 0; i < n; i++) x[i] = b[i] / a[i][i];
		return x;
	}

	public static double evaluate(double[] coefficients, double t) {
		double result = 0;
		for (int i = coefficients.length - 1; i >= 0; i--) result = result * t + coefficients[i];
		return result;
	}

	public static double evaluateDerivative(double[] coefficients, double t) {
		double result = 0;
		for (int i = coefficients.length - 1; i >= 1; i--) result = result * t + coefficients[i] * i;
		return result;
	}

	public static double component(Vec3 v, int axis) {
		return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
	}

	/** Entry/exit points of a ray through an AABB (slab method), or null if it misses. */
	public static Vec3[] intersectAabb(AABB box, Ray ray) {
		double[] min = {box.minX, box.minY, box.minZ};
		double[] max = {box.maxX, box.maxY, box.maxZ};
		double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			double o = component(ray.origin(), i), d = component(ray.direction(), i);
			if (Math.abs(d) < 1e-12) {
				if (o < min[i] || o > max[i]) return null;
			} else {
				double t1 = (min[i] - o) / d, t2 = (max[i] - o) / d;
				if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
				tmin = Math.max(tmin, t1);
				tmax = Math.min(tmax, t2);
				if (tmin > tmax) return null;
			}
		}
		return new Vec3[]{ray.origin().add(ray.direction().scale(tmin)), ray.origin().add(ray.direction().scale(tmax))};
	}

	/** Point on the ray whose {@code axis} coordinate equals {@code value}, or null if parallel. */
	public static Vec3 pointOnRay(Ray ray, int axis, double value) {
		double o = component(ray.origin(), axis), d = component(ray.direction(), axis);
		if (Math.abs(d) < 1e-12) return Math.abs(o - value) < 1e-12 ? ray.origin() : null;
		return ray.origin().add(ray.direction().scale((value - o) / d));
	}

	/** Distance from a point to the ray; {@link Double#MAX_VALUE} if the point is behind the origin. */
	public static double distanceToRay(Ray ray, Vec3 point) {
		double t = ray.direction().dot(point.subtract(ray.origin()));
		if (t < 0) return Double.MAX_VALUE;
		return ray.origin().add(ray.direction().scale(t)).distanceTo(point);
	}

	public static BlockPos floor(Vec3 v) {
		return BlockPos.containing(v.x, v.y, v.z);
	}

	public static Vec3 vec(BlockPos pos) {
		return new Vec3(pos.getX(), pos.getY(), pos.getZ());
	}
}
