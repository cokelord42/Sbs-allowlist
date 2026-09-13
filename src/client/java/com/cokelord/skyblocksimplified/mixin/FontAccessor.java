package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@link Font}'s private glyph {@code provider} so a custom {@code Font} instance can be built
 *  that always resolves glyphs against a fixed font id regardless of the {@code Style} requesting them —
 *  see {@code ModFont} for why (applying the Quicksand font to just this mod's own GUI). */
@Mixin(Font.class)
public interface FontAccessor {
	@Accessor("provider")
	Font.Provider skyblocksimplified$getProvider();
}
