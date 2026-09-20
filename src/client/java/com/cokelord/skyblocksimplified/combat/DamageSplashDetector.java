package com.cokelord.skyblocksimplified.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.regex.Pattern;

/**
 * Detects Hypixel's fake "damage splash" ArmorStands (the floating damage-number entities spawned on
 * hit) — ported from SkyHanni's DamageIndicatorManager.isDamageSplash: a young (freshly spawned), alive,
 * named ArmorStand whose comma-stripped custom name fully matches the confirmed damage-number pattern.
 */
public final class DamageSplashDetector {
	private static final int MAX_TICK_AGE = 300;
	private static final Pattern DAMAGE_PATTERN = Pattern.compile("[✧✯]?(\\d+[⚔+✧❤♞☄✷ﬗ✯]*)");

	private DamageSplashDetector() {}

	public static boolean isDamageSplash(Entity entity) {
		if (!(entity instanceof ArmorStand)) return false;
		if (entity.tickCount > MAX_TICK_AGE) return false;
		if (!entity.hasCustomName()) return false;
		if (!entity.isAlive()) return false;
		Component customName = entity.getCustomName();
		if (customName == null) return false;
		String name = customName.getString().replace(",", "");
		return DAMAGE_PATTERN.matcher(name).matches();
	}
}
