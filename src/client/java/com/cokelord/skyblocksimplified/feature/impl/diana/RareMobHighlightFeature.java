package com.cokelord.skyblocksimplified.feature.impl.diana;

import com.cokelord.skyblocksimplified.diana.DianaState;
import com.cokelord.skyblocksimplified.diana.RareMobs;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Highlights rare Diana mobs through the shared mob-highlight pipeline (2D/3D render modes, occlusion,
 * tracers). Rare Diana mobs are player-model entities named after the mob — SBO's RareMobHighlight matches
 * Player entities whose UUID is not version 4 (i.e. not a real player) and whose name contains the mob name.
 */
public class RareMobHighlightFeature extends MobHighlightFeature {
	public RareMobHighlightFeature() {
		super("diana_rare_mob_highlight", "Highlight Rare Mobs", FeatureCategory.EVENTS, "Diana", 0xFFFFD700);
	}

	@Override
	protected boolean matches(Entity entity) {
		if (!(entity instanceof Player) || entity.getUUID().version() == 4 || entity.isInvisible()) return false;
		return RareMobs.Mob.fromName(entity.getName().getString()) != null && DianaState.inHub();
	}

	@Override
	public String getDescription() {
		return "Highlights Minos Inquisitors, King Minos, Manticores and Sphinxes using the mod's mob highlight renderer.";
	}
}
