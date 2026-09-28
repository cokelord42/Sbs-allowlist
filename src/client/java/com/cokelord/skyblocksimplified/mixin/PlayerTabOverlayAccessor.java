package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Tab-list footer (where Hypixel lists the dungeon's active blessings). */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("footer")
	Component skyblocksimplified$getFooter();
}
