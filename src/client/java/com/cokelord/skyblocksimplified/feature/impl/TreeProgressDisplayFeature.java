package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the current Foraging tree's break progress — ported from SkyHanni's TreeProgressDisplay.kt.
 * The progress readout is an ArmorStand nametag Hypixel spawns next to the tree being chopped, so this
 * scans nearby loaded entities each tick for one matching it.
 *
 * SkyHanni's confirmed real nametag is "§a§lFIG TREE §r§b§l88%", matched via a formatting-preserving text
 * accessor its own compat layer builds specifically for this. Verified via decompiled source that this
 * codebase's Component.getString() strips Style-based formatting entirely (visits only literal content,
 * ignoring Style) — but Hypixel's own text is commonly sent as literal §-prefixed characters embedded
 * directly in the string content rather than structured Style, in which case getString() would actually
 * preserve them untouched. Without live packet data there's no way to be certain which applies here, so
 * every §-run in the pattern is optional: it matches the nametag whether getString() hands back the fully
 * colored form or the plain "FIG TREE 88%" form.
 */
public class TreeProgressDisplayFeature extends TabWidgetOverlayFeature {
	private static final Pattern TREE_PROGRESS = Pattern.compile("(?<treeType>(?:§.)*\\w+) TREE (?:§.)*(?<percent>\\d+)%");

	private String display = null;

	public TreeProgressDisplayFeature() {
		super("tree_progress_display", "Tree Progress Display", FeatureCategory.FORAGING, "Foraging", 0.01f, 0.3f);
	}

	private int tickCounter = 0;

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.level == null) return;
		// A percentage readout doesn't need frame-perfect (20/sec) updates — scanning every entity in
		// render distance is the expensive part here, so cut that cost 4x by only doing it 5x/sec.
		if (tickCounter++ % 4 != 0) return;
		display = null;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand)) continue;
			Component customName = entity.getCustomName();
			if (customName == null) continue;
			Matcher matcher = TREE_PROGRESS.matcher(customName.getString());
			if (matcher.find()) {
				// "§e" is only a fallback default: if the tree-type capture already carries its own real
				// color codes (the non-stripped case), they immediately override it since later codes win.
				display = "§e" + matcher.group("treeType") + " §r§b§l" + matcher.group("percent") + "%";
				break;
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		return display == null ? List.of() : List.of(display);
	}

	@Override
	public String getDescription() {
		return "Shows the current Foraging tree's break progress.";
	}
}
