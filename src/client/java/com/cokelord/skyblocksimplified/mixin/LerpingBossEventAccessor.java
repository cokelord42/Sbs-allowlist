package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code LerpingBossEvent}'s private {@code targetPercent} — the raw value the server actually
 *  sent, confirmed via {@code javap} against the MC 26.2 client jar ({@code getProgress()} instead returns
 *  the animated, smoothly-interpolating-toward-that-target value {@link BossHealthOverlayAccessor} reads
 *  for the plain HP readout). Per user request ("Watcher mobs left counter" — detect the EXACT percentage
 *  drop from one real kill, then self-correct when a later kill reveals an earlier reading was actually a
 *  multi-mob batch): the lerp animation means {@code getProgress()} keeps changing across several ticks
 *  after every real update, which would read as a series of small fake "drops" instead of one clean real
 *  one. {@code targetPercent} changes exactly once per real server update, giving an exact before/after
 *  pair to diff. */
@Mixin(LerpingBossEvent.class)
public interface LerpingBossEventAccessor {
	@Accessor("targetPercent")
	float skyblocksimplified$getTargetPercent();
}
