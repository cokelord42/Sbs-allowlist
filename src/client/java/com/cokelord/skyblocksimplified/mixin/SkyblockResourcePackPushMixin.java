package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.RemoveSkyblockTexturePackFeature;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remove Skyblock Texture Pack: swallows Hypixel's SkyBlock pack push and reports it as loaded, so nothing
 *  is downloaded or applied and the server doesn't kick for a declined required pack (Detexturify's own
 *  approach). Cancelled at HEAD, before the vanilla handler reschedules itself onto the main thread. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class SkyblockResourcePackPushMixin {
	@Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$blockSkyblockPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
		if (!RemoveSkyblockTexturePackFeature.shouldBlockPack(packet.url())) return;
		ci.cancel();
		ClientCommonPacketListenerImpl self = (ClientCommonPacketListenerImpl) (Object) this;
		self.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
		self.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
		RemoveSkyblockTexturePackFeature.onPackBlocked(packet.url());
	}
}
