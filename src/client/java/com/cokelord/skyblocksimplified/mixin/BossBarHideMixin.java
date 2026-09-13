package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.BossBarFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link BossBarFeature}'s "Hide Vanilla Boss Bar" subtoggle suppress vanilla's own boss-bar draw
 *  once the mod's own custom readout is showing the same data — same direct-cancel pattern as {@code
 *  ExperienceBarHideMixin} (this render path has no HudElementRegistry hook to unregister instead). */
@Mixin(Hud.class)
public class BossBarHideMixin {
	@Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$hideBossBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		// Per user request ("The vanilla bossbar isnt hiding. I can see my objective at the top even outside
		// dungeons. 'Hide vanilla bossbar' should hide always."): reverted a previous round's isVisible()
		// gate (added for a different, now-superseded report about vanilla showing "in the regular dungeon
		// too") — the toggle is named "Hide Vanilla Boss Bar", and the user's explicit current spec is that
		// it means exactly that: whenever it's on, vanilla's own bar (Kuudra/event objective bars included,
		// not just dungeon boss fights) is always suppressed, independent of whether this feature's own
		// custom readout currently has anything to show in its place.
		if (FeatureRegistry.get("boss_bar") instanceof BossBarFeature feature && feature.isEnabled() && feature.isHideVanilla()) {
			ci.cancel();
		}
	}
}
