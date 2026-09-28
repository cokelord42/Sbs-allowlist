package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.gui.NotificationToastRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per user report ("The notification doesnt render above the gui background blackout. It should have like
 * the highest z level since the notifications are important"): the toast used to only ever draw via a normal
 * HUD-layer callback, which (per vanilla's own per-frame order) runs BEFORE any open Screen — meaning any
 * screen's own background darkening/blur painted OVER it the moment one was open. The mod menu screen worked
 * around this for itself specifically by calling {@link NotificationToastRenderer#renderOverlay} directly
 * after its own panel, but every OTHER screen (a chest GUI, a vanilla menu, anything) had the exact same
 * problem with no such workaround.
 *
 * <p>{@code Screen#extractRenderStateWithTooltipAndSubtitles} is the one real, {@code final} entry point
 * every screen's per-frame render funnels through (it calls the screen's own, overridable
 * {@code extractRenderState} first, then draws the tooltip/subtitle overlay) — injecting at its TAIL draws
 * the toast after literally everything else that screen (any screen) just drew, guaranteeing the "highest z
 * level" the user asked for regardless of which GUI happens to be open. {@link NotificationToastRenderer}'s
 * own HUD-layer hook now skips entirely whenever ANY screen is open (not just {@code MainScreen}), so this
 * mixin is the ONLY thing that draws/animates the toast in that case — no double-render, no double-speed
 * animation from firing twice in the same frame.
 */
@Mixin(Screen.class)
public class NotificationToastZOrderMixin {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
	private void skyblocksimplified$drawNotificationOnTop(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		NotificationToastRenderer.renderOverlay(graphics);
	}
}
