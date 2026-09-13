package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.keybind.CustomKeybindRegistry;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Backs the Custom Keybinds feature: substitutes CustomKeybindRegistry's captured combo state for
 *  vanilla's own bound-key state on the 8 remappable movement/action KeyMappings, when active. */
@Mixin(KeyMapping.class)
public class KeyMappingOverrideMixin {
	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$overrideIsDown(CallbackInfoReturnable<Boolean> cir) {
		Boolean override = CustomKeybindRegistry.overrideIsDown((KeyMapping) (Object) this);
		if (override != null) cir.setReturnValue(override);
	}

	@Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$overrideConsumeClick(CallbackInfoReturnable<Boolean> cir) {
		Boolean override = CustomKeybindRegistry.overrideConsumeClick((KeyMapping) (Object) this);
		if (override != null) cir.setReturnValue(override);
	}
}
