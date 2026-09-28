package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.feature.impl.diana.CloseBurrowDetectionFeature;
import com.cokelord.skyblocksimplified.particle.ParticlePacketEvent;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Close burrow detection and dig bookkeeping, ported from SBO's BurrowDetector.kt / ParticleTypes.kt.
 *
 * <p>Each burrow emits a signature particle every few ticks: enchanted-hit (Start), crit x3 (Mob),
 * dripping lava (Treasure), plus enchant and single-crit "footstep" particles. A large-smoke puff with no
 * spread marks a burrow that just vanished. The exact count/speed/spread values are SBO's.
 */
public final class BurrowDetector {
	private static final Pattern DEATH = Pattern.compile("^§c ☠ §7You .+$");
	private static final Pattern CHAIN_FINISHED = Pattern.compile("^§eYou finished the Griffin burrow chain!.*$");
	private static final Pattern DUG_OUT_NON_CHAIN = Pattern.compile(".*§eYou (?:just )?dug out(?!.*\\(\\d+/\\d+\\)$).*");

	static final class Burrow {
		final BlockPos pos;
		String type;
		DianaWaypoints.Waypoint waypoint;

		Burrow(BlockPos pos) { this.pos = pos; }
	}

	static final Map<BlockPos, Burrow> burrows = new HashMap<>();
	private static final Map<BlockPos, Long> recentlyRemoved = new HashMap<>();
	private static final Map<DianaWaypoints.Waypoint, BooleanSupplier> toRemove = new LinkedHashMap<>();
	private static BlockPos lastDugOutPos;

	private BurrowDetector() {}

	public static void clear() {
		burrows.clear();
		recentlyRemoved.clear();
		toRemove.clear();
	}

	static void removeFromInternalState(BlockPos pos) {
		burrows.remove(pos);
	}

	static void queueRemoval(DianaWaypoints.Waypoint waypoint, BooleanSupplier condition) {
		toRemove.put(waypoint, condition);
	}

	private static void markRecentlyRemoved(BlockPos pos) {
		recentlyRemoved.put(pos, System.currentTimeMillis());
	}

	private static boolean wasRecentlyRemoved(BlockPos pos) {
		Long at = recentlyRemoved.get(pos);
		if (at == null) return false;
		if (System.currentTimeMillis() - at <= 1000) return true;
		recentlyRemoved.remove(pos);
		return false;
	}

	// ---- particles ----------------------------------------------------------------------------------------

