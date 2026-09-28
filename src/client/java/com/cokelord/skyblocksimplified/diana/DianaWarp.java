package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.DianaWarpFeature;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Picks the travel-scroll warp that gets closest to the current Diana target. Warp coordinates are SBO's
 * (WarpPoint.kt). Per user request, Wizard and Castle (both far above burrow height) compare distance
 * ignoring Y; the others use full 3D distance. Like SBO's getFinalClosestWarp, it simulates up to four hops
 * so it doesn't suggest a warp that would immediately suggest a different one on arrival.
 */
public final class DianaWarp {
	public enum Warp {
		HUB("hub", "Hub", 0.5, 77, -0.5, true, true, false),
		CASTLE("castle", "Castle", -250, 130, 45, false, true, true),
		WIZARD("wizard", "Wizard Tower", 44.5, 119, 93.5, false, true, true),
		CRYPT("crypt", "Crypt", -160.5, 62, -106.5, false, false, false),
		STONKS("stonks", "Stonks", -36.5, 70, -81.5, false, false, false),
		DA("da", "Dark Auction", 91.5, 75, 173.5, false, true, false),
		TAYLOR("taylor", "Taylor", 29.5, 73, -41.5, false, false, false),
		MUSEUM("museum", "Museum", 29.5, 72, 1.5, false, false, false);

		private final String command;
		private final String label;
		private final Vec3 pos;
		private final boolean alwaysAvailable;
		private final boolean enabledByDefault;
		private final boolean ignoreY;

		Warp(String command, String label, double x, double y, double z, boolean alwaysAvailable, boolean enabledByDefault, boolean ignoreY) {
			this.command = command;
			this.label = label;
			this.pos = new Vec3(x, y, z);
			this.alwaysAvailable = alwaysAvailable;
			this.enabledByDefault = enabledByDefault;
			this.ignoreY = ignoreY;
		}

		public String command() { return command; }
		public String label() { return label; }
		public boolean alwaysAvailable() { return alwaysAvailable; }
		public boolean enabledByDefault() { return enabledByDefault; }

		double distanceTo(Vec3 target) {
			if (!ignoreY) return pos.distanceTo(target);
			double dx = pos.x - target.x, dz = pos.z - target.z;
			return Math.sqrt(dx * dx + dz * dz);
		}
	}

	private static long lastWarpAt = 0;

	private DianaWarp() {}

	private static Vec3 center(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	/** Best warp from the player's position to {@code target}, or null when walking is as good or the
	 *  target is within the configured minimum distance. */
	public static Warp bestWarpFor(BlockPos target) {
		Minecraft mc = Minecraft.getInstance();
		DianaWarpFeature settings = DianaWarpFeature.get();
		if (mc.player == null || settings == null) return null;
		Vec3 player = mc.player.position();
		Vec3 goal = center(target);
		if (player.distanceTo(goal) < settings.integer("minDistance")) return null;
		Warp last = null;
		for (int hop = 0; hop < 4; hop++) {
			Warp warp = closestWarp(settings, player, goal);
			if (warp == null) return last;
			if (warp == last) return warp;
			last = warp;
			DianaWaypoints.Waypoint next = DianaWaypoints.bestGuessFrom(warp.pos);
			if (next == null) return warp;
			player = warp.pos;
			goal = center(next.pos);
		}
		return last;
	}

	private static Warp closestWarp(DianaWarpFeature settings, Vec3 player, Vec3 goal) {
		Warp best = null;
		double bestDist = Double.MAX_VALUE;
		for (Warp warp : Warp.values()) {
			if (!settings.warpAllowed(warp)) continue;
			double d = warp.distanceTo(goal);
			if (d < bestDist) { bestDist = d; best = warp; }
		}
		if (best == null) return null;
		// Only worth it if arriving at the warp leaves you closer than you already are.
		return bestDist < player.distanceTo(goal) ? best : null;
	}

	/** Warp key handler. */
	public static void warpToTarget() {
		Minecraft mc = Minecraft.getInstance();
		DianaWarpFeature settings = DianaWarpFeature.get();
		if (mc.player == null || settings == null || !DianaState.inHub() || !DianaState.hasSpade()) return;
		long now = System.currentTimeMillis();
		if (now - lastWarpAt < 500) return;

		DianaWaypoints.Waypoint target = null;
		if (settings.bool("preferRareMob")) target = DianaWaypoints.newestRareMob();
		if (target == null) target = DianaWaypoints.bestGuessFrom(mc.player.position());
		if (target == null) return;
		if (target.type != DianaWaypoints.Type.RARE_MOB && settings.bool("blockNearBurrow")
			&& BurrowDetector.burrowNearby(mc.player.position(), 60)) {
			ChatText.clientMessage("§b[SBS] §eNot warping: there's a burrow near you.");
			return;
		}
		Warp warp = bestWarpFor(target.pos);
		if (warp == null) return;
		lastWarpAt = now;
		ChatText.command("warp " + warp.command());
	}
}
