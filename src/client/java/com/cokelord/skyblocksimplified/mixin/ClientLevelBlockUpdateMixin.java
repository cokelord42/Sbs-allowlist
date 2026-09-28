package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server-sent block changes. Both ClientboundBlockUpdatePacket and ClientboundSectionBlocksUpdatePacket
 *  route every changed block through {@code setServerVerifiedBlockState} (confirmed by decompiling
 *  ClientPacketListener), while chunk loads do not — so this sees exactly the live, in-place changes.
 *  Injected at HEAD so the previous state is still readable (the callee only reads it after cheaper checks). */
@Mixin(ClientLevel.class)
public class ClientLevelBlockUpdateMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void skyblocksimplified$onServerBlockChange(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		DungeonTimersFeature.onServerBlockChange((ClientLevel) (Object) this, pos, state);
		com.cokelord.skyblocksimplified.dungeon.m7.M7Dragons.onBlockChange(pos, state);
	}
}
