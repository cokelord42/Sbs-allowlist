package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonObjectPredicates;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;

/** Hides the real Necron P5 tentacle ArmorStands (a custom-skull-textured decoration, not a distinct
 *  entity type) — per user request ("Add ... Hide P5 tentacles to the performance tab"), ported from
 *  NoammAddons' confirmed {@code RenderOptimizer.kt} "Hide P5 Tentacles" toggle. Detection reuses this
 *  codebase's own {@link DungeonObjectPredicates#isP5Tentacle} texture match (same real texture NoammAddons'
 *  own {@code TENTACLE_TEXTURE} constant uses) via {@link EntityHideRegistry} instead of NoammAddons'
 *  packet-cancellation approach — gated to F7 phase 5 the same way NoammAddons gates its own toggle, since
 *  the same skull texture is exclusive to that phase anyway but this avoids depending on that alone. */
public class HideP5TentaclesFeature extends Feature {
	private boolean registered = false;

	public HideP5TentaclesFeature() {
		super("performance_hide_p5_tentacles", "Hide P5 Tentacles", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityHideRegistry.setRule(getId(), entity ->
				DungeonState.getF7Phase() == DungeonState.F7Phase.P5 && DungeonObjectPredicates.isP5Tentacle(entity));
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			EntityHideRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getDescription() {
		return "Hides the decorative tentacle armor stands in the Necron P5 boss fight.";
	}
}
