package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Remove Minecraft Font Shading.
 *
 * <p>Real bug found (per user report — "Remove Font shading module doesn't work in chat"): the previous
 * version of this mixin targeted {@code GuiGraphicsExtractor.text(...)}'s {@code FormattedCharSequence}
 * overload, on the theory that every text-drawing call funnels through it — true for plain GUI/HUD text,
 * but chat lines render through a separate, newer path ({@code ActiveTextCollector.accept(...)}, this MC
 * version's own batched/scrolling text collector) that never calls that method at all, so chat text kept
 * its shadow regardless of this toggle. {@link GuiTextRenderState} is the one place BOTH paths actually
 * converge — every prepared line of text in the game, chat included, ends up constructing one of these
 * immutable render-state records right before submission — so forcing its {@code dropShadow} constructor
 * argument false here is a genuine single choke point covering vanilla HUD/GUI text, chat, and this mod's
 * own GUI text alike. */
@Mixin(GuiTextRenderState.class)
public class TextShadowMixin {
	// Must be static: this injects into <init> before the super() call, where `this` isn't constructed yet —
	// Mixin rejects a non-static @ModifyVariable handler at that position with "handler before super()
	// invocation must be static" (real crash found: this caused a hard MixinApplyError on every launch,
	// failing to load the whole mod).
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static boolean skyblocksimplified$stripShadow(boolean dropShadow) {
		Feature feature = FeatureRegistry.get("remove_font_shadow");
		return feature != null && feature.isEnabled() ? false : dropShadow;
	}
}
