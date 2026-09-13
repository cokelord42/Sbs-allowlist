package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.api.UpdateApi;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.AutoUpdateOnCloseFeature;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backs the Auto-Update On Close feature (per user request: "Add auto-update-on-close module (default
 * off)"). {@code Minecraft.stop()}'s real body is trivial — it only flips the {@code running} field to
 * false, which the main loop notices on its next iteration and only THEN begins the real shutdown/save/
 * destroy sequence — so cancelling this one call is enough to keep the game fully alive and running normally
 * while a background update downloads, with nothing else to unwind.
 *
 * <p>Intercepts the FIRST call to {@code stop()} this session (any quit path all route through this same
 * method — pause-menu Quit, window close, etc.) only while the feature is on AND {@link UpdateApi} reports an
 * update is currently {@code AVAILABLE}: cancels that call and starts the same download/verify/stage flow
 * the manual Update button already uses ({@link UpdateApi#startUpdate(Runnable)}), which — on success —
 * calls the real {@code stop()} itself once the new jar is staged (see {@code UpdateApi.applyDownloadedJar}).
 * {@code attempted} latches permanently true the instant this first interception happens, so: a second quit
 * click while the download is still in flight bypasses the mixin entirely and closes the game normally right
 * away (the player is never trapped needing to click twice), and — via the {@code onGiveUp} fallback passed
 * to {@code startUpdate} — a failed attempt (network error, checksum mismatch) still calls the real
 * {@code stop()} itself instead of silently leaving the game open with no obvious reason why.
 */
@Mixin(Minecraft.class)
public class MinecraftStopMixin {
	private static boolean attempted = false;

	@Inject(method = "stop", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$autoUpdateOnClose(CallbackInfo ci) {
		if (attempted) return;
		if (!(FeatureRegistry.get("auto_update_on_close") instanceof AutoUpdateOnCloseFeature f) || !f.isEnabled()) return;
		if (UpdateApi.getState() != UpdateApi.State.AVAILABLE) return;

		attempted = true;
		ci.cancel();
		UpdateApi.startUpdate(() -> Minecraft.getInstance().execute(Minecraft.getInstance()::stop));
	}
}
