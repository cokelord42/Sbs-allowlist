package com.cokelord.skyblocksimplified.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes ItemInHandRenderer's private main/off-hand "height" fields — these are the 0..1 smoothed
 *  swap-in progress values (0 the instant the held item changes, tweening to 1) that drive the item
 *  swapping animation. Forcing both straight to 1 skips that tween entirely.
 *
 *  Also exposes the tracked mainHandItem/offHandItem fields themselves: vanilla's own tick() only ever
 *  updates these to the newly-equipped stack once the corresponding height has tweened back down below
 *  0.1 (see ItemInHandRenderer.tick, the `if (mainHandHeight < 0.1F) mainHandItem = ...` gate) — pinning
 *  height at 1 forever (the old approach) meant that gate could never fire, so the rendered item just
 *  never updated at all on a real swap. Setting these fields directly mirrors vanilla's own
 *  shouldInstantlyReplaceVisibleItem fast path (used today for same-item stack-count changes), just
 *  applied unconditionally. */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
	@Accessor("mainHandHeight")
	void skyblocksimplified$setMainHandHeight(float value);

	@Accessor("oMainHandHeight")
	void skyblocksimplified$setOMainHandHeight(float value);

	@Accessor("offHandHeight")
	void skyblocksimplified$setOffHandHeight(float value);

	@Accessor("oOffHandHeight")
	void skyblocksimplified$setOOffHandHeight(float value);

	@Accessor("mainHandItem")
	void skyblocksimplified$setMainHandItem(ItemStack value);

	@Accessor("offHandItem")
	void skyblocksimplified$setOffHandItem(ItemStack value);

	// Per user report ("Disable Full Swing Animation" isn't "fully" cancelling the swing — the item still
	// visibly rotates in place): applyItemArmAttackTransform is the arm-rotation half of swingArm's two
	// independent transforms (the other, purely positional, half is already cancelled via the @Redirect on
	// PoseStack.translate in ItemInHandRendererMixin). It's private, so calling through to real vanilla
	// behavior when the toggle is off needs this invoker rather than a direct call from the mixin class.
	@Invoker("applyItemArmAttackTransform")
	void skyblocksimplified$callApplyItemArmAttackTransform(PoseStack poseStack, HumanoidArm arm, float swingProgress);
}
