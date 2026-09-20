package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityTransparencyRegistry;
import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * Generic "render any entity matching a predicate at reduced alpha" toggle — same shape as
 * EntityHideFeature but for partial transparency instead of a full hide, so a mob stays visible enough
 * to notice (e.g. so it doesn't block a doorway invisibly) without being distracting.
 */
public class EntityTransparencyFeature extends Feature {
	private static final int LOW_OPACITY_ALPHA = 55; // ~22% opacity: visible but clearly de-emphasized

	private final String subcategory;
	private final String slayerType;
	private final Predicate<Entity> matcher;
	private boolean registered = false;

	public EntityTransparencyFeature(String id, String displayName, FeatureCategory category, String subcategory, Predicate<Entity> matcher) {
		this(id, displayName, category, subcategory, null, matcher);
	}

	/** Same as the other constructor, but also tagged with a slayer type (General/Tarantula/Voidgloom/...)
	 *  so it shows up under Combat > Slayers' second-level tab row instead of just the plain subcategory. */
	public EntityTransparencyFeature(String id, String displayName, FeatureCategory category, String subcategory, String slayerType, Predicate<Entity> matcher) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.slayerType = slayerType;
		this.matcher = matcher;
	}

	@Override
	public String getSlayerType() {
		return slayerType;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			EntityTransparencyRegistry.setRule(getId(), matcher, LOW_OPACITY_ALPHA);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			EntityTransparencyRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getDescription() {
		return "Renders " + getDisplayName() + " at reduced opacity instead of hiding it completely.";
	}
}
