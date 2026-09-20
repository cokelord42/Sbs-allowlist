package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Real bug found (per user report — "visual words doesnt show on the actionbar on hotbar swap"): Visual
 * Words only ever hooked {@code ClientReceiveMessageEvents.MODIFY_GAME} (real chat/system messages) and
 * {@code ItemTooltipCallback} (real inventory-screen tooltips) — neither ever fires for the vanilla action
 * bar. Per {@link PlayerDisplayActionBarMixin}'s own doc comment (confirmed by decompiling both callers),
 * {@link Hud#setOverlayMessage} is the one real choke point every action-bar update converges on: both
 * Hypixel's server-pushed {@code ClientboundSetActionBarTextPacket} text AND the entirely client-local
 * "show held item name" message fired when the player swaps their selected hotbar slot. Rewriting the
 * message here, instead of only observing it like that mixin does, covers both cases Visual Words is
 * supposed to reach without needing a separate hook per source.
 */
@Mixin(Hud.class)
public class VisualWordsActionBarMixin {
	@ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
	private Component skyblocksimplified$rewriteOverlayMessage(Component message) {
		return VisualWordsFeature.rewriteIfEnabled(message);
	}
}
