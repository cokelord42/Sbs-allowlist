package com.cokelord.skyblocksimplified.highlight;

import net.minecraft.world.entity.Entity;

/** The 3D real-model glow-outline render mode was removed entirely for Hypixel PESTS specifically (see
 *  MobHighlightFeature.RenderMode's own doc comment — a pest's visible model is rigged onto an invisible
 *  armor stand, so vanilla's outline pass, which glows the ENTITY it's given, glows the invisible rig
 *  instead of the visible pest model; nothing to see).
 *
 * <p>Real bug found (per user report — "the teammate highlight doesnt highlight them" /
 * {@link com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature}'s "Highlight Party Members"):
 * this used to unconditionally return {@code false} for every entity, not just pests — since
 * EntityRendererMixin/EntityGlowingMixin gate the ENTIRE {@link MobHighlightRegistry} outline-color
 * mechanism behind this one check, that blanket stub silently killed every OTHER consumer of the same
 * shared registry too, PlayerGlowFeature's teammate highlighting included. Players were special-cased
 * back to {@code true} to fix that.
 *
 * <p>Later reverted (per user follow-up — "Hide player glow STILL isn't hiding glow on teammates. The
 * highlight should not make them glow, it should highlight them like a starred mob (2d box)"): rendering
 * teammates via the real 3D glow-outline pass is exactly what made "Hide Glow" and "Highlight Party
 * Members" visually indistinguishable/conflicting in the first place — a teammate highlight that IS a
 * glow can never coexist with a module whose entire job is suppressing glow. {@link
 * com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature}'s party-highlight rules now render as a
 * plain 2D screen-space box instead (see {@link com.cokelord.skyblocksimplified.highlight.HighlightBoxRenderer}'s
 * generic fallback path for rules with no owning {@code MobHighlightFeature}), the same visual language
 * every other highlight in this codebase already uses — so nothing needs the 3D pass anymore. */
public final class HighlightRenderModeUtil {
	private HighlightRenderModeUtil() {}

	public static boolean wants3dOutline(Entity entity) {
		return false;
	}
}
