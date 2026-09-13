package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.dungeon.LobbyIdTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link LobbyIdTracker} — Fabric API has no stock event for
 *  {@code ClientboundSetPlayerTeamPacket}, same reasoning as {@link SetTimePacketMixin}. */
@Mixin(ClientPacketListener.class)
public class SetPlayerTeamPacketMixin {
	@Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
	private void skyblocksimplified$onSetPlayerTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
		packet.getParameters().ifPresent(params -> {
			String text = params.playerPrefix().getString() + params.playerSuffix().getString();
			LobbyIdTracker.onTeamPrefixSuffix(text);
		});
	}
}
