package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Makes {@link Minecraft}'s {@code public final Font font} field settable — needed so
 *  {@code GameWideFontOverride} can swap the game's real default font (used by every vanilla and mod
 *  screen, not just this mod's own menu) to one backed by a user-imported TTF, and restore it again on
 *  reset. Same {@code @Accessor} pattern as {@code FontAccessor}, just with {@code @Mutable} since this
 *  field is normally never reassigned after construction. */
@Mixin(Minecraft.class)
public interface MinecraftFontAccessor {
	@Accessor("font")
	@Mutable
	void skyblocksimplified$setFont(Font font);

	@Accessor("font")
	Font skyblocksimplified$getFont();
}
