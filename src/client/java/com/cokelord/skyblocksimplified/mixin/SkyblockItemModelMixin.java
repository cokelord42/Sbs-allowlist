package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Remove Skyblock Texture Pack: each of these three methods reads exactly one component, ITEM_MODEL
 *  (confirmed by decompiling 26.2's ItemModelResolver), so the single ItemStack#get call is swapped. */
@Mixin(ItemModelResolver.class)
public class SkyblockItemModelMixin {
	@ModifyExpressionValue(method = {"appendItemLayers", "shouldPlaySwapAnimation", "swapAnimationScale"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
	private Object skyblocksimplified$restoreItemModel(Object original, @Local(argsOnly = true) ItemStack stack) {
		return RemoveSkyblockTexturePackFeature.resolveModel(stack, (Identifier) original);
	}
}
