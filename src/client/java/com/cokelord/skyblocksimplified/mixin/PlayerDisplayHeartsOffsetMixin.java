package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Per user report: hiding the XP bar (see {@link ExperienceBarHideMixin}) left a "bunch of empty space"
 *  between the hotbar and hearts. Confirmed via decompiling {@code Hud.extractPlayerHealth}: the
 *  hearts/armor/food/air row baseline ({@code yLineBase = guiHeight() - 39}) is a fixed constant that
 *  always reserves room for the XP/contextual bar, completely independent of whether that bar actually
 *  renders — there's no Fabric hook for this baseline at all (same reasoning as ExperienceBarHideMixin's
 *  own doc comment for why it needs a direct mixin instead of the registry-replace pattern). Base value:
 *  the real vanilla hotbar height (22, confirmed in the same decompiled class — {@code extractItemHotbar}'s
 *  own {@code guiHeight() - 22}) plus one real heart row's sprite height (9, confirmed a few lines below
 *  this constant in the same method — the 9x9 armor/heart sprites) puts hearts flush against the hotbar's
 *  top edge. Per follow-up user report ("the vanilla hearts need to go up like 2 pixels"), nudged up 2px
 *  further from that flush baseline (33, not 31) — purely a visual preference on top of the confirmed
 *  flush-against-hotbar math above, not a second real vanilla constant. */
@Mixin(Hud.class)
public class PlayerDisplayHeartsOffsetMixin {
	@ModifyConstant(method = "extractPlayerHealth", constant = @Constant(intValue = 39))
	private int skyblocksimplified$tightenHeartsGapWhenXpHidden(int original) {
		if (FeatureRegistry.get("player_display") instanceof PlayerDisplayFeature feature && feature.isEnabled() && feature.isHideXp()) {
			return 33;
		}
		return original;
	}
}
