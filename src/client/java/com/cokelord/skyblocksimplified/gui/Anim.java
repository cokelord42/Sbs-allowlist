package com.cokelord.skyblocksimplified.gui;

/**
 * A float that exponentially approaches a target each frame: value += (target - value) * (1 - e^(-rate*dt)),
 * where rate is derived from a configurable duration (5/duration ~= time to visually settle). This is a
 * real exponential curve (not an eased tween) and naturally handles the target changing mid-flight (e.g.
 * hover on/off, open/close), since it's just continuously chasing wherever the target currently is.
 *
 * durationSeconds <= 0, or the global instant flag, both collapse every update to an immediate snap —
 * this is the mechanism behind the GUI Animations feature's per-animation duration sliders and its
 * master "disable animations" toggle (see GuiAnimationsFeature).
 */
public final class Anim {
	private static volatile boolean globalInstant = false;

	public static void setGlobalInstant(boolean instant) {
		globalInstant = instant;
	}

	public static boolean isGlobalInstant() {
		return globalInstant;
	}

	private float value;
	private float previous;
	private float target;
	private float durationSeconds;

	Anim(float initial, float durationSeconds) {
		this.value = initial;
		this.previous = initial;
		this.target = initial;
		this.durationSeconds = durationSeconds;
	}

	void setDurationSeconds(float durationSeconds) {
		this.durationSeconds = Math.max(0f, durationSeconds);
	}

	void setTarget(float target) {
		this.target = target;
	}

	/** Instantly resets value/previous/target together, with no animation — for state resets (e.g.
	 *  switching which panel's settings are expanded) where an animated chase to the new value would
	 *  look like a glitch rather than an intentional cut. */
	void snapTo(float value) {
		this.value = value;
		this.previous = value;
		this.target = value;
	}

	float getTarget() {
		return target;
	}

	void update(float dtSeconds) {
		updateInternal(dtSeconds, globalInstant);
	}

	/** Same chase as {@link #update(float)}, but ignores the static global-instant flag entirely — for a
	 *  caller that has already folded whatever "should this animate" decision it needs (including its own
	 *  read of {@link #isGlobalInstant()}) into {@code durationSeconds} itself. See MainScreen's
	 *  toggleAnimValue/animatedSliderValue: a toggle that controls its OWN animated-ness (the "Slider
	 *  Animation" row, GUI Animations' own master switch) always reads that flag AFTER it has already
	 *  flipped by the time a frame renders, so consulting the live flag here too would always force that
	 *  one specific transition instant regardless of what duration the caller computed — those callers use
	 *  this method instead, with a duration that already accounts for a brief grace period. */
	void updateIgnoringGlobalInstant(float dtSeconds) {
		updateInternal(dtSeconds, false);
	}

	private void updateInternal(float dtSeconds, boolean instant) {
		previous = value;
		if (value == target) return;
		if (instant || durationSeconds <= 0.001f) {
			value = target;
			return;
		}
		if (dtSeconds <= 0f) return;
		float rate = 5f / durationSeconds;
		float t = 1f - (float) Math.exp(-rate * dtSeconds);
		value += (target - value) * t;
		if (Math.abs(target - value) < 0.0005f) {
			value = target;
		}
	}

	float get() {
		return value;
	}

	float getPrevious() {
		return previous;
	}
}
