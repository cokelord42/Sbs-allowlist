package com.cokelord.skyblocksimplified.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes KeyMapping's protected "key" field — the player's actual current binding, which can differ
 *  from getDefaultKey() if they've rebound it in the vanilla Controls screen. Used to seed a fresh
 *  Custom Keybinds slot with the player's real vanilla binding instead of leaving it unbound. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("key")
	InputConstants.Key skyblocksimplified$getKey();
}
