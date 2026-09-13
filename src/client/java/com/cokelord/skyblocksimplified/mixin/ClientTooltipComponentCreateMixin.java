package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.ClientPersonalCompactorTooltip;
import com.cokelord.skyblocksimplified.feature.impl.PersonalCompactorTooltipData;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dispatch half of the same mechanism vanilla uses to turn a BundleTooltip into a ClientBundleTooltip
 * (confirmed via javap: create(TooltipComponent) is a plain instanceof-switch over known TooltipComponent
 * implementations, throwing IllegalArgumentException for anything unrecognized) — this recognizes our own
 * PersonalCompactorTooltipData and returns the matching client-side renderer before vanilla's own switch
 * ever gets a chance to throw on it. Explicit descriptor on "method" disambiguates from the sibling
 * create(FormattedCharSequence) overload.
 *
 * <p>Real crash found (user report — game wouldn't launch past preLaunch at all, misleadingly appearing to
 * collide with the Essential mod, which just happened to be the first entrypoint stage that triggers Mixin
 * class transformation): Mixin requires a mixin's own declared shape to match its target's — targeting an
 * <em>interface</em> (ClientTooltipComponent is one) with a mixin declared as a plain {@code class} throws
 * "@Mixin target type mismatch ... is an interface" at MixinApplyError/PREPARE time. This never surfaced at
 * compile time (the Mixin annotation processor validates the target method exists, not this shape match),
 * only at actual game launch. Fixed by declaring this mixin as an {@code interface} itself, matching
 * ClientTooltipComponent — {@code private static} methods with a body are valid in interfaces since Java 9,
 * so the injected method body is otherwise unchanged.
 */
@Mixin(ClientTooltipComponent.class)
public interface ClientTooltipComponentCreateMixin {
	@Inject(
		method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void skyblocksimplified$personalCompactorClientTooltip(TooltipComponent component, CallbackInfoReturnable<ClientTooltipComponent> cir) {
		if (component instanceof PersonalCompactorTooltipData data) {
			cir.setReturnValue(new ClientPersonalCompactorTooltip(data));
		}
	}
}
