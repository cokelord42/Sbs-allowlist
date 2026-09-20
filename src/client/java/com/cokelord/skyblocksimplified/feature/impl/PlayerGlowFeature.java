package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.world.entity.Entity;

/**
 * Hides the real vanilla/Hypixel-driven glow (outline) effect on other players — per user request, a
 * dedicated module for this. Its "Highlight Party Members" party-highlight half used to live here as a
 * subtoggle; per a later user request ("Make highlight party members into its own module instead of being
 * in the hide glow module. However, add the hide glow module as a subtoggle inside highlight party members
 * ASWELL so the hide glow we currently have links with the one in the teammate highlight. They are related,
 * but some people may use the hide glow for other stuff.") it's split out into its own
 * {@link HighlightPartyMembersFeature}, which reads/writes this feature's own enabled state through
 * {@link #getInstance()} for its own linked "Hide Glow" subtoggle — so toggling either one flips both.
 *
 * <p>Wired into {@code EntityGlowingMixin}/{@code EntityRendererMixin} (see {@link #shouldHideGlow}) the
 * same way every other real-glow suppression already works in this codebase.
 */
public class PlayerGlowFeature extends Feature {
	private static PlayerGlowFeature instance;

	public PlayerGlowFeature() {
		super("player_glow", "Hide Glow", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	public static PlayerGlowFeature getInstance() { return instance; }

	/** Called from {@code EntityGlowingMixin} — true when this entity's real (vanilla/Hypixel-driven) glow
	 *  should be suppressed. Applies to the local player too, per explicit user request — the mixin caller
	 *  handles that case separately (it can't go through {@link com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry},
	 *  which never matches the local player at all).
	 *
	 *  <p>Real bug found (per user report — "Hide player glow should hide on everything"): this used to
	 *  require {@code entity instanceof Player}, so a real Hypixel/vanilla glow on any non-player entity
	 *  (a glowing mob, a summoned pet, anything else the server or a status effect makes glow) was never
	 *  suppressed at all despite the feature otherwise being fully entity-agnostic — the mixin it feeds
	 *  already hooks {@code Entity.isCurrentlyGlowing()} directly, not a Player-specific method. Removed the
	 *  type restriction entirely.
	 *
	 *  <p>Second real bug found (per user report — "It still doesnt hide glow on all entities. It should
	 *  never show glow when on"): the mixin used to let ANY OTHER feature's own {@link com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry}
	 *  rule win over this one, so an entity that was BOTH really glowing AND separately targeted by some
	 *  unrelated highlight feature (e.g. a wither/starred-mob highlight) still showed its glow. Since the
	 *  user asked for an absolute "never show glow when on," the mixin no longer lets any matched highlight
	 *  rule save an already-glowing entity from suppression — moot for {@link HighlightPartyMembersFeature}'s
	 *  own rules either way, since those render through {@code HighlightBoxRenderer}'s own screen-space/
	 *  real-3D box drawing (see that class' own doc comment), never vanilla's glow-outline pass at all. */
	public static boolean shouldHideGlow(Entity entity) {
		return instance != null && instance.isEnabled();
	}

	@Override
	public String getDescription() {
		return "Hides the colored outline glow effect other players and mobs can have.";
	}
}
