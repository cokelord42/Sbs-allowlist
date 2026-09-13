package com.cokelord.skyblocksimplified.util.bezier;

import java.util.ArrayList;
import java.util.List;

/**
 * Least-squares polynomial fit (normal equations, solved via Gauss-Jordan elimination with partial
 * pivoting) — direct port of SkyHanni's PolynomialFitter.kt, minus its dependency on SkyHanni's own
 * generic Matrix utility (this solves the small (degree+1)x(degree+1) system directly instead).
 */
public final class PolynomialFitter {
	private final int degree;
	private final List<double[]> xRows = new ArrayList<>();
	private final List<Double> yValues = new ArrayList<>();

	public PolynomialFitter(int degree) {
		this.degree = degree;
	}

	public void addPoint(double x, double y) {
		double[] row = new double[degree + 1];
		double power = 1;
		for (int i = 0; i <= degree; i++) {
			row[i] = power;
			power *= x;
		}
		xRows.add(row);
		yValues.add(y);
	}

	/** Polynomial coefficients, lowest degree first (c[0] + c[1]*t + c[2]*t^2 + ...). */
	public double[] fit() {
		int n = degree + 1;
		double[][] xtx = new double[n][n];
		double[] xty = new double[n];
		for (int r = 0; r < xRows.size(); r++) {
			double[] row = xRows.get(r);
			double y = yValues.get(r);
			for (int i = 0; i < n; i++) {
				xty[i] += row[i] * y;
				for (int j = 0; j < n; j++) {
					xtx[i][j] += row[i] * row[j];
				}
			}
		}
		return solve(xtx, xty);
	}

	public void reset() {
		xRows.clear();
		yValues.clear();
	}

	private static double[] solve(double[][] a, double[] b) {
		int n = b.length;
		double[][] m = new double[n][n + 1];
		for (int i = 0; i < n; i++) {
			System.arraycopy(a[i], 0, m[i], 0, n);
			m[i][n] = b[i];
		}
		for (int col = 0; col < n; col++) {
			int pivot = col;
			for (int row = col + 1; row < n; row++) {
				if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) pivot = row;
			}
			double[] tmp = m[col];
			m[col] = m[pivot];
			m[pivot] = tmp;
			double div = m[col][col];
			// A near-singular pivot means too few/degenerate points for this degree — leave that
			// coefficient at 0 rather than dividing by ~0 and producing NaN/garbage.
			if (Math.abs(div) < 1e-9) continue;
			for (int k = col; k <= n; k++) m[col][k] /= div;
			for (int row = 0; row < n; row++) {
				if (row == col) continue;
				double factor = m[row][col];
				for (int k = col; k <= n; k++) m[row][k] -= factor * m[col][k];
			}
		}
		double[] result = new double[n];
		for (int i = 0; i < n; i++) result[i] = m[i][n];
		return result;
	}
}
