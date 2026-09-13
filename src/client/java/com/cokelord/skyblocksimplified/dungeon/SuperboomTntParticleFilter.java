package com.cokelord.skyblocksimplified.dungeon;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature;
import com.cokelord.skyblocksimplified.particle.ParticleFilterRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the ambient FIREWORK/DUST particles a Superboom TNT prop constantly emits — a separate, positional
 * particle spawn, not the entity's own render (so "Hide Superboom TNT," which only skips rendering the
 * entity itself via EntityHideRegistry, left these visible on their own, reading as "the hider doesn't
 * work" even once the model itself was genuinely invisible). Confirmed real mechanism from SkyHanni's own
 * {@code DungeonHideItems.kt} (the user's supplied reference source): {@code onParticle} independently
 * cancels FIREWORK and DUST particles within 2 blocks of a matched Superboom TNT ArmorStand. Mirrors {@link
 * BlessingParticleFilter}'s own exact pattern (live per-tick position tracking + a positional particle
 * rule), reusing {@link DungeonDeclutterFeature}'s existing toggle rather than adding a new one.
 */
public final class SuperboomTntParticleFilter {
	private static final double TRACK_RANGE = 32.0;
	private static final double HIDE_RADIUS = 2.0;
	private static final String RULE_ID = "dungeon_hide_superboom_tnt_particles";
	private static List<Vec3> positions = List.of();
	private static boolean registered = false;

	private SuperboomTntParticleFilter() {}

	private static boolean enabled() {
		return FeatureRegistry.get("dungeon_declutter") instanceof DungeonDeclutterFeature f
			&& f.isEnabled() && f.isHideSuperboomTnt();
	}

	public static void register() {
		if (registered) return;
		registered = true;
		ParticleFilterRegistry.setPositionalRule(RULE_ID, (options, x, y, z) -> {
			if (!enabled()) return false;
			if (options.getType() != ParticleTypes.FIREWORK && options.getType() != ParticleTypes.DUST) return false;
			for (Vec3 pos : positions) {
				if (pos.distanceToSqr(x, y, z) <= HIDE_RADIUS * HIDE_RADIUS) return true;
			}
			return false;
		});
		// Same reasoning as BlessingParticleFilter's own doc comment on this exact try-catch: this and
		// every other raw END_CLIENT_TICK registration runs before FeatureRegistry::tickAll every frame, so
		// an uncaught throw here would silently skip every listener registered after it for that tick.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tick(client);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("SuperboomTntParticleFilter tick threw, skipping this tick", e);
			}
		});
	}

	private static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !enabled()) {
			positions = List.of();
			return;
		}
		AABB area = client.player.getBoundingBox().inflate(TRACK_RANGE);
		List<Vec3> found = new ArrayList<>();
		for (Entity entity : client.level.getEntities(client.player, area, DungeonObjectPredicates::isSuperboomTnt)) {
			found.add(entity.position());
		}
		positions = found;
	}
}
