package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Chat alert when your slayer boss gets cocooned (trapped, no more damage until it breaks free) —
 * ported from SkyHanni's SlayerCocoonWarning.kt, keeping its confirmed chat regex.
 */
public class SlayerCocoonAlertFeature extends Feature {
	// Plain text, no literal §-code requirement — Component#getString() (what this reads) already strips
	// all formatting, so the original §-prefixed pattern could never match anything.
	private static final Pattern COCOONED = Pattern.compile("\\s*YOU COCOONED YOUR SLAYER BOSS.*");

	public SlayerCocoonAlertFeature() {
		super("slayer_cocoon_alert", "Slayer Cocoon Alert", FeatureCategory.COMBAT, false);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !isEnabled()) return;
			if (COCOONED.matcher(message.getString()).matches()) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§lSlayer Boss Cocooned!"));
				}
			}
		});
	}

	@Override
	public String getSubcategory() {
		return "Slayers";
	}

	@Override
	public String getDescription() {
		return "Chat alert when your slayer boss gets cocooned (trapped and untargetable) mid-fight.";
	}
}
