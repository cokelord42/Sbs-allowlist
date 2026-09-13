package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.NoCursorResetFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Root cause (see NoCursorResetFeature's doc comment): MouseHandler.grabMouse()/releaseMouse() both snap
 * the cursor to the exact center of the window, and Hypixel's paginated menus commonly hop through a
 * null screen between two real screens, triggering an unwanted recenter. Ported directly from Odin's
 * {@code MouseHandlerMixin.java}: capture the pre-grab cursor position, then on the next releaseMouse (the
 * call that would otherwise recenter it) force it back to that captured position instead — but only while
 * a container screen was open recently (NoCursorResetFeature.shouldHookMouse()).
 */
@Mixin(MouseHandler.class)
public class NoCursorResetMixin {
	@Shadow
	private double xpos;
	@Shadow
	private double ypos;

	@Unique
	private double skyblocksimplified$beforeX;
	@Unique
	private double skyblocksimplified$beforeY;

	@Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D", ordinal = 0, opcode = Opcodes.PUTFIELD))
	private void skyblocksimplified$lockPos(CallbackInfo ci) {
		this.skyblocksimplified$beforeX = this.xpos;
		this.skyblocksimplified$beforeY = this.ypos;
	}

	@Inject(method = "releaseMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getWindow()Lcom/mojang/blaze3d/platform/Window;"))
	private void skyblocksimplified$correctCursorPosition(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		Feature feature = FeatureRegistry.get("dont_reset_cursor_between_inventories");
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> && feature instanceof NoCursorResetFeature ncr && ncr.shouldHookMouse()) {
			InputConstants.grabOrReleaseMouse(mc.getWindow(), InputConstants.CURSOR_NORMAL, this.skyblocksimplified$beforeX, this.skyblocksimplified$beforeY);
			this.xpos = this.skyblocksimplified$beforeX;
			this.ypos = this.skyblocksimplified$beforeY;
		}
	}
}
