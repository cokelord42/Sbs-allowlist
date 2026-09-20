package com.cokelord.skyblocksimplified.mixin;

import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import net.minecraft.client.Options;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Real bug found (per user report — "the volume slider doesn't make custom/imported sounds any louder past
 * 100%"): {@link com.cokelord.skyblocksimplified.sound.CustomSoundOption}'s Volume slider intentionally
 * spans 0.0-2.0 (see that class's own doc comment — "volume and pitch sliders from 0-2, defaulted at 1"),
 * and its custom/imported-sound path already bakes a value above 1.0 directly into the PCM samples for a
 * real amplitude increase (see {@code CustomSoundOption.scaleVolume}). Its OTHER path — one of the feature's
 * built-in vanilla sound choices, played via {@code player.playSound(event, volume, pitch)} — instead
 * funnels into {@link SoundEngine}'s private {@code calculateVolume(float, SoundSource)}, confirmed by
 * decompiling this class: {@code Mth.clamp(volume, 0.0f, 1.0f) * Mth.clamp(finalSoundSourceVolume, 0.0f,
 * 1.0f) * gainBySource}. That FIRST clamp is on the SoundInstance's own volume — ours — and silently caps
 * it at 1.0 no matter what value is actually passed in, so a built-in sound plays identically whether the
 * slider reads 1.0 or 2.0. This is a real, vanilla-imposed ceiling (not something this mod's own code was
 * ever clamping), so working around it means intercepting this exact method rather than anything in
 * CustomSoundOption itself.
 *
 * <p>Recomputes the whole method at HEAD instead of {@code @Redirect}-ing the {@code Mth.clamp(float,float,
 * float)} call it shares with the category-volume factor a few tokens later in the same expression — the
 * identical call signature appears twice in this one method, and telling them apart would need a fragile
 * ordinal (see EndermanDeathFlopMixin's own doc comment on avoiding exactly that kind of disambiguation).
 * Only the per-instance ceiling this mod's slider feeds into is raised, to 2.0 to match the slider's own
 * declared max — the player's own in-game sound-category volume option (the second factor) is left exactly
 * as vanilla computes it, and every other sound in the game (which never requests an instance volume above
 * 1.0 in the first place) plays identically to before.
 */
@Mixin(SoundEngine.class)
public class SoundEngineVolumeMixin {
	@Shadow private Options options;
	@Shadow private Object2FloatMap<SoundSource> gainBySource;

	@Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$allowBoostedInstanceVolume(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
		float categoryVolume = Mth.clamp(this.options.getFinalSoundSourceVolume(source), 0.0f, 1.0f);
		float instanceVolume = Mth.clamp(volume, 0.0f, 2.0f);
		cir.setReturnValue(instanceVolume * categoryVolume * this.gainBySource.getFloat(source));
	}
}
