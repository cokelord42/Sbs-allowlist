package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.PersonalCompactorOverlayFeature;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Personal Compactor/Deletor items are vanilla/reskinned items identified purely by NBT, not a custom Item
 * subclass, so the only interception point for supplying a custom tooltip image is here: getTooltipImage()
 * normally just delegates to getItem().getTooltipImage(this) (confirmed via javap), which vanilla items
 * never override to return anything. Cancelling with our own Optional here works for every call site that
 * queries it, matching how vanilla's own Bundle item supplies its BundleTooltip.
 */
@Mixin(ItemStack.class)
public class ItemStackTooltipImageMixin {
	@Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$personalCompactorTooltipImage(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		ItemStack self = (ItemStack) (Object) this;
		try {
			Optional<TooltipComponent> custom = PersonalCompactorOverlayFeature.tooltipImageFor(self);
			if (custom.isPresent()) cir.setReturnValue(custom);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Personal Compactor Overlay tooltip-image lookup failed, skipping this stack", e);
		}
	}
}
