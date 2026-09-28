package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.HypixelFontPack;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/** Adds the cached Hypixel icon-font pack (see {@link HypixelFontPack}) as an extra client resource source —
 *  same hook point Detexturify uses for its cached Hypixel pack. */
@Mixin(Minecraft.class)
public class HypixelFontPackSourceMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"), index = 0)
	private static RepositorySource[] skyblocksimplified$addFontPackSource(RepositorySource[] sources) {
		RepositorySource[] result = Arrays.copyOf(sources, sources.length + 1);
		result[sources.length] = onLoad -> {
			Pack pack = HypixelFontPack.pack();
			if (pack != null) onLoad.accept(pack);
		};
		return result;
	}
}
