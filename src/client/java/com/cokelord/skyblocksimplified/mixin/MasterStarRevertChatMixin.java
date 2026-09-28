package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.item.MasterStarRevert;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Per user report ("The revert master stars thing still does not work in lore when hovering chat, and still
 * doesnt work in the chat either"): {@link MasterStarRevert}'s own {@code ClientReceiveMessageEvents.MODIFY_GAME}
 * hook only ever sees real {@code ClientboundSystemChatPacket} traffic — per {@code VisualWordsFeature}'s own
 * class doc comment, Fabric has no equivalent hook for real signed PLAYER chat messages at all ("Fabric doesn't
 * offer rewriting its displayed content the way GAME/system messages allow"). Whatever chat line the user is
 * hitting this on may well be exactly that case. {@link ChatComponent#addClientSystemMessage},
 * {@link ChatComponent#addServerSystemMessage} and {@link ChatComponent#addPlayerMessage} are the three real
 * choke points EVERY chat line — system or signed player message alike — passes through right before it's
 * stored for display, so rewriting the Component here catches every case regardless of which packet type
 * carried it, no signature/packet-type awareness needed at all.
 */
@Mixin(ChatComponent.class)
public class MasterStarRevertChatMixin {
	@ModifyVariable(method = "addClientSystemMessage", at = @At("HEAD"), argsOnly = true)
	private Component skyblocksimplified$revertClientSystemMessage(Component message) {
		return MasterStarRevert.revertChatIfEnabled(message);
	}

	@ModifyVariable(method = "addServerSystemMessage", at = @At("HEAD"), argsOnly = true)
	private Component skyblocksimplified$revertServerSystemMessage(Component message) {
		return MasterStarRevert.revertChatIfEnabled(message);
	}

	@ModifyVariable(method = "addPlayerMessage", at = @At("HEAD"), argsOnly = true)
	private Component skyblocksimplified$revertPlayerMessage(Component message) {
		return MasterStarRevert.revertChatIfEnabled(message);
	}
}
