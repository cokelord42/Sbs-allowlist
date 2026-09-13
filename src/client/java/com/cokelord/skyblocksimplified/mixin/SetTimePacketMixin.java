package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.TpsMonitor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link TpsMonitor} — the server sends this packet once per real server tick, so timing between
 *  consecutive packets is this codebase's only available signal for estimating server-side lag. */
@Mixin(ClientPacketListener.class)
public class SetTimePacketMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void skyblocksimplified$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		TpsMonitor.onSetTime(packet.gameTime());
	}
}
