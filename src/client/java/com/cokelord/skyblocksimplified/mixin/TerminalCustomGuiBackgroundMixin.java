package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature;
import com.cokelord.skyblocksimplified.highlight.ScreenRenderCancelRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Companion to {@link TerminalCustomGuiRenderMixin}, which only cancels AbstractContainerScreen's
 *  extractRenderState (slots/labels/tooltip). The container's own background TEXTURE — the chest-shaped
 *  frame with visible empty slot outlines, exactly what was still showing behind the panel per user report
 *  — is drawn by a completely separate top-level call, {@code Screen#extractBackground}, invoked by the
 *  screen manager BEFORE extractRenderState even runs (confirmed by decompiling {@code Screen}). Targets
 *  {@code ContainerScreen} specifically (not {@code AbstractContainerScreen}) because that's the class that
 *  actually overrides extractBackground with real texture-drawing logic — AbstractContainerScreen itself
 *  doesn't override it at all, so a mixin there would never apply to a ContainerScreen instance's own
 *  override. Both real Hypixel terminals and {@code /termsim} render as ContainerScreen (a plain
 *  chest-shaped GUI), so this covers both. */
@Mixin(ContainerScreen.class)
public class TerminalCustomGuiBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$cancelBackgroundForCustomTerminalGui(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (TerminalSolverFeature.shouldReplaceRender(self) || ScreenRenderCancelRegistry.shouldCancel(self)) {
			ci.cancel();
		}
	}
}
