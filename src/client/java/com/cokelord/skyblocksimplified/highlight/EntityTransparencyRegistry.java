package com.cokelord.skyblocksimplified.highlight;

import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "render this entity at reduced alpha" rules — same shape as MobHighlightRegistry
 * and EntityHideRegistry, but for partial transparency instead of a highlight color or a full hide.
 * Consumed by EntityRendererMixin (which stashes the resolved alpha onto the render state at extraction
 * time) and LivingEntityAlphaMixin (which actually applies it during the submit/render-type pass).
 */
public final class EntityTransparencyRegistry {
	private EntityTransparencyRegistry() {}

	private static final Map<String, Rule> rules = new LinkedHashMap<>();

	private record Rule(Predicate<Entity> matcher, int alpha0to255) {}

	// Resolved alpha per entity for the current client tick (see TickCache).
	private static final TickCache.PerTick<Integer> cache = new TickCache.PerTick<>();

	public static void setRule(String id, Predicate<Entity> matcher, int alpha0to255) {
		rules.put(id, new Rule(matcher, alpha0to255));
		cache.clear();
	}

	public static void clearRule(String id) {
		if (rules.remove(id) != null) cache.clear();
	}

	/** 255 (fully opaque) if no rule matches this entity, otherwise the first matching rule's alpha. */
	public static int getAlphaFor(Entity entity) {
		if (rules.isEmpty()) return 255;
		Integer cached = cache.get(entity.getId());
		if (cached != null) return cached;
		int alpha = 255;
		for (Rule rule : rules.values()) {
			if (rule.matcher().test(entity)) { alpha = rule.alpha0to255(); break; }
		}
		cache.put(entity.getId(), alpha);
		return alpha;
	}
}
