package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.GuiAnimationsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Per user request ("a clean animation for the gui... in the background like bubbles floating around and
 * merging... follow the opening animations... on all custom menus like pv"): soft blurred blobs tinted to
 * blend with the panel theme, drifting slowly across the mod menu, Player Viewer and Party Finder panels.
 * Overlapping blobs blend into each other (lava-lamp look). Each blob is one blit of a single baked radial
 * falloff texture, so this is cheap. Motion runs off wall-clock time, so it continues seamlessly between
 * menus; the caller's open/close fraction fades the blobs in and grows them outward from the center.
 */
public final class GuiBubbles {
	private GuiBubbles() {}

	private static final int TEX = 128;
	private static Identifier texture;

	// Fixed per-bubble parameters: base position (0..1), drift speeds/phases, radius (fraction of min dim),
	// hue shift. Deterministic so every menu shows the same slow field.
	private static final int COUNT = 11;
	private static final float[][] BUBBLES = new float[COUNT][];
	static {
		java.util.Random r = new java.util.Random(0x5B5L);
		for (int i = 0; i < COUNT; i++) {
			BUBBLES[i] = new float[]{
				r.nextFloat(), r.nextFloat(),                              // base x, y
				0.012f + r.nextFloat() * 0.02f, 0.010f + r.nextFloat() * 0.018f, // drift speed x, y (screens/sec)
				r.nextFloat() * 6.28f, r.nextFloat() * 6.28f,              // wobble phases
				0.10f + r.nextFloat() * 0.14f,                             // radius
				(r.nextFloat() - 0.5f) * 0.12f,                            // (unused)
				i,                                                         // index for destination hashing
				9f + r.nextFloat() * 7f                                    // seconds per leg
			};
		}
	}

	public static boolean enabled() {
		return !(FeatureRegistry.get("gui_animations") instanceof GuiAnimationsFeature gaf) || gaf.isBackgroundBubbles();
	}

	/** Draws the field inside the menu panel's body rect ({@code x0..x1, y0..y1}), clipped to the given clip rect
	 *  (a caller drawing its panel in sections passes each section's rect so the bubbles only ever sit on top of
	 *  the panel's own background, never on the rest of the screen). @param open 0..1 open fraction of the
	 *  calling screen (fades and expands the field with the menu). */
	public static void render(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
							  int clipX0, int clipY0, int clipX1, int clipY1, float open) {
		if (!enabled() || open <= 0.01f || x1 - x0 < 4 || y1 - y0 < 4 || clipX1 <= clipX0 || clipY1 <= clipY0) return;
		Identifier tex = texture();
		if (tex == null) return;
		float t = (float) ((System.nanoTime() / 1_000_000_000.0) % 100_000.0);
		int w = x1 - x0, h = y1 - y0;
		float minDim = Math.min(w, h);
		float cx = x0 + w / 2f, cy = y0 + h / 2f;
		float spread = 0.55f + 0.45f * open;
		// Per user request ("match the theme color so they blend in... a little brighter than black on the black
		// theme, a little darker on white theme and full white on transparent... 15%"): white glows on Black and
		// Transparent, black glows on White, peaking at 10% opacity (user: 15% was too bright) so they just tint the panel.
		// Follow-up: white was too bright on Black/Transparent (now a mid gray) and barely visible on White (now
		// black at double strength).
		boolean light = Theme.isLightTheme();
		int rgb = light ? 0x000000 : 0x808080;
		float peak = light ? 0.20f : PEAK_ALPHA;
		graphics.enableScissor(clipX0, clipY0, clipX1, clipY1);
		try {
			for (float[] b : BUBBLES) {
				// Per user request ("randomly pick destinations and go there, they all seem to be cluttered on the
				// bottom"): each bubble glides from one random spot in the panel to the next, eased in and out, so
				// the field stays spread over the whole panel. Destinations are a hash of (bubble, leg), so the
				// motion is continuous and identical across menus without any per-frame state.
				int i = (int) b[8];
				float legSecs = b[9];
				float legPos = t / legSecs + b[4];
				long leg = (long) Math.floor(legPos);
				float k = legPos - leg;
				k = k * k * (3f - 2f * k);
				float fx = lerp(dest(i, leg, 0), dest(i, leg + 1, 0), k) + 0.015f * (float) Math.sin(t * 0.35f + b[5]);
				float fy = lerp(dest(i, leg, 1), dest(i, leg + 1, 1), k) + 0.015f * (float) Math.cos(t * 0.3f + b[4]);
				float x = x0 + fx * w;
				float y = y0 + fy * h;
				x = cx + (x - cx) * spread;
				y = cy + (y - cy) * spread;
				// Blurred: gaussian falloff baked into the texture plus a wide faint halo — out-of-focus light.
				float radius = minDim * b[6] * 1.5f * (0.9f + 0.1f * (float) Math.sin(t * 0.5f + b[4])) * (0.7f + 0.3f * open);
				blob(graphics, tex, x, y, radius * 1.8f, rgb, peak * 0.35f * open);
				blob(graphics, tex, x, y, radius, rgb, peak * 0.65f * open);
			}
		} finally {
			graphics.disableScissor();
		}
	}

	/** Peak opacity at a lone bubble's center (halo + core) on the dark themes. */
	private static final float PEAK_ALPHA = 0.10f;

	private static void blob(GuiGraphicsExtractor graphics, Identifier tex, float x, float y, float radius, int rgb, float alpha01) {
		int alpha = Math.round(RenderUtil.clamp01(alpha01) * 255f);
		if (alpha <= 0) return;
		int size = Math.round(radius * 2f);
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex,
			Math.round(x - radius), Math.round(y - radius), 0f, 0f, size, size, TEX, TEX, TEX, TEX, (alpha << 24) | rgb);
	}

	private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

	/** Deterministic pseudo-random destination coordinate in 0.08..0.92 for bubble {@code i}'s leg {@code leg}. */
	private static float dest(int i, long leg, int axis) {
		long h = (leg * 0x9E3779B97F4A7C15L) ^ ((long) i * 0xC2B2AE3D27D4EB4FL) ^ ((long) axis * 0x165667B19E3779F9L);
		h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
		return 0.08f + ((h >>> 40) / (float) (1L << 24)) * 0.84f;
	}

	/** A white gaussian falloff baked once (128px, linear filtering) — the blur is in the texture itself. */
	private static Identifier texture() {
		if (texture != null) return texture;
		try {
			com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(
				com.mojang.blaze3d.platform.NativeImage.Format.RGBA, TEX, TEX, false);
			float c = (TEX - 1) / 2f;
			for (int j = 0; j < TEX; j++) {
				for (int i = 0; i < TEX; i++) {
					float d = (float) Math.hypot(i - c, j - c) / c;
					// Gaussian falloff (a blur kernel's shape), faded to exactly 0 at the rim.
					float a = d >= 1f ? 0f : (float) (Math.exp(-4.5 * d * d) * (1 - d * d));
					image.setPixelABGR(i, j, (Math.round(a * 255f) << 24) | 0xFFFFFF);
				}
			}
			Identifier id = Identifier.fromNamespaceAndPath("skyblocksimplified", "gui_bubble");
			net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "sbs-gui-bubble", image) {
				{
					this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
						.getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
				}
			};
			Minecraft.getInstance().getTextureManager().register(id, dyn);
			texture = id;
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("GUI bubble texture failed", e);
		}
		return texture;
	}
}
