package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows armor/ability "stack" counters (Wither Impact, etc.) from the action bar — ported from
 * SkyHanni's ArmorStackDisplay.kt, keeping its confirmed action-bar regex, with one change: action-bar
 * text is client-constructed Component text, and Component.getString() strips Style-based formatting
 * entirely (confirmed via decompiled source — see TreeProgressDisplayFeature's doc comment), so if
 * Hypixel sends the "§6"/"§6§l" coloring here via structured Style rather than literal characters,
 * getString() would never contain "§" and the original mandatory-§6 regex would silently never match.
 * The §-run is now optional (matches with or without codes) rather than required.
 */
public class ArmorStackDisplayFeature extends TabWidgetOverlayFeature {
	private static final Pattern ARMOR_STACK = Pattern.compile(" (?:§.)*(?<stack>\\d+[ᝐ⁑|҉Ѫ⚶])");

	private String display = null;

	public ArmorStackDisplayFeature() {
		super("armor_stack_display", "Armor Stack Display", FeatureCategory.COMBAT, null, 0.01f, 0.74f);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay || !isEnabled()) return;
			onActionBar(message.getString());
		});
	}

	private void onActionBar(String actionBar) {
		Matcher matcher = ARMOR_STACK.matcher(actionBar);
		List<String> stacks = new ArrayList<>();
		while (matcher.find()) {
			stacks.add("§6§l" + matcher.group("stack"));
		}
		display = stacks.isEmpty() ? null : String.join(" ", stacks);
	}

	@Override
	protected List<String> currentLines() {
		return display == null ? List.of() : List.of(display);
	}

	@Override
	public String getDescription() {
		return "Shows armor/ability stack counters (like Wither Impact stacks) pulled from the action bar.";
	}
}
