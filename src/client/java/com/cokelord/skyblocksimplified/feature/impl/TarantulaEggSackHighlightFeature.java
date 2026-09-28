package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.regex.Pattern;

/**
 * Per user request ("The egg sack highlight should only highlight the ones the slayer boss spawns. Make
 * it coordinate with the tarantula boss highlight... If the egg sacks are within 5 blocks horizontally of
 * the tarantula boss then it should render them"): a plain name-pattern match (the original, generic
 * {@code MobHighlightFeature} registration this replaced) matched EVERY egg sack shaped armor stand
 * anywhere near the player, including ones spawned by someone else's Tarantula fight nearby. This instead
 * requires a real Tarantula Broodfather/Conjoined Brood entity — the same names/vehicle-passenger handling
 * {@code tarantula_boss_highlight} itself matches, including that module's own "Only My Bosses" toggle —
 * within 5 blocks on the X/Z plane. Y is deliberately never checked: per the user, eggs always spawn well
 * above the boss, so a Y-inclusive radius would exclude the exact case this needs to catch.
 */
public class TarantulaEggSackHighlightFeature extends MobHighlightFeature {
	private static final Pattern EGG_SACK_NAME = Pattern.compile("\\d+s \\d+/\\d+");
	private static final double MAX_HORIZONTAL_DISTANCE = 5.0;

	public TarantulaEggSackHighlightFeature() {
		super("tarantula_egg_sack_highlight", "Highlight Egg Sacks", FeatureCategory.COMBAT, "Slayers", "Tarantula",
			name -> EGG_SACK_NAME.matcher(name).matches(), 0xFFAA00FF);
	}

	@Override
	protected boolean matches(Entity entity) {
		String name = entity.getName().getString();
		if (!EGG_SACK_NAME.matcher(name).matches()) return false;
		return isNearOwnTarantulaBoss(entity);
	}

	private boolean isNearOwnTarantulaBoss(Entity eggSack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		double x = eggSack.getX(), z = eggSack.getZ();
		// Wide open on Y (eggs spawn well above the boss) — only X/Z ever gate the match.
		AABB searchBox = new AABB(x - MAX_HORIZONTAL_DISTANCE, eggSack.getY() - 128, z - MAX_HORIZONTAL_DISTANCE,
			x + MAX_HORIZONTAL_DISTANCE, eggSack.getY() + 128, z + MAX_HORIZONTAL_DISTANCE);
		for (Entity candidate : mc.level.getEntities(eggSack, searchBox)) {
			if (!isTarantulaBoss(candidate)) continue;
			double dx = candidate.getX() - x, dz = candidate.getZ() - z;
			if (dx * dx + dz * dz > MAX_HORIZONTAL_DISTANCE * MAX_HORIZONTAL_DISTANCE) continue;
			if (isRequireOwnBoss() && !isSpawnedByLocalPlayer(candidate)) continue;
			return true;
		}
		return false;
	}

	private static boolean isTarantulaBoss(Entity entity) {
		if (isTarantulaBossName(entity.getName().getString())) return true;
		for (Entity passenger : entity.getPassengers()) {
			if (isTarantulaBossName(passenger.getName().getString())) return true;
		}
		return false;
	}

	private static boolean isTarantulaBossName(String name) {
		return name.contains("Tarantula Broodfather") || name.contains("Conjoined Brood");
	}
}
