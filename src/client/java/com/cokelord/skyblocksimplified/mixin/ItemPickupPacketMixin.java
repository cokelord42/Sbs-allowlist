package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.dungeon.DungeonBlockDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real vanilla item-pickup signal — the server sends this packet only when an entity actually walks over
 *  and collects a ground {@link ItemEntity}, never for a container-slot transfer (taking an item out of a
 *  chest GUI goes over a completely different packet path), so this structurally can't fire on "took it out
 *  of a chest" the way the old tick-based nearby-ItemEntity diff in DungeonBlockDetector could. Mirrors
 *  Devonian's own ClientPacketListenerMixin#devonian$itemPickupEventNormal, which hooks this exact same
 *  method for its ItemPickupEvent. Injected at HEAD, before vanilla shrinks/removes the item entity, so the
 *  stack read here is still the real pre-pickup stack. */
@Mixin(ClientPacketListener.class)
public class ItemPickupPacketMixin {
	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void skyblocksimplified$onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null || mc.player == null) return;
		Entity from = level.getEntity(packet.getItemId());
		if (!(from instanceof ItemEntity itemEntity)) return;
		LivingEntity to = level.getEntity(packet.getPlayerId()) instanceof LivingEntity living ? living : null;
		if (to == null) to = mc.player;
		if (to != mc.player) return;
		DungeonBlockDetector.onLocalPlayerItemPickup(itemEntity);
	}
}
