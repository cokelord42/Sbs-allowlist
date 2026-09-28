package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.QuiverDisplayFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real vanilla per-slot inventory sync packet — per user request ("Do the odin version, theirs works
 *  well"), ported from Odin's own confirmed {@code QuiverDisplay.kt}, which reads Hypixel's live "Arrows
 *  Remaining" lore update off this exact packet type rather than polling the inventory (see
 *  {@link QuiverDisplayFeature#onSetSlot} for the matching logic ported from it).
 *
 *  <p>Method name and packet accessors ({@code getContainerId()}/{@code getSlot()}/{@code getItem()}, not
 *  record-style) confirmed by decompiling this project's own real {@code minecraft-client.jar}/
 *  {@code minecraft-common.jar} (fabric-loom's mapped MC 26.2 artifacts) — no longer an unverified guess, so
 *  the earlier {@code require = 0} override of this project's {@code defaultRequire: 1} mixin policy has
 *  been removed. {@link QuiverDisplayFeature}'s own tick-based inventory scan is kept as a fallback
 *  regardless (see its own doc comment) in case a future MC update changes this packet's shape again. */
@Mixin(ClientPacketListener.class)
public class ContainerSetSlotPacketMixin {
	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
	private void skyblocksimplified$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		// Vanilla runs this handler twice (network thread, then re-queued on the main thread); only the
		// main-thread pass is real.
		if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return;
		QuiverDisplayFeature.onSetSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
	}
}
