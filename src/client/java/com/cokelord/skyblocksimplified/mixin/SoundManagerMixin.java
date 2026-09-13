package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import com.cokelord.skyblocksimplified.sound.SoundPlayListenerRegistry;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SoundManager.play(SoundInstance) is the single choke point every client-side sound passes through
 * (entity sounds, block sounds, UI sounds, ambient sounds all funnel through here) — cancelling here
 * once covers every "mute X" toggle instead of needing a separate hook per sound-triggering system.
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$muteSounds(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		SoundPlayListenerRegistry.notifyPlayed(instance);
		if (SoundMuteRegistry.isMuted(instance)) {
			cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}
