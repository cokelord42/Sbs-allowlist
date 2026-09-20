package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.keybind.CustomKeybindRegistry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scales mouse-look sensitivity while Custom Keybinds' own sensitivity override is active — approach and
 * exact wrap point (LocalPlayer.turn(DD) as invoked from MouseHandler.turnPlayer) confirmed against
 * SkyHanni's own MixinMouse.java, same version family. A multiplier of 0 means the camera doesn't turn
 * at all, matching the user's request. Doesn't touch the real Options sensitivity setting (which would
 * risk the scaled-down value getting persisted to options.txt if a save happened while active) — the
 * deltas are scaled in-flight instead.
 */
@Mixin(MouseHandler.class)
public class CustomKeybindsSensitivityMixin {
	@WrapOperation(
		method = "turnPlayer",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
	)
	private void skyblocksimplified$scaleSensitivity(LocalPlayer instance, double x, double y, Operation<Void> original) {
		float scale = CustomKeybindRegistry.sensitivityScale();
		original.call(instance, x * scale, y * scale);
	}
}
