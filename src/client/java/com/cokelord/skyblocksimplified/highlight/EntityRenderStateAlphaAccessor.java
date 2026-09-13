package com.cokelord.skyblocksimplified.highlight;

/** Implemented by EntityRenderStateMixin's injected field — carries a per-frame alpha value (0-255,
 *  255 = fully opaque) from extraction time (where the real Entity is available) through to the
 *  Living-entity submit/render-type mixins (which only see the render state, not the entity). Lives
 *  outside the com.cokelord.skyblocksimplified.mixin package on purpose: Mixin's classloader reserves that
 *  whole package for @Mixin-annotated transformer classes and refuses to load anything else from it
 *  (confirmed by a hard IllegalClassLoadError crash on launch when this lived there instead). */
public interface EntityRenderStateAlphaAccessor {
	int skyblocksimplified$getAlpha();

	void skyblocksimplified$setAlpha(int alpha);
}
