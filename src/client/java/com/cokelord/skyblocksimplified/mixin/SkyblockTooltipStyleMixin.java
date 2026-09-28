package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Remove Skyblock Texture Pack: a tooltip styled with a pack sprite falls back to the vanilla tooltip. */
@Mixin(GuiGraphicsExtractor.class)
public class SkyblockTooltipStyleMixin {
	@ModifyVariable(method = "tooltip", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private Identifier skyblocksimplified$vanillaTooltip(Identifier style) {
		return RemoveSkyblockTexturePackFeature.resolveTooltipStyle(style);
	}
}