	private static boolean spread(ParticlePacketEvent e, double x, double y, double z) {
		return round2(e.offset().x) == x && round2(e.offset().y) == y && round2(e.offset().z) == z;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	static void onParticle(ParticlePacketEvent event) {
		if (!CloseBurrowDetectionFeature.on() || !DianaState.inHub()) return;
		ParticleType<?> type = event.type().getType();
		BlockPos pos = DianaMath.floor(event.location()).below();

		if (type == ParticleTypes.LARGE_SMOKE && event.maxSpeed() == 0.01f && event.offset().equals(Vec3.ZERO)) {
			markRecentlyRemoved(pos);
			burrows.remove(pos);
			DianaWaypoints.removeAllAt(pos);
			ArrowGuess.removeSubGuessFromInternalState(pos);
			ArrowGuess.removeOrMoveFromInternalState(pos);
			return;
		}

		String kind = classify(event, type);
		if (kind == null || wasRecentlyRemoved(pos)) return;
		switch (kind) {
			case "FOOTSTEP", "ENCHANT" -> burrows.computeIfAbsent(pos, Burrow::new);
			default -> registerBurrow(pos, kind, true, null);
		}
	}

	private static String classify(ParticlePacketEvent e, ParticleType<?> type) {
		int count = e.count();
		float speed = e.maxSpeed();
		if (type == ParticleTypes.ENCHANT && count == 5 && speed == 0.05f && spread(e, 0.5, 0.4, 0.5)) return "ENCHANT";
		if (type == ParticleTypes.ENCHANTED_HIT && count == 4 && speed == 0.01f && spread(e, 0.5, 0.1, 0.5)) return "Start";
		if (type == ParticleTypes.CRIT && count == 3 && speed == 0.01f && spread(e, 0.5, 0.1, 0.5)) return "Mob";
		if (type == ParticleTypes.DRIPPING_LAVA && count == 2 && speed == 0.01f && spread(e, 0.35, 0.1, 0.35)) return "Treasure";
		if (type == ParticleTypes.CRIT && count == 1 && speed == 0.0f && spread(e, 0.05, 0.0, 0.05)) return "FOOTSTEP";
		return null;
	}

	private static void registerBurrow(BlockPos pos, String type, boolean fromParticle, Integer carriedTimesDug) {
		if (!CloseBurrowDetectionFeature.on()) return;
		if (DianaWaypoints.getAt(pos, DianaWaypoints.Type.BURROW) != null) return;
		Burrow burrow = burrows.computeIfAbsent(pos, Burrow::new);
		burrow.type = type;
		ArrowGuess.removeFromInternalState(pos);

		int timesDug = carriedTimesDug != null ? carriedTimesDug : existingTimesDug(pos, burrow);
		if (burrow.waypoint != null) {
			burrow.waypoint.timesDug = timesDug;
			return;
		}
		DianaWaypoints.Waypoint waypoint = DianaWaypoints.add(type, pos, DianaWaypoints.Type.BURROW, 1800);
		waypoint.timesDug = timesDug;
		burrow.waypoint = waypoint;
		if (fromParticle) CloseBurrowDetectionFeature.playBurrowSound();
	}

	private static int existingTimesDug(BlockPos pos, Burrow burrow) {
		if (burrow.waypoint != null) return burrow.waypoint.timesDug;
		for (DianaWaypoints.Type t : new DianaWaypoints.Type[]{DianaWaypoints.Type.BURROW, DianaWaypoints.Type.ARROW,
			DianaWaypoints.Type.GUESS, DianaWaypoints.Type.SUBGUESS}) {
			DianaWaypoints.Waypoint w = DianaWaypoints.getAt(pos, t);
			if (w != null) return w.timesDug;
		}
		return 0;
	}

	// ---- chat ---------------------------------------------------------------------------------------------

	/** @param legacy the message with § codes (see ChatText.legacy). */
	static void onChat(String legacy) {
		if (DEATH.matcher(legacy).matches()) {
			if (DianaState.inHub()) refreshBurrows(true, 1, null);
		} else if (CHAIN_FINISHED.matcher(legacy).matches()) {
			lastDugOutPos = DianaEvents.lastWaypointClicked;
			refreshBurrows(false, 2, null);
		} else if (DUG_OUT_NON_CHAIN.matcher(legacy).matches()) {
			// Mob spawns, feather/coin drops and Myth the Fish don't send the "(x/y)" chain message.
			lastDugOutPos = DianaEvents.lastWaypointClicked;
			refreshBurrows(false, 1, typeFromChat(legacy));
		}
	}

	/** "(x/y)" chain message — see DianaEvents. */
	static void onBurrowDug() {
		if (!CloseBurrowDetectionFeature.on()) return;
		lastDugOutPos = DianaEvents.lastWaypointClicked;
		refreshBurrows(false, 2, null);
	}

	private static String typeFromChat(String message) {
		if (message.contains("Griffin Feather") || message.contains(" coins!") || message.contains("Mythos Fragment")
			|| message.contains("Myth the Fish")) return "Treasure";
		return "Mob";
	}

	/** SBO's refreshBurrows: advance the dig count of the waypoint the player last dug, removing it once
	 *  fully dug (Start: 1 dig; Mob/Treasure: 2), or when the player died to its mob. */
	private static void refreshBurrows(boolean deathOriginating, int expectedTimesDug, String burrowType) {
		BlockPos pos = lastDugOutPos;
		if (pos == null) {
			flushRemovals();
			return;
		}
		DianaWaypoints.Waypoint known = DianaWaypoints.getAt(pos, DianaWaypoints.Type.BURROW);
		DianaWaypoints.Waypoint dug = known;
		if (dug == null) dug = DianaWaypoints.getAt(pos, DianaWaypoints.Type.ARROW);
		if (dug == null) dug = DianaWaypoints.getAt(pos, DianaWaypoints.Type.GUESS);
		if (dug == null) dug = DianaWaypoints.getAt(pos, DianaWaypoints.Type.SUBGUESS);

		if (dug != null) {
			boolean start = "Start".equals(dug.text);
			boolean mob = "Mob".equals(dug.text) || "Mob".equals(burrowType);
			if (!deathOriginating && !start) dug.timesDug++;
			if (dug.timesDug != expectedTimesDug) dug.timesDug = expectedTimesDug;

			boolean death = deathOriginating && dug.timesDug >= 1 && mob;
			boolean remove = !deathOriginating && start || !deathOriginating && dug.timesDug >= 2 || death;
			if (remove) {
				if (death) ChatText.clientMessage("§b[SBS] §eRemoved Mob burrow waypoint since you died.");
				markRecentlyRemoved(dug.pos);
				if (known != null) burrows.remove(known.pos);
				ArrowGuess.removeArrowGuessFromSubGuess(dug.pos);
				ArrowGuess.removeFromInternalState(dug.pos);
				DianaWaypoints.remove(dug);
			}
		}

		flushRemovals();

		if (dug != null && dug.type != DianaWaypoints.Type.BURROW && dug.type != DianaWaypoints.Type.RARE_MOB
			&& dug.type != DianaWaypoints.Type.WORLD && burrowType != null) {
			// A guess was dug before particles identified it: promote it to a real burrow, keeping progress.
			registerBurrow(pos, burrowType, false, expectedTimesDug);
			markRecentlyRemoved(dug.pos);
			DianaWaypoints.remove(dug);
		}
	}

	private static void flushRemovals() {
		toRemove.entrySet().removeIf(e -> {
			if (!e.getValue().getAsBoolean()) return false;
			DianaWaypoints.remove(e.getKey());
			return true;
		});
	}

	/** Any detected (particle) burrow within {@code range} blocks of {@code from}. */
	public static boolean burrowNearby(Vec3 from, double range) {
		for (DianaWaypoints.Waypoint w : DianaWaypoints.ofType(DianaWaypoints.Type.BURROW)) {
			if (w.distanceTo(from) <= range) return true;
		}
		return false;
	}
}
