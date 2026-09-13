package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import net.minecraft.client.resources.sounds.SoundInstance;

import java.util.function.Predicate;

/**
 * Generic "mute any sound matching a predicate" toggle — reused for every "mute X" module instead of a
 * near-identical class per sound. Matches against the whole SoundInstance (id, pitch, volume, position),
 * so it works for any real vanilla or resource-pack-remapped sound and for rules that need more than
 * just the sound id (e.g. mute_bugged_spade). Most call sites still just match on id (see the constructor
 * call sites for which ones are confirmed vanilla sounds vs. placeholders awaiting the real Hypixel sound ID).
 */
public class SoundMuteFeature extends Feature {
	private final String subcategory;
	private final Predicate<SoundInstance> matcher;
	private boolean registered = false;

	public SoundMuteFeature(String id, String displayName, FeatureCategory category, String subcategory, Predicate<SoundInstance> matcher) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.matcher = matcher;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			SoundMuteRegistry.setRule(getId(), matcher);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			SoundMuteRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getDescription() {
		return "Mutes the " + getDisplayName() + " sound.";
	}
}
