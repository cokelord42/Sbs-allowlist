package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Remove Skyblock Texture Pack: supplies the pre-pack skin for SkyBlock heads sent without a profile. */
@Mixin(PlayerHeadSpecialRenderer.class)
public class SkyblockHeadProfileMixin {
	@ModifyExpressionValue(method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
	private Object skyblocksimplified$restoreHeadProfile(Object original, @Local(argsOnly = true) ItemStack stack) {
		return RemoveSkyblockTexturePackFeature.resolveProfile(stack, (ResolvableProfile) original);
	}
}
