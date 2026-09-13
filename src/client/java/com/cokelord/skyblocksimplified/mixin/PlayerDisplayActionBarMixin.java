package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real bug found (per user report — "Player Display health/mana/defense/overflow mana lags ~10s behind
 *  the real values"): PlayerDisplayFeature used to parse stats purely off Fabric's
 *  {@code ClientReceiveMessageEvents.GAME} event. Decompiling fabric-message-api-v1's own
 *  ChatListenerMixin shows that event only fires from {@code ChatListener#handleOverlay} — i.e. only for
 *  the {@code ClientboundSystemChatPacket(overlay=true)} delivery path. But decompiling
 *  {@code ClientPacketListener#setActionBarText} (the OTHER vanilla packet a server can push actionbar
 *  text through, {@code ClientboundSetActionBarTextPacket}) shows it calls
 *  {@code minecraft.gui.hud.setOverlayMessage(...)} DIRECTLY, never touching ChatListener at all — so
 *  Fabric's event, and therefore this mod, never saw that packet. Hypixel's continuously-refreshed
 *  health/mana/defense line rides that second path essentially every tick; the mod was only catching
 *  whatever occasional real system-chat-overlay message happened to also carry the stat line, which is
 *  exactly the "updates every ~10s instead of instantly" symptom reported.
 *
 *  <p>{@link Hud#setOverlayMessage} is the one real choke point both packet paths converge on (confirmed
 *  by decompiling both callers) — hooking it directly here, instead of patching the Fabric event further,
 *  catches every actionbar update regardless of which packet type carried it. */
@Mixin(Hud.class)
public class PlayerDisplayActionBarMixin {
	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void skyblocksimplified$onOverlayMessage(Component string, boolean animate, CallbackInfo ci) {
		PlayerDisplayFeature.onOverlayMessage(string);
	}
}
