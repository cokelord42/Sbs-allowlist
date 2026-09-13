package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per user report, "Hide XP" only ever hid the level-number badge, not the green progress bar itself — the
 *  two are separate render paths in this MC version. {@code VanillaHudElements} only exposes
 *  {@code EXPERIENCE_LEVEL} (the badge); the bar is a {@code ContextualBar} implementation
 *  ({@link ExperienceBar}) rendered through a completely different, HudElementRegistry-inaccessible path
 *  (confirmed via decompiling this class — no Fabric hook references it at all), so hiding it needs a
 *  direct mixin instead of the registry-replace pattern every other hide toggle in this project uses. */
@Mixin(ExperienceBar.class)
public class ExperienceBarHideMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$hideXpBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (FeatureRegistry.get("player_display") instanceof PlayerDisplayFeature feature && feature.isEnabled() && feature.isHideXp()) {
			ci.cancel();
		}
	}
}
