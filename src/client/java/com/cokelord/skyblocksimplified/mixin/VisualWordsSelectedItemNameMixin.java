package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Real bug found (per user report — "visual words doesnt replace action bar tooltips when you swap
 *  items"): the vanilla "held item name" popup shown when swapping the selected hotbar slot (fading in/out
 *  over the tool-highlight timer) is NOT the same overlay {@link VisualWordsActionBarMixin} already hooks
 *  (real chat/system action-bar text, confirmed by that mixin's own doc comment to converge on
 *  {@code Hud#setOverlayMessage}) — it's an entirely separate render path, {@code Hud#extractSelectedItemName},
 *  confirmed via javap disassembly: it builds its own Component fresh every frame directly from
 *  {@code lastToolHighlight.getHoverName()}, never touching {@code setOverlayMessage} at all, so Visual
 *  Words' rewrite never got a chance to run on it. Redirected here at the exact same {@code
 *  ItemStack.getHoverName()} call every other vanilla tooltip/name render already makes. */
@Mixin(Hud.class)
public class VisualWordsSelectedItemNameMixin {
	@Redirect(
		method = "extractSelectedItemName",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;")
	)
	private Component skyblocksimplified$rewriteSelectedItemName(ItemStack stack) {
		return VisualWordsFeature.rewriteIfEnabled(stack.getHoverName());
	}
}
