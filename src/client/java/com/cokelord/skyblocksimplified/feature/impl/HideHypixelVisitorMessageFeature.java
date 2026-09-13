package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Pattern;

/**
 * Hides Hypixel's own "new visitor arrived" chat line — ported from SkyHanni's GardenVisitorChat.kt
 * arrival regex, but with the literal §-code requirements stripped: SkyHanni reads that text through a
 * source that keeps legacy formatting codes inline, while Component#getString() (what
 * ClientReceiveMessageEvents hands us here) already strips all styling, so the original
 * ".* §r§ehas arrived on your §r§[ba]Garden§r§e!" pattern could never match plain text and the toggle
 * silently did nothing regardless of its state.
 */
public class HideHypixelVisitorMessageFeature extends Feature {
	private static final Pattern VISITOR_ARRIVED = Pattern.compile(".*has arrived on your Garden!");

	public HideHypixelVisitorMessageFeature() {
		super("visitor_hypixel_message", "Hide Hypixel New Visitor Message", FeatureCategory.FARMING, false);
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay || !isEnabled()) return true;
			return !VISITOR_ARRIVED.matcher(message.getString()).matches();
		});
	}

	@Override
	public String getSubcategory() {
		return "Visitors";
	}

	@Override
	public String getDescription() {
		return "Hides Hypixel's own \"a new visitor has arrived\" chat message in the Garden.";
	}
}
