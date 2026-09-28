package com.cokelord.skyblocksimplified.highlight;

import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "don't render this entity at all" rules, consumed by {@code EntityHideMixin}
 * (hooking EntityRenderer.shouldRender) — mirrors MobHighlightRegistry's shape but for full hiding
 * instead of outline-coloring. Multiple features can share the exact same underlying rule id (e.g. a
 * combat-wide and a slayer-specific "hide damage splash" toggle both flip the same detection on/off)
 * since any single matching rule is enough to hide an entity.
 */
public final class EntityHideRegistry {
	private EntityHideRegistry() {}

	private static final Map<String, Predicate<Entity>> rules = new LinkedHashMap<>();

	public static void setRule(String id, Predicate<Entity> matcher) {
		rules.put(id, matcher);
		cache.clear();
	}

	public static void clearRule(String id) {
		rules.remove(id);
		cache.clear();
	}

	// shouldHide runs for every entity every frame (EntityRenderer#shouldRender); cached per tick (TickCache).
	private static final TickCache.PerTick<Boolean> cache = new TickCache.PerTick<>();

	public static boolean shouldHide(Entity entity) {
		if (rules.isEmpty()) return false;
		Boolean hit = cache.get(entity.getId());
		if (hit != null) return hit;
		boolean hide = false;
		for (Predicate<Entity> matcher : rules.values()) {
			if (matcher.test(entity)) { hide = true; break; }
		}
		cache.put(entity.getId(), hide);
		return hide;
	}
}
