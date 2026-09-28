package com.cokelord.skyblocksimplified.gui;

/** A screen that drives the mod's own background blur (GuiAnimations blur slider) instead of vanilla's menu
 *  blur option — read by {@code GameRendererBlurMixin} at the real vanilla read site. */
public interface BlurringScreen {
	/** Blur level for this frame, 0-10 (0 = don't override vanilla). */
	int effectiveBlurAmount();
}
