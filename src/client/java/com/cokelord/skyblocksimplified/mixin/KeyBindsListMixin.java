package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.keybind.ModKeyCategory;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;

/** Real bug found (per user report — "Remove the keybinds from the Minecraft controls section, I don't
 *  want them there"): moving this mod's KeyMappings into their own {@link ModKeyCategory#MAIN} category
 *  (a prior round) only regrouped them within the vanilla Controls screen — they still showed up there,
 *  under their own heading, even though the mod already exposes the exact same binds inline in its own
 *  settings panels (see MainScreen's drawKeybindRows). Redirects the one array-clone call {@link
 *  KeyBindsList}'s constructor makes from {@code minecraft.options.keyMappings} — filtering out every
 *  KeyMapping registered under our category before the vanilla list ever builds its entries from it. The
 *  KeyMapping stays fully registered in {@code Options.keyMappings} itself (untouched), so it keeps
 *  working everywhere else (input handling, save/load, collision checks) — only this one screen's own
 *  locally-cloned copy is filtered. */
@Mixin(KeyBindsList.class)
public class KeyBindsListMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE",
		target = "Lorg/apache/commons/lang3/ArrayUtils;clone([Ljava/lang/Object;)[Ljava/lang/Object;"))
	private Object[] skyblocksimplified$hideModKeybinds(Object[] array) {
		return Arrays.stream(array)
			.filter(k -> !(k instanceof KeyMapping mapping) || mapping.getCategory() != ModKeyCategory.MAIN)
			.toArray(KeyMapping[]::new);
	}
}
