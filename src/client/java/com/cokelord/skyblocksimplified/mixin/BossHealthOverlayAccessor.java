package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Exposes {@code BossHealthOverlay}'s private {@code events} map — the same {@code
 *  ClientboundBossEventPacket}-backed {@code LerpingBossEvent} instances vanilla's own boss bar renders
 *  from, confirmed via {@code javap} against the MC 26.2 client jar. Each entry's {@code getProgress()} is
 *  the real lerped HP fraction Hypixel sends for Catacombs/Kuudra bosses — the same signal Devonian's own
 *  boss-bar HP feature reads (its {@code BossHealthOverlayMixin} wraps this same map's iteration). */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
	@Accessor("events")
	Map<UUID, LerpingBossEvent> skyblocksimplified$getEvents();
}
