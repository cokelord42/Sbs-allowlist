package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.RealPingMonitor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link RealPingMonitor} — fires on every real ping/pong round trip, whether the request came from
 *  our own periodic ping or (if the F3 network chart happens to be open too) vanilla's own gated monitor. */
@Mixin(ClientPacketListener.class)
public class PongResponseMixin {
	@Inject(method = "handlePongResponse", at = @At("HEAD"))
	private void skyblocksimplified$onPongResponse(ClientboundPongResponsePacket packet, CallbackInfo ci) {
		RealPingMonitor.onPongReceived(packet.time());
	}
}
