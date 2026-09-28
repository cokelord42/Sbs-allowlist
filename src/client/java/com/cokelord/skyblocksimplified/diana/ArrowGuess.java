package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.ArrowGuessFeature;
import com.cokelord.skyblocksimplified.particle.ParticlePacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arrow guess: after digging a burrow in a chain, Hypixel draws a particle arrow (dust particles) pointing
 * toward the next burrow; its color encodes the distance band. This finds the arrow's shaft among recent
 * dust particles, casts a ray along it, and walks the ray through the Hub bounds to the grass blocks the
 * burrow can be on.
 *
 * <p>Ported from SBO's ArrowGuessBurrow.kt, whose logic SBO credits entirely to SidOfThe7Cs' SkyHanni PR
 * (hannibal002/SkyHanni#4916); the ray helpers are SkyHanni's RaycastUtils (also via SBO). Constants,
 * particle filter, distance bands and scoring are unchanged.
 */
public final class ArrowGuess {
	private static final int SHAFT_LENGTH = 20;
	private static final double PARTICLE_DETECTION_TOLERANCE = 0.12;
	private static final int COUNT_NEAR_TIP = 4;
	private static final int COUNT_NEAR_BASE = 2;
	private static final double EPSILON = 1e-6;
	private static final AABB HUB_BOUNDS = new AABB(-283, 60, -208, 175, 105, 205);

	private static final Set<Block> ALLOWED_ABOVE_GROUND = Set.of(
		Blocks.AIR, Blocks.DANDELION, Blocks.SPRUCE_FENCE, Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES,
		Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SUNFLOWER,
		Blocks.LILAC, Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.POPPY, Blocks.BLUE_ORCHID,
		Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP,
		Blocks.OXEYE_DAISY);

	private static final Set<Vec3> locations = new HashSet<>();
	/** Arrows already processed in the last 18s (SBO's TimeLimitedSet), keyed by rounded ray. */
	private static final Map<String, Long> recentArrows = new HashMap<>();
	/** Blocks the player started breaking in the last 4s — a just-dug burrow turns into air. */
	private static final Map<BlockPos, Long> recentClickedBlocks = new HashMap<>();
	static final List<GuessEntry> allGuesses = new ArrayList<>();

	private ArrowGuess() {}

	public static void clear() {
		locations.clear();
		recentArrows.clear();
		for (GuessEntry g : allGuesses) g.removeAllWaypoints();
		allGuesses.clear();
	}

	static void onBlockClicked(BlockPos pos) {
		recentClickedBlocks.put(pos.immutable(), System.currentTimeMillis());
	}

	static void onBurrowDug(int current, int max) {
		if (current != max) locations.clear();
	}

	// ---- chain of candidate blocks along one arrow --------------------------------------------------------

	static final class GuessEntry {
		final List<BlockPos> guesses;
		int currentIndex = 0;

		GuessEntry(List<BlockPos> guesses) { this.guesses = guesses; }

		BlockPos current() { return guesses.get(currentIndex); }

		List<BlockPos> remaining() { return guesses.subList(currentIndex + 1, guesses.size()); }

		boolean equivalentTo(GuessEntry other) {
			return guesses.subList(currentIndex, guesses.size()).equals(other.guesses.subList(other.currentIndex, other.guesses.size()));
		}

		void removeSubGuess(BlockPos pos) {
			int index = guesses.indexOf(pos);
			if (index <= currentIndex) return;
			guesses.remove(index);
			DianaWaypoints.removeAt(pos, DianaWaypoints.Type.SUBGUESS);
		}

		void removeAllWaypoints() {
			DianaWaypoints.removeAt(current(), DianaWaypoints.Type.ARROW);
			for (BlockPos p : remaining()) DianaWaypoints.removeAt(p, DianaWaypoints.Type.SUBGUESS);
		}

		boolean moveToNext() {
			DianaWaypoints.Waypoint wp = DianaWaypoints.getAt(current(), DianaWaypoints.Type.ARROW);
			if (wp != null) {
				// Interacted with: removed once the "dug out" message confirms it (2 digs), else immediately.
				if (wp.userInteracted) BurrowDetector.queueRemoval(wp, () -> wp.timesDug != 1);
				else DianaWaypoints.remove(wp);
			}
			int next = currentIndex + 1;
			if (next < guesses.size()) {
				currentIndex = next;
				DianaWaypoints.removeAt(guesses.get(next), DianaWaypoints.Type.SUBGUESS);
				addArrowGuess(guesses.get(next));
				return true;
			}
			return false;
		}
	}

	static void addArrowGuess(BlockPos pos) {
		if (DianaWaypoints.getAt(pos, DianaWaypoints.Type.ARROW) != null) return;
		DianaWaypoints.add("Guess", pos, DianaWaypoints.Type.ARROW, 1800);
	}

	private static void addSubGuess(BlockPos pos) {
		if (DianaWaypoints.getAt(pos, DianaWaypoints.Type.SUBGUESS) != null) return;
		DianaWaypoints.add(ArrowGuessFeature.subGuessText() ? "Possible" : "", pos, DianaWaypoints.Type.SUBGUESS, 1800);
	}

	static void removeArrowGuessFromSubGuess(BlockPos pos) {
		allGuesses.removeIf(entry -> {
			if (!entry.remaining().contains(pos)) return false;
			entry.removeAllWaypoints();
			return true;
		});
	}

	static void removeSubGuessFromInternalState(BlockPos pos) {
		for (GuessEntry entry : allGuesses) entry.removeSubGuess(pos);
	}

	static void removeFromInternalState(BlockPos pos) {
		allGuesses.removeIf(entry -> {
			if (!entry.current().equals(pos)) return false;
			entry.removeAllWaypoints();
			return true;
		});
	}

	static void removeOrMoveFromInternalState(BlockPos pos) {
		allGuesses.removeIf(entry -> {
			if (!entry.current().equals(pos)) return false;
			if (entry.moveToNext()) return false;
			entry.removeAllWaypoints();
			return true;
		});
	}

	// ---- particle intake ----------------------------------------------------------------------------------

	static void onParticle(ParticlePacketEvent event) {
		if (!ArrowGuessFeature.on() || !DianaState.inHub()) return;
		if (!(event.type() instanceof DustParticleOptions) || event.count() != 0 || event.maxSpeed() != 1.0f) return;
		Vec3 location = event.location();
		BlockPos lastClicked = DianaEvents.lastDigPos();
		if (lastClicked != null && location.distanceTo(DianaMath.vec(lastClicked)) > 7) return;
		int[] range = arrowRange(event.offset());
		if (range == null) return;
		// Bounded: detection is O(n^2) in stored particles, and stray dust near the last burrow shouldn't pile up.
		if (locations.size() > 400) locations.clear();
		locations.add(location);
		Ray ray = detectArrow();
		if (ray == null) return;
		String key = Math.round(ray.origin.x * 10) + "," + Math.round(ray.origin.y * 10) + "," + Math.round(ray.origin.z * 10)
			+ "|" + Math.round(ray.direction.x * 100) + "," + Math.round(ray.direction.y * 100) + "," + Math.round(ray.direction.z * 100);
		long now = System.currentTimeMillis();
		recentArrows.values().removeIf(t -> now - t > 18_000);
		if (recentArrows.putIfAbsent(key, now) != null) return;
		locations.clear();
		findCandidates(new DianaMath.Ray(ray.origin, ray.direction), range);
	}

	/** Particle "offset" is the arrow color here (count 0): yellow = close, red = medium, black = far. */
	private static int[] arrowRange(Vec3 offset) {
		float x = (float) offset.x, y = (float) offset.y, z = (float) offset.z;
		if (x == 0f && y == 128f && z == 0f) return new int[]{0, 117};
		if (x == 255f && y == 255f && z == 0f) return new int[]{112, 282};
		if (x == 255f && y == 0f && z == 0f) return new int[]{281, 600};
		return null;
	}

	private record Ray(Vec3 origin, Vec3 direction) {}

	private static Ray detectArrow() {
		List<Vec3> line = findLine();
		if (line.isEmpty()) return null;
		int count1 = pointsWithinTolerance(line.get(1));
		int count2 = pointsWithinTolerance(line.get(line.size() - 2));
		boolean baseFirst = count1 == COUNT_NEAR_BASE && count2 == COUNT_NEAR_TIP;
		boolean tipFirst = count1 == COUNT_NEAR_TIP && count2 == COUNT_NEAR_BASE;
		if (!baseFirst && !tipFirst) return null;
		Vec3 base = tipFirst ? line.get(line.size() - 1) : line.get(0);
		Vec3 tip = tipFirst ? line.get(0) : line.get(line.size() - 1);
		Vec3 adjustedBase = base.subtract(0, 1.5, 0);
		Vec3 adjustedTip = tip.subtract(0, 1.5, 0);
		Vec3 dir = adjustedTip.subtract(adjustedBase);
		if (dir.lengthSqr() < 1e-12) return null;
		return new Ray(adjustedBase, dir.normalize());
	}

	private static List<Vec3> findLine() {
		List<Vec3> bestLine = List.of();
		double bestScore = Double.POSITIVE_INFINITY;
		for (Vec3 start : locations) {
			List<Vec3> line = new ArrayList<>();
			Set<Vec3> visited = new HashSet<>();
			line.add(start);
			visited.add(start);
			if (!extendLine(line, visited)) continue;
			double score = scoreLine(line);
			if (score < bestScore || score == bestScore && line.size() > bestLine.size()) {
				bestScore = score;
				bestLine = line;
			}
		}
		return bestLine;
	}

	private static double scoreLine(List<Vec3> line) {
		if (line.size() < 2) return Double.POSITIVE_INFINITY;
		Vec3 origin = line.get(0);
		Vec3 direction = line.get(line.size() - 1).subtract(origin).normalize();
		double sum = 0;
		for (Vec3 point : line) {
			double projection = direction.dot(point.subtract(origin));
			sum += point.distanceTo(origin.add(direction.scale(projection)));
		}
		return sum;
	}

	private static boolean extendLine(List<Vec3> line, Set<Vec3> visited) {
		while (line.size() < SHAFT_LENGTH) {
			Vec3 next = null;
			double minDist = Double.MAX_VALUE;
			Vec3 last = line.get(line.size() - 1);
			Vec3 first = line.get(0);
			Vec3 second = line.size() > 1 ? line.get(1) : first;
			for (Vec3 location : locations) {
				if (visited.contains(location)) continue;
				double dist = last.distanceTo(location);
				if (dist > PARTICLE_DETECTION_TOLERANCE) continue;
				if (!collinear(first, second, location)) continue;
				if (dist < minDist) { minDist = dist; next = location; }
			}
			if (next == null) return false;
			line.add(next);
			visited.add(next);
		}
		return true;
	}

	private static boolean collinear(Vec3 a, Vec3 b, Vec3 c) {
		return b.subtract(a).cross(c.subtract(a)).lengthSqr() < EPSILON;
	}

	private static int pointsWithinTolerance(Vec3 origin) {
		double maxSq = PARTICLE_DETECTION_TOLERANCE * PARTICLE_DETECTION_TOLERANCE;
		int count = 0;
		for (Vec3 v : locations) if (!v.equals(origin) && v.distanceToSqr(origin) <= maxSq) count++;
		return count;
	}

	/** Walks the ray one block at a time along its dominant axis through the Hub bounds, scoring each valid
	 *  ground block by how close its center is to the ray (scaled by distance), and keeps every block tied for
	 *  the best score whose distance falls inside the arrow color's band. */
	private static void findCandidates(DianaMath.Ray ray, int[] range) {
		if (!insideHubBounds(ray.origin())) return;
		Vec3[] hit = DianaMath.intersectAabb(HUB_BOUNDS, ray);
		if (hit == null) return;
		Vec3 end = hit[1];
		Vec3 diff = end.subtract(ray.origin());
		int axis = -1;
		double minAbs = Double.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			double v = Math.abs(DianaMath.component(diff, i));
			if (v > 0.9 && v < minAbs) { minAbs = v; axis = i; }
		}
		if (axis < 0) return;

		Map<BlockPos, double[]> candidates = new LinkedHashMap<>();
		double originAxis = DianaMath.component(ray.origin(), axis);
		double sign = Math.signum(DianaMath.component(ray.direction(), axis));
		int iterations = (int) Math.abs(DianaMath.component(end, axis) - originAxis);
		for (int i = 1; i <= iterations; i++) {
			Vec3 point = DianaMath.pointOnRay(ray, axis, originAxis + i * sign);
			if (point == null) continue;
			BlockPos block = DianaMath.floor(point);
			if (!isBlockValid(block)) continue;
			Vec3 center = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
			double toRay = DianaMath.distanceToRay(ray, center);
			double fromOrigin = point.distanceTo(ray.origin());
			candidates.put(block, new double[]{toRay * 500000 / fromOrigin, fromOrigin});
		}
		if (candidates.isEmpty()) return;

		double bestScore = Double.MAX_VALUE, bestDist = Double.MAX_VALUE;
		for (double[] v : candidates.values()) {
			if (v[0] < bestScore || v[0] == bestScore && v[1] < bestDist) { bestScore = v[0]; bestDist = v[1]; }
		}
		List<BlockPos> within = new ArrayList<>();
		for (Map.Entry<BlockPos, double[]> e : candidates.entrySet()) {
			double[] v = e.getValue();
			int dist = (int) v[1];
			if (Math.abs(v[0] - bestScore) <= 1e-6 && dist >= range[0] && dist <= range[1]) within.add(e.getKey());
		}
		if (within.isEmpty()) return;
		GuessEntry entry = new GuessEntry(within);
		for (GuessEntry existing : allGuesses) if (existing.equivalentTo(entry)) return;
		allGuesses.add(entry);
		addArrowGuess(entry.current());
		if (ArrowGuessFeature.subGuesses()) for (BlockPos p : entry.remaining()) addSubGuess(p);
	}

	private static boolean insideHubBounds(Vec3 v) {
		return v.x > HUB_BOUNDS.minX && v.x <= HUB_BOUNDS.maxX && v.y > HUB_BOUNDS.minY && v.y <= HUB_BOUNDS.maxY
			&& v.z > HUB_BOUNDS.minZ && v.z <= HUB_BOUNDS.maxZ;
	}

	/** A burrow sits on a grass block (or a just-dug air block) with a passable block above it. Unloaded
	 *  chunks can't be checked, so they count as valid. */
	public static boolean isBlockValid(BlockPos pos) {
		if (!insideHubBounds(new Vec3(pos.getX(), pos.getY(), pos.getZ()))) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !mc.level.isLoaded(pos)) return true;
		Block block = mc.level.getBlockState(pos).getBlock();
		boolean ground = block == Blocks.GRASS_BLOCK || block == Blocks.AIR && recentlyClicked(pos);
		return ground && ALLOWED_ABOVE_GROUND.contains(mc.level.getBlockState(pos.above()).getBlock());
	}

	private static boolean recentlyClicked(BlockPos pos) {
		Long at = recentClickedBlocks.get(pos);
		return at != null && System.currentTimeMillis() - at <= 4000;
	}

	/** SBO's checkMoveGuess: skip past guesses that turned out invalid, or that the player walked within 30
	 *  blocks of (holding a spade) without a burrow being detected there. */
	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		recentClickedBlocks.values().removeIf(t -> now - t > 4000);
		if (!ArrowGuessFeature.on() || allGuesses.isEmpty() || mc.player == null || !DianaState.inHub()) return;
		boolean spade = DianaState.heldSpadeWithin(1000);
		Set<BlockPos> known = new HashSet<>();
		for (DianaWaypoints.Waypoint w : DianaWaypoints.ofType(DianaWaypoints.Type.BURROW)) known.add(w.pos);
		Vec3 player = mc.player.position();
		allGuesses.removeIf(guess -> {
			BlockPos current = guess.current();
			if (!isBlockValid(current)) return !guess.moveToNext();
			if (spade && !known.contains(current) && player.distanceToSqr(Vec3.atLowerCornerOf(current)) < 900) {
				return !guess.moveToNext();
			}
			return false;
		});
	}
}
