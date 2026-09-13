package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.gui.MainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Real fix for "the blur slider caps out at 1/10 no matter what it's set to" (task #472). Confirmed via
 * bytecode disassembly of the real client jar: {@code GameRenderer.extract()} calls {@code extractOptions()}
 * — which reads {@code Options.getMenuBackgroundBlurriness()} ONCE and caches it into this frame's
 * {@code OptionsRenderState} snapshot — BEFORE it calls {@code Gui.extractRenderState(...)}, which is what
 * actually runs {@code MainScreen.extractBackground()} further down the call chain. The old approach
 * (temporarily {@code option.set(level)}, call {@code extractBlurredBackground()}, then
 * {@code option.set(original)}) always ran strictly AFTER that snapshot had already been taken for the
 * frame — so whatever the mod's slider was set to never actually reached the real blur-radius value the
 * later render pass reads; what rendered was always just the player's own real, persisted vanilla "Menu
 * Background Blurriness" setting, snapshotted before the override ever touched it. That's exactly why the
 * effect looked capped at whatever fixed value that setting happened to be on, regardless of this mod's own
 * slider.
 *
 * <p>Fixed at the actual read site instead of by mutating the persistent option at all: redirecting the one
 * {@code Options.getMenuBackgroundBlurriness()} call inside {@code extractOptions()} to substitute this
 * mod's override value when the menu is open, so the correct value is already baked into the snapshot
 * before anything downstream reads it. This also means {@code MainScreen.extractBackground()} no longer
 * needs to touch the real option at all — see its own updated doc comment — so the previous
 * "flips your Graphics Preset to Custom" side effect is gone too.
 */
@Mixin(GameRenderer.class)
public class GameRendererBlurMixin {
	@Redirect(method = "extractOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getMenuBackgroundBlurriness()I"))
	private int skyblocksimplified$overrideMenuBlur(Options instance) {
		// Reads through MainScreen.effectiveBlurAmount() (not the slider directly) so this real vanilla read
		// site and MainScreen.extractBackground()'s own blur-pass trigger can never disagree — see that
		// method's doc comment for why Transparent panel theme forces a floor here too.
		if (Minecraft.getInstance().gui.screen() instanceof MainScreen && MainScreen.effectiveBlurAmount() >= 1) {
			return MainScreen.effectiveBlurAmount();
		}
		return instance.getMenuBackgroundBlurriness();
	}
}
