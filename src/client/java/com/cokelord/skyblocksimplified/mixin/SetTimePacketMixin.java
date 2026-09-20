package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.TpsMonitor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link TpsMonitor} — timing between consecutive packets is this codebase's only available signal
 *  for estimating server-side lag. Real send cadence confirmed NOT to be a steady once-per-tick (~50ms): a
 *  round that assumed it was (bounding the estimate by time-since-last-packet) caused the live estimate to
 *  cycle wildly under completely normal play — see {@link TpsMonitor#getEstimatedTps()}'s own doc comment.
 *  Only the tickDelta/msDelta ratio between whatever packets DO arrive (this class's existing EMA) is a
 *  confirmed-safe way to use this signal; treating gaps between them as meaningful on their own is not. */
@Mixin(ClientPacketListener.class)
public class SetTimePacketMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void skyblocksimplified$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		TpsMonitor.onSetTime(packet.gameTime());
	}
}
