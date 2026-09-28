package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.item.MasterStarRevert;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Per user report ("Revert master stars doesnt work in the action bar tooltip when you swap items. Same
 * with the chat in lore and text. Still shows ... is holding [Spiritual Terminator ✪✪✪✪✪➍]."): the
 * tooltip fix and the {@code extractSelectedItemName} chain cover the item tooltip and the local
 * hotbar-swap popup, but Hypixel's "X is holding [item]" broadcast is a real candidate for arriving as a
 * server-pushed action bar text ({@code ClientboundSetActionBarTextPacket}) rather than a normal chat
 * message — per {@link VisualWordsActionBarMixin}'s own doc comment, that packet converges on
 * {@link Hud#setOverlayMessage} and is invisible to Fabric's {@code ClientReceiveMessageEvents} entirely.
 * A separate mixin class (rather than editing {@code VisualWordsActionBarMixin} directly) because Mixin's
 * {@code @ModifyVariable} — unlike {@code @Redirect} — explicitly supports multiple independent handlers
 * chaining on the same target, so this doesn't need to touch that unrelated feature's own code at all.
 */
@Mixin(Hud.class)
public class MasterStarRevertActionBarMixin {
	@ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
	private Component skyblocksimplified$revertOverlayMasterStars(Component message) {
		return MasterStarRevert.revertIfEnabled(message);
	}
}
