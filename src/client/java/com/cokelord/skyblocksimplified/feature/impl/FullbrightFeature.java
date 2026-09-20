package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;

/**
 * Full lighting regardless of the player's actual gamma setting. Originally tried via
 * {@code Minecraft.getInstance().options.gamma().set(1500)}, but {@code Options.gamma()} is an
 * {@code OptionInstance<Double>} whose value set ({@code UnitDouble}) rejects anything outside [0, 1] —
 * {@code .set()} silently fails validation and resets to the 0.5 default instead of applying, which is why
 * that approach visibly did nothing. See {@link com.cokelord.skyblocksimplified.mixin.FullbrightMixin},
 * which instead overrides the extracted {@code LightmapRenderState.brightness} field directly after the
 * real option is read — same value the shader ultimately uses, just past the option's own validation gate,
 * and it never touches (or corrupts) the player's actual gamma setting.
 */
public class FullbrightFeature extends Feature {
	public static final float FULLBRIGHT_BRIGHTNESS = 1500f;

	public FullbrightFeature() {
		super("performance_fullbright", "Fullbright", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	public String getDescription() {
		return "Makes everything fully lit regardless of your gamma/brightness setting, so dark areas are easy to see.";
	}
}
