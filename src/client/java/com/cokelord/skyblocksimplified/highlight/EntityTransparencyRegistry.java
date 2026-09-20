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

	public static void setRule(String id, Predicate<Entity> matcher, int alpha0to255) {
		rules.put(id, new Rule(matcher, alpha0to255));
	}

	public static void clearRule(String id) {
		rules.remove(id);
	}

	/** 255 (fully opaque) if no rule matches this entity, otherwise the first matching rule's alpha. */
	public static int getAlphaFor(Entity entity) {
		for (Rule rule : rules.values()) {
			if (rule.matcher().test(entity)) return rule.alpha0to255();
		}
		return 255;
	}
}
