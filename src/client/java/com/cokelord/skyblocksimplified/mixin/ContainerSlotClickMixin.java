package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Single choke-point for every real container-slot click, before its packet is sent — lets features
 *  veto specific clicks (e.g. Experiment Addons' Prevent Misclicks) via ContainerClickRegistry instead
 *  of each needing its own mixin. Also handles the "upgrade to middle-click" side of that same registry
 *  (Terminal Solver's redirect toggle) and the "raw resend" side (same click, sent directly instead of
 *  through slotClicked's own body, to skip vanilla's local prediction — see
 *  ContainerClickRegistry#shouldRawResend's doc comment). */
@Mixin(AbstractContainerScreen.class)
public class ContainerSlotClickMixin {
	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$onSlotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type, CallbackInfo ci) {
		if (ContainerClickRegistry.shouldCancel(slot, slotId, mouseButton, type)) {
			ci.cancel();
			return;
		}
		ContainerClickRegistry.notifyAllowed(slot, slotId, mouseButton, type);
		if (ContainerClickRegistry.shouldUpgradeToMiddleClick(slot, slotId, mouseButton, type)) {
			ci.cancel();
			AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (mc.gameMode != null && player != null) {
				mc.gameMode.handleContainerInput(self.getMenu().containerId, slotId, 0, ContainerInput.CLONE, player);
			}
			return;
		}
		if (ContainerClickRegistry.shouldRawResend(slot, slotId, mouseButton, type)) {
			ci.cancel();
			AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (mc.gameMode != null && player != null) {
				mc.gameMode.handleContainerInput(self.getMenu().containerId, slotId, mouseButton, type, player);
			}
		}
	}
}
