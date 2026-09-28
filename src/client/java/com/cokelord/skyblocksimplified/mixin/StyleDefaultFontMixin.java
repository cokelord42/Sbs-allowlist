package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.CustomMenuFontFeature;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per user request ("make a toggle like we have before where it replaces the minecraft font entirely...
 * this will apply to the gui elements anyway cause they use the minecraft font"): a real global redirect of
 * vanilla's own default font, gated on {@link CustomMenuFontFeature#isReplaceMinecraftFontGlobally()} —
 * see that feature's own doc comment for the full reasoning and the tradeoff (this is a genuinely global
 * redirect with no way to scope it to just this mod's own GUI, unlike the mod menu/notification toast's own
 * MSDF rendering, which stays explicit and unaffected by this toggle either way).
 *
 * <p>{@code Style.getFont()} only ever returns either an explicitly-set font on that particular {@link Style},
 * or the shared {@link FontDescription#DEFAULT} constant when none was set — decompiling the real method body
 * confirms this (a single ternary, no other branches), so checking the return value against that exact
 * constant reliably distinguishes "this text asked for vanilla's own default" from "this text (or its source
 * resource pack) already picked a specific font on purpose," which this mixin leaves alone either way.
 */
@Mixin(Style.class)
public class StyleDefaultFontMixin {
	@Inject(method = "getFont", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$redirectDefaultFont(CallbackInfoReturnable<FontDescription> cir) {
		if (cir.getReturnValue() != FontDescription.DEFAULT) return;
		FontDescription replacement = CustomMenuFontFeature.globalReplacementFontOrNull();
		if (replacement != null) cir.setReturnValue(replacement);
	}
}
