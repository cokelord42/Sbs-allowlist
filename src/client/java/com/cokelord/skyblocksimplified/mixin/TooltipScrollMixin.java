package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.ScrollableTooltipsFeature;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Real single choke point for every tooltip variant (confirmed via {@code javap} on the real mapped
 * {@code GuiGraphicsExtractor} — every {@code setTooltipForNextFrame} overload, item/component/chat-hover
 * alike, funnels into this one deferred {@code tooltip(...)} call): wraps the ENTIRE real render in a pose
 * translate so {@link ScrollableTooltipsFeature} can scroll a long tooltip with the mouse wheel — see that
 * class's own doc comment for the full mechanism and why no scissor is needed.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipScrollMixin {
	@Inject(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At("HEAD")
	)
	private void skyblocksimplified$beginTooltipScroll(Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY, ClientTooltipPositioner positioner, Identifier id, CallbackInfo ci) {
		ScrollableTooltipsFeature.beginTooltip((GuiGraphicsExtractor) (Object) this, font, components);
	}

	@Inject(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At("RETURN")
	)
	private void skyblocksimplified$endTooltipScroll(Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY, ClientTooltipPositioner positioner, Identifier id, CallbackInfo ci) {
		ScrollableTooltipsFeature.endTooltip((GuiGraphicsExtractor) (Object) this);
	}
}
