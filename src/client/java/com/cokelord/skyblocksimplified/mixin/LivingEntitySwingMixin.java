package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.AbilityCooldownTimerFeature;
import com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real left-click signal for {@link AbilityCooldownTimerFeature} and {@link GyroHelperFeature} — neither's
 *  right-click-only {@code ItemEvents.USE} trigger could ever catch a left-click ability on its own.
 *  {@code LivingEntity#swing} is the one real call every left-click action (attacking an entity, a block, or
 *  plain air) always funnels through client-side, both the 1-arg and 2-arg overloads included (the 1-arg one
 *  just delegates straight into this one) — a single hook point that needs no separate coverage for each of
 *  those three cases. */
@Mixin(LivingEntity.class)
public class LivingEntitySwingMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
	private void skyblocksimplified$onSwing(InteractionHand hand, boolean sendToSwingingEntity, CallbackInfo ci) {
		LocalPlayer localPlayer = Minecraft.getInstance().player;
		if ((LivingEntity) (Object) this != localPlayer) return;
		if (hand != InteractionHand.MAIN_HAND) return;
		AbilityCooldownTimerFeature.onLeftClickSwing();
		GyroHelperFeature.onLeftClickSwing();
	}
}
