package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.MimicFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds {@link MimicFeature} — the same real entity-event packet (id 3 = death animation) Odin's own
 *  Mimic.kt keys its Mimic-kill detection off, fired for the baby zombie a real Mimic disguises as. */
@Mixin(ClientPacketListener.class)
public class EntityEventMixin {
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void skyblocksimplified$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Level level = Minecraft.getInstance().level;
		if (level == null) return;
		Entity entity = packet.getEntity(level);
		if (entity != null) MimicFeature.onEntityEvent(entity, packet.getEventId());
	}
}
