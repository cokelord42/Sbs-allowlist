package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.particle.ParticleFilterRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the ambient particles a dropped blessing constantly emits — a separate, server-driven spawn tied
 * to the item's world position, not the entity's own render (so "Hide Blessings", which only skips
 * rendering the item entity itself via EntityHideRegistry, left these visible on its own). Reuses that
 * same toggle rather than adding a new one, per "hide the particles under a dropped blessing AS WELL".
 * Tracks live blessing positions each tick (same tick-diff style as DungeonBlockDetector) and registers a
 * positional particle rule that hides anything spawning within HIDE_RADIUS of one.
 */
public final class BlessingParticleFilter {
	private static final double TRACK_RANGE = 32.0;
	private static final double HIDE_RADIUS = 1.5;
	private static final String RULE_ID = "dungeon_hide_blessings_particles";
	private static List<Vec3> blessingPositions = List.of();
	private static boolean registered = false;

	private BlessingParticleFilter() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ParticleFilterRegistry.setPositionalRule(RULE_ID, (options, x, y, z) -> {
			if (!(FeatureRegistry.get("dungeon_hide_blessings") instanceof com.cokelord.skyblocksimplified.feature.impl.EntityHideFeature f) || !f.isEnabled()) {
				return false;
			}
			for (Vec3 pos : blessingPositions) {
				if (pos.distanceToSqr(x, y, z) <= HIDE_RADIUS * HIDE_RADIUS) return true;
			}
			return false;
		});
		// Wrapped in try-catch, not left to propagate — this and every other raw END_CLIENT_TICK
		// registration in the mod run BEFORE FeatureRegistry::tickAll every frame (registration order:
		// this fires during feature construction in SkyblockSimplifiedClient, tickAll is registered last),
		// and Fabric's tick event dispatches listeners in sequence with no isolation between them. An
		// uncaught throw here would silently skip every listener registered after it for that whole
		// tick — including tickAll itself, which means EVERY toggleable feature's onTick (all the
		// farming HUD widgets among them) simply stops running for as long as the condition recurs. This
		// is the exact same failure class FeatureRegistry.tickAll's own per-feature try-catch fixed
		// internally, just at a sibling registration site that fix never covered.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick(client);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("BlessingParticleFilter tick threw, skipping this tick", e);
			}
		});
	}

	private static void tick(Minecraft client) {
		if (client.player == null || client.level == null
			|| !(FeatureRegistry.get("dungeon_hide_blessings") instanceof com.cokelord.skyblocksimplified.feature.impl.EntityHideFeature f) || !f.isEnabled()) {
			blessingPositions = List.of();
			return;
		}
		AABB area = client.player.getBoundingBox().inflate(TRACK_RANGE);
		List<Vec3> positions = new ArrayList<>();
		for (Entity entity : client.level.getEntities(client.player, area, DungeonObjectPredicates::isBlessing)) {
			positions.add(entity.position());
		}
		blessingPositions = positions;
	}
}
