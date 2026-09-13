package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature;
import com.cokelord.skyblocksimplified.highlight.ScreenRenderCancelRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the real vanilla terminal GUI's slots/labels/tooltip/carried-item render — extractRenderState's
 *  own call chain is extractContents() [labels/slot-highlights/slots/items] -> extractCarriedItem() ->
 *  extractTooltip() (confirmed by decompiling AbstractContainerScreen), so cancelling at HEAD skips every
 *  one of those — and draws Terminal Solver's own fully custom replacement panel instead, when its Custom
 *  Terminal GUI mode is active. Per user request ("steal their code and do everything their solver is
 *  doing"): this matches Odin's own proven CustomTermGui.kt/CustomGUIImpl.kt mechanism, a full cancel-and-
 *  replace at HIGHEST priority, not a paint-over.
 *
 *  <p>Real bug found and fixed separately (see {@link TerminalCustomGuiBackgroundMixin}): this does NOT
 *  cover the container's own background TEXTURE (the chest-shaped frame with visible empty slot outlines).
 *  That's drawn by an entirely different top-level call — {@code Screen#extractBackground}, invoked by
 *  {@code Screen#extractRenderStateWithTooltipAndSubtitles} BEFORE extractRenderState even runs, confirmed
 *  by decompiling {@code Screen} — this mixin never touches it. That's the real, previously-misdiagnosed
 *  source of the leftover chest-shaped slot grid visible behind the panel (guessed at the time to be
 *  something specific to termsim's bigger menu; it wasn't). */
@Mixin(AbstractContainerScreen.class)
public class TerminalCustomGuiRenderMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$replaceRenderForCustomTerminalGui(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
		if (TerminalSolverFeature.shouldReplaceRender(self)) {
			TerminalSolverFeature.renderReplacement(graphics, self);
			ci.cancel();
		} else if (ScreenRenderCancelRegistry.shouldCancel(self)) {
			// Per ScreenRenderCancelRegistry's own doc comment — a registered feature (e.g. Storage Overlay)
			// draws its own replacement panel independently (its own ScreenEvents.afterExtract hook, unlike
			// Terminal Solver's mixin-driven renderReplacement above), so this only needs to cancel the real
			// vanilla render, not also call anything here.
			ci.cancel();
		}
	}
}
