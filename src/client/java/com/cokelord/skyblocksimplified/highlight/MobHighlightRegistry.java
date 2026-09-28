package com.cokelord.skyblocksimplified.highlight;

import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Central registry of "highlight this entity with this color" rules, consumed by
 * {@code EntityRendererMixin} to override the outline color vanilla already computes for glowing
 * entities (EntityRenderState.outlineColor, normally sourced from Entity.getTeamColor()). Reusing
 * vanilla's own outline render pass means no custom shader/geometry work is needed — every feature
 * that wants to highlight a mob just registers a rule here instead of touching rendering directly.
 *
 * Rules are checked in registration order; the first match wins. Register under a stable id per
 * feature (e.g. "slayer_miniboss") so that feature can freely replace or clear its own rule without
 * needing to know about anyone else's.
 */
public final class MobHighlightRegistry {
	private MobHighlightRegistry() {}

	// LinkedHashMap so iteration order matches registration order — "first match wins" depends on it.
	private static final Map<String, Rule> rules = new LinkedHashMap<>();

	/** {@code owner} is the {@link com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature} this
	 *  rule's render-mode/thickness/fill/tracer settings should come from — null for a rule with no such
	 *  feature to read from (drawn with fixed defaults, always 2D). Real bug found (per user report — "The
	 *  3d teammate highlight does not work. Its still 2d even on 3d mode"): a rule registered under a
	 *  synthetic id that isn't itself a real Feature id (e.g. HighlightPartyMembersFeature's "Color by Role"
	 *  mode, one rule per {@link com.cokelord.skyblocksimplified.dungeon.DungeonClass}) used to have NO way
	 *  to resolve back to the feature whose Render Mode/thickness/etc. it should actually use —
	 *  HighlightBoxRenderer only ever tried {@code FeatureRegistry.get(ruleId)}, which is null for those
	 *  synthetic ids, so they silently always fell through to the plain-2D-only fallback regardless of the
	 *  feature's own Render Mode setting. Storing the real owning feature directly on the Rule itself (set
	 *  explicitly by whoever registers it) fixes this without HighlightBoxRenderer needing to guess at any
	 *  naming convention between a rule id and its owner. */
	public record Rule(Predicate<Entity> matcher, int color,
						com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature owner) {}

	public static void setRule(String id, Predicate<Entity> matcher, int color) {
		setRule(id, matcher, color, null);
	}

	public static void setRule(String id, Predicate<Entity> matcher, int color,
								com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature owner) {
		rules.put(id, new Rule(matcher, color, owner));
		cache.clear();
	}

	public static void clearRule(String id) {
		rules.remove(id);
		cache.clear();
	}

	// Per-entity, per-tick result of the rule scan (see TickCache). NONE = "no rule matched".
	private static final TickCache.PerTick<Object> cache = new TickCache.PerTick<>();
	private static final Object NONE = new Object();

	@SuppressWarnings("unchecked")
	private static Map.Entry<String, Rule> cachedEntry(Entity entity) {
		if (rules.isEmpty()) return null;
		Object hit = cache.get(entity.getId());
		if (hit == null) {
			Map.Entry<String, Rule> found = null;
			for (Map.Entry<String, Rule> entry : rules.entrySet()) {
				if (entry.getValue().matcher().test(entity)) { found = entry; break; }
			}
			hit = found != null ? found : NONE;
			cache.put(entity.getId(), hit);
		}
		return hit == NONE ? null : (Map.Entry<String, Rule>) hit;
	}

	/** The ARGB color to outline this entity with, or 0 if no rule matches (no highlight). */
	public static int getColorFor(Entity entity) {
		Map.Entry<String, Rule> e = cachedEntry(entity);
		return e != null ? e.getValue().color() : 0;
	}

	/** The id of whichever rule matched, or null — used by HighlightBoxRenderer to look the owning
	 *  Feature back up (by id, via FeatureRegistry) so it can read that feature's own render-mode
	 *  settings instead of duplicating them here. */
	public static String getMatchedRuleId(Entity entity) {
		Map.Entry<String, Rule> e = cachedEntry(entity);
		return e != null ? e.getKey() : null;
	}

	/** The whole matched {@link Rule} (color + owning feature, if any) for this entity, or null if nothing
	 *  matches — see {@link #setRule(String, Predicate, int, com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature)}'s
	 *  own doc comment for why HighlightBoxRenderer reads the owner from here now instead of re-deriving it
	 *  from the rule id via FeatureRegistry. */
	public static Rule getMatchedRule(Entity entity) {
		Map.Entry<String, Rule> e = cachedEntry(entity);
		return e != null ? e.getValue() : null;
	}

	/** Both the matched rule id and its {@link Rule} in one pass — avoids scanning every registered rule's
	 *  predicate a second time just to also learn which id matched (HighlightBoxRenderer needs both: the id
	 *  for its own per-rule Y-offset tuning, the Rule for color/owning-feature). */
	public static Map.Entry<String, Rule> getMatchedEntry(Entity entity) {
		return cachedEntry(entity);
	}

	public static java.util.Set<String> ruleIds() {
		return rules.keySet();
	}
}
