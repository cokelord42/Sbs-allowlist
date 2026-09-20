package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Per user request: "Add a new feature to not render players during terminals. This is only client side
 * rendering and no cancelling interact packets or anything." Teammates crowding around the same terminal
 * grid (Waterboard/Simon Says/etc.) routinely block the view of the buttons/levers a solver needs to
 * highlight — this purely skips their model draw call via the same EntityHideRegistry/EntityHideMixin
 * "shouldRender" hook {@link EntityRenderDistanceFeature} already uses for its own render-only culling, so
 * teammates keep ticking, keep their real hitbox, and every interaction packet (clicking terminals,
 * leaping to them, etc.) is completely unaffected — only their visible model disappears while terminals
 * are active.
 */
public class HidePlayersDuringTerminalsFeature extends Feature {
	private boolean registered = false;

	public HidePlayersDuringTerminalsFeature() {
		super("performance_hide_players_terminals", "Hide Players During Terminals", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	public String getSubcategory() { return "Dungeons"; }

	@Override
	protected void onEnable() {
		if (registered) return;
		registered = true;
		EntityHideRegistry.setRule(getId(), this::shouldHide);
	}

	@Override
	protected void onDisable() {
		if (!registered) return;
		registered = false;
		EntityHideRegistry.clearRule(getId());
	}

	private boolean shouldHide(Entity entity) {
		if (!(entity instanceof Player)) return false;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || entity == player) return false;
		if (!DungeonState.isInDungeon()) return false;
		return DungeonState.getTerminalSection() != DungeonState.TerminalSection.NONE;
	}

	@Override
	public String getDescription() {
		return "Stops rendering other players while terminals are active, so they don't block the buttons/levers a solver highlights. Client-side rendering only — never blocks clicks or leaps.";
	}
}
