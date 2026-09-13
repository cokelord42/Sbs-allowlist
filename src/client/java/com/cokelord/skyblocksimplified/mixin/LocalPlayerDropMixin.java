package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.SlotLockingFeature;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per user request ("Locking an item means no dropping (by any means, protect it!)"): {@link
 * com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry}/{@code ContainerSlotClickMixin} only
 * ever sees clicks made INSIDE an open container screen (THROW there covers the Q/Ctrl+Q drop keys while
 * hovering a slot in a GUI) — pressing the same drop key with no screen open at all (dropping straight from
 * the hotbar while walking around) is a completely different vanilla code path, {@link
 * LocalPlayer#drop(boolean)}, never routed through a container click at all. Cancelling it here (instead of
 * a keybind-level intercept) is also immune to the currently-selected hotbar slot changing between the
 * key-down and whatever vanilla actually drops — this always reads the real item drop() is about to act on.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerDropMixin {
	@Inject(method = "drop", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$blockLockedDrop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		ItemStack held = self.getInventory().getSelectedItem();
		// Per user request ("allow users to 'drop' their currently held item when ult is ready, but only in
		// dungeons") — see SlotLockingFeature#isReadyToDropUltimate's own doc comment for the exact detection.
		if (SlotLockingFeature.isLocked(held) && !SlotLockingFeature.isReadyToDropUltimate(held)) {
			cir.setReturnValue(false);
		}
	}
}
