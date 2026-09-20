package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Generic "highlight any mob matching a name pattern with a chosen color" feature — reused for every
 * highlight toggle in the big module list (Zealots, Arachne, slayer minibosses, per-slayer-type
 * highlights) instead of writing a near-identical class per mob. Matching is done against the
 * entity's custom name text (Hypixel Skyblock reskins vanilla entity types and relies entirely on
 * custom names/nametags, not real entity types, so name-contains matching is the same heuristic
 * SkyHanni/Skytils use) — these default patterns are a reasonable starting point from general
 * knowledge of Skyblock, not confirmed against live game text, so they may need tuning once tested.
 */
public class MobHighlightFeature extends Feature {
	/** OUTLINE_2D is outline-only (thickness, no fill); FULL_2D adds an interior fill (color + opacity).
	 *  Both always face the camera (screen-space). The real-MODEL glow outline pass (vanilla's own outline
	 *  shader, see EntityRendererMixin/LivingEntityOutlineMixin) was removed per user report: for Hypixel's
	 *  pests specifically, that outline lands on the invisible armor stand the pest's real model is rigged
	 *  to rather than the visible model itself, which isn't fixable from this side without knowing the
	 *  exact vanilla entity Hypixel reskins for a given pest.
	 *  <p>WIRE_3D/FULL_3D are a DIFFERENT mechanism from that removed one — neither touches vanilla's model
	 *  outline pass at all. Both draw a real depth-tested (or, with {@link #isOcclusion3D} off, always-visible
	 *  "ESP-style") box via {@link com.cokelord.skyblocksimplified.highlight.World3DRenderer}, built from the
	 *  exact same entity {@code getBoundingBox()} source of truth the 2D modes already use (see
	 *  HighlightBoxRenderer's own {@code inflateToMinimumSize}/per-rule Y-offset handling, reused as-is for
	 *  this mode too) — so it doesn't depend on which real entity a mob's model happens to be rigged to, and
	 *  isn't subject to the pest bug above. WIRE_3D is outline-only (mirrors OUTLINE_2D), FULL_3D adds the
	 *  interior fill (mirrors FULL_2D) — per user request, split out from one combined mode that always drew
	 *  both an outline and a fill together. */
	public enum RenderMode { OUTLINE_2D, FULL_2D, WIRE_3D, FULL_3D }

	private final String subcategory;
	private final String slayerType;
	private final Predicate<String> nameMatcher;
	private final int defaultColor;
	private int color;
	protected boolean registered = false;

	private RenderMode renderMode = RenderMode.OUTLINE_2D;
	// Subtoggle for RenderMode.FILLED_3D only (ignored by the 2D modes): true = real depth-tested, occluded
	// by terrain/walls in front of it (World3DRenderer.drawFilledBox/drawWireBox); false = the "ESP-style"
	// always-visible-through-walls variant (drawFilledBoxThroughWalls/drawWireBoxThroughWalls) — same real
	// 3D geometry either way, just a different depth-stencil state. Defaults to occluded/true since that's
	// the more "expected" behavior for a highlight box that isn't explicitly an ESP.
	private boolean occlusion3D = true;
	private int outlineThickness = 2;
	private int fillColor = 0xFFFFFFFF;
	private int fillOpacity = 40;
	private boolean tracers = false;
	private int tracerThickness = 2;
	private int tracerOpacity = 100;

	// Custom image fill (per user request): a picked local file drawn over the FULL_2D highlight box
	// instead of a flat color, in one of 4 fit modes (like a Windows desktop background picker — see
	// ImageFillMode) instead of always unconditionally stretching. Lazily loaded and cached as a GPU
	// texture keyed by path — the actual per-mode blit lives in HighlightBoxRenderer, right where the
	// flat-color fill used to be, so a missing/failed image just falls back to it unmodified.
	private String customImagePath = null;
	private boolean useCustomImage = false;
	private Identifier loadedImageTextureId = null;
	private String loadedImagePath = null;
	private int loadedImageWidth = 0;
	private int loadedImageHeight = 0;

	public enum ImageFillMode {
		STRETCH("Stretch"), FILL("Fill"), FIT("Fit"), TILE("Tile");
		public final String label;
		ImageFillMode(String label) { this.label = label; }
	}
	private ImageFillMode imageFillMode = ImageFillMode.STRETCH;

	// Shared across EVERY highlight module (static, not per-instance) — per user request: a sub-toggle
	// that appears inside every highlight feature's own settings panel, but is really just one underlying
	// setting, so flipping it from ANY of them flips it everywhere at once (same "one shared switch, many
	// entry points" idea as the Kuudra/Dungeons Croesus row, just backed by a single static field here
	// instead of LinkedFeatureMirror — that class mirrors one Feature's row into a second subcategory tab,
	// which isn't quite this: here it's many DIFFERENT Feature subclasses all reading/writing the same
	// value). Controls how often HighlightBoxRenderer re-scans for which entities are currently
	// highlighted — see that class's own doc comment for what doubling it actually costs/buys.
	private static boolean highFrequencyPolling = false;

	public static boolean isHighFrequencyPolling() { return highFrequencyPolling; }
	public static void setHighFrequencyPolling(boolean value) { highFrequencyPolling = value; }

	public MobHighlightFeature(String id, String displayName, FeatureCategory category, String subcategory,
								Predicate<String> nameMatcher, int defaultColor) {
		this(id, displayName, category, subcategory, null, nameMatcher, defaultColor);
	}

	public MobHighlightFeature(String id, String displayName, FeatureCategory category, String subcategory, String slayerType,
								Predicate<String> nameMatcher, int defaultColor) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.slayerType = slayerType;
		this.nameMatcher = nameMatcher;
		this.defaultColor = defaultColor;
		this.color = defaultColor;
	}

	/** For subclasses with a matching strategy that isn't a plain name predicate (e.g. an externally
	 *  tracked entity set) — {@code nameMatcher} stays null and {@link #matches} must be overridden.
	 *  Everything else (render mode, thickness, fill, tracers, persistence) is still inherited as-is. */
	protected MobHighlightFeature(String id, String displayName, FeatureCategory category, String subcategory, int defaultColor) {
		super(id, displayName, category, false);
		this.subcategory = subcategory;
		this.slayerType = null;
		this.nameMatcher = null;
		this.defaultColor = defaultColor;
		this.color = defaultColor;
	}

	public int getDefaultColor() {
		return defaultColor;
	}

	protected boolean matches(Entity entity) {
		String name = entity.getName().getString();
		if (!nameMatcher.test(name)) return false;
		// Two earlier revisions of this method tried to exclude Hypixel's purely decorative nametag holders
		// (portal signs, area labels — e.g. the "Zealot Bruiser" End portal sign, which matched the Zealot
		// rule purely because its own display text contains "Zealot") by ENTITY TYPE — first blanket-
		// excluding every ArmorStand, then narrowing to just marker stands. Both broke real mob matching
		// outright, since Hypixel apparently rigs real mobs (pests confirmed) onto armor stands in ways that
		// don't reliably fall outside either exclusion. Per user's own diagnosis: every real Hypixel mob's
		// nametag shows a health readout (a number), which a purely decorative sign never has — scoped to
		// ONLY armor stands, since that's the sole entity type this project has ever seen used both ways
		// (real mob rig vs. pure decoration); a real player (Highlight Party Members) or any other real mob
		// entity is never ambiguous with a decorative sign in the first place and needs no extra check.
		if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
			for (int i = 0; i < name.length(); i++) {
				if (Character.isDigit(name.charAt(i))) return true;
			}
			return false;
		}
		return true;
	}

	@Override
	protected void onEnable() {
		if (!registered) {
			MobHighlightRegistry.setRule(getId(), this::matches, color, this);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			MobHighlightRegistry.clearRule(getId());
			registered = false;
		}
	}

	@Override
	public String getSubcategory() {
		return subcategory;
	}

	@Override
	public String getSlayerType() {
		return slayerType;
	}

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
		if (registered) {
			MobHighlightRegistry.setRule(getId(), this::matches, color, this);
		}
	}

	@Override
	public Integer getPersistedColor() {
		return color;
	}

	@Override
	public void loadPersistedColor(int argb) {
		setColor(argb);
	}

	public RenderMode getRenderMode() {
		return renderMode;
	}

	public void setRenderMode(RenderMode renderMode) {
		this.renderMode = renderMode;
	}

	public boolean isOcclusion3D() {
		return occlusion3D;
	}

	public void setOcclusion3D(boolean occlusion3D) {
		this.occlusion3D = occlusion3D;
	}

	public int getOutlineThickness() {
		return outlineThickness;
	}

	public void setOutlineThickness(int outlineThickness) {
		this.outlineThickness = Math.max(1, Math.min(10, outlineThickness));
	}

	public int getFillColor() {
		return fillColor;
	}

	public void setFillColor(int fillColor) {
		this.fillColor = fillColor;
	}

	public int getFillOpacity() {
		return fillOpacity;
	}

	public void setFillOpacity(int fillOpacity) {
		this.fillOpacity = Math.max(0, Math.min(100, fillOpacity));
	}

	public boolean isTracersEnabled() {
		return tracers;
	}

	public void setTracersEnabled(boolean tracers) {
		this.tracers = tracers;
	}

	public int getTracerThickness() {
		return tracerThickness;
	}

	public void setTracerThickness(int tracerThickness) {
		this.tracerThickness = Math.max(1, Math.min(10, tracerThickness));
	}

	public int getTracerOpacity() {
		return tracerOpacity;
	}

	public void setTracerOpacity(int tracerOpacity) {
		this.tracerOpacity = Math.max(0, Math.min(100, tracerOpacity));
	}

	public String getCustomImagePath() {
		return customImagePath;
	}

	public void setCustomImagePath(String path) {
		if (java.util.Objects.equals(customImagePath, path)) return;
		releaseLoadedImage();
		customImagePath = path;
	}

	public boolean isUseCustomImage() {
		return useCustomImage;
	}

	public void setUseCustomImage(boolean value) {
		useCustomImage = value;
	}

	public ImageFillMode getImageFillMode() {
		return imageFillMode;
	}

	public void setImageFillMode(ImageFillMode value) {
		imageFillMode = value;
	}

	public void cycleImageFillMode() {
		ImageFillMode[] values = ImageFillMode.values();
		imageFillMode = values[(imageFillMode.ordinal() + 1) % values.length];
	}

	private void releaseLoadedImage() {
		if (loadedImageTextureId != null) {
			Minecraft.getInstance().getTextureManager().release(loadedImageTextureId);
			loadedImageTextureId = null;
			loadedImagePath = null;
		}
	}

	/** The GPU-uploaded texture for customImagePath, loading (and caching) it on first use — null if no
	 *  image is set, or if loading it failed (bad/missing file, not actually an image, etc.), in which
	 *  case the caller should just fall back to the flat-color fill (0xFFFFFFFF/white by default — the
	 *  "custom image just shows plain white" report was this exact fallback, not a rendering bug: every
	 *  non-PNG pick was silently failing to load and falling all the way back to flat white). Failure also
	 *  clears customImagePath outright rather than retrying every single frame against a file that's never
	 *  going to load — which is also why it looked like "leaving the mod menu turns it off": the setting
	 *  really was being saved correctly, but every subsequent render attempt re-failed the same load and
	 *  re-cleared it in memory, over and over, for as long as the file stayed in an unsupported format. */
	public Identifier getCustomImageTextureId() {
		if (customImagePath == null) return null;
		if (loadedImageTextureId != null && customImagePath.equals(loadedImagePath)) return loadedImageTextureId;
		releaseLoadedImage();
		try {
			NativeImage image = readAnyImage(Path.of(customImagePath));
			loadedImageWidth = image.getWidth();
			loadedImageHeight = image.getHeight();
			Identifier id = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID,
				"highlight_custom_" + getId().toLowerCase(Locale.ROOT));
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sbar-highlight-" + getId(), image));
			loadedImageTextureId = id;
			loadedImagePath = customImagePath;
			return id;
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.warn("Failed to load custom highlight image from {}, falling back to flat color fill", customImagePath, e);
			var player = Minecraft.getInstance().player;
			if (player != null) {
				Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
					"§c[" + getDisplayName() + "] Couldn't load that image, falling back to the flat color fill."));
			}
			customImagePath = null;
			useCustomImage = false;
			return null;
		}
	}

	/** NativeImage.read() only reliably decodes PNG — per confirmed user report, anything else (jpg, bmp,
	 *  etc.) silently failed to load. Falls back to javax.imageio (built into the JDK, handles jpg/bmp/gif/
	 *  png and whatever else has a registered plugin) and manually copies the decoded pixels into a
	 *  NativeImage, since ImageIO has no concept of Mojang's own image type. */
	private static NativeImage readAnyImage(Path path) throws java.io.IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return NativeImage.read(in);
		} catch (Exception nativeReadFailed) {
			BufferedImage buffered = ImageIO.read(path.toFile());
			if (buffered == null) throw new java.io.IOException("Unrecognized image format: " + path);
			int width = buffered.getWidth();
			int height = buffered.getHeight();
			NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					// BufferedImage#getRGB(x, y) always returns packed ARGB regardless of the source
					// image's own color model — NativeImage stores ABGR, so red/blue swap on the way in.
					int argb = buffered.getRGB(x, y);
					int a = (argb >>> 24) & 0xFF;
					int r = (argb >>> 16) & 0xFF;
					int g = (argb >>> 8) & 0xFF;
					int b = argb & 0xFF;
					image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
				}
			}
			return image;
		}
	}

	public int getCustomImageWidth() {
		return loadedImageWidth;
	}

	public int getCustomImageHeight() {
		return loadedImageHeight;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("renderMode", renderMode.name());
		obj.addProperty("occlusion3D", occlusion3D);
		obj.addProperty("outlineThickness", outlineThickness);
		obj.addProperty("fillColor", fillColor);
		obj.addProperty("fillOpacity", fillOpacity);
		obj.addProperty("tracers", tracers);
		obj.addProperty("tracerThickness", tracerThickness);
		obj.addProperty("tracerOpacity", tracerOpacity);
		if (customImagePath != null) obj.addProperty("customImagePath", customImagePath);
		obj.addProperty("useCustomImage", useCustomImage);
		obj.addProperty("imageFillMode", imageFillMode.name());
		// Saved redundantly in every highlight module's own config blob — harmless (they're all the exact
		// same shared static value, saved/loaded identically regardless of which module's turn it is).
		obj.addProperty("highFrequencyPolling", highFrequencyPolling);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("renderMode")) {
			String saved = obj.get("renderMode").getAsString();
			// FILLED_3D was renamed to FULL_3D when it was split into WIRE_3D (outline-only)/FULL_3D
			// (outline+fill) — migrates a config saved before that split instead of silently reverting to
			// the OUTLINE_2D default.
			if ("FILLED_3D".equals(saved)) saved = "FULL_3D";
			try {
				renderMode = RenderMode.valueOf(saved);
			} catch (IllegalArgumentException ignored) {}
		}
		if (obj.has("outlineThickness")) outlineThickness = obj.get("outlineThickness").getAsInt();
		if (obj.has("fillColor")) fillColor = obj.get("fillColor").getAsInt();
		if (obj.has("fillOpacity")) fillOpacity = obj.get("fillOpacity").getAsInt();
		if (obj.has("tracers")) tracers = obj.get("tracers").getAsBoolean();
		if (obj.has("tracerThickness")) tracerThickness = obj.get("tracerThickness").getAsInt();
		if (obj.has("tracerOpacity")) tracerOpacity = obj.get("tracerOpacity").getAsInt();
		if (obj.has("customImagePath")) customImagePath = obj.get("customImagePath").getAsString();
		if (obj.has("useCustomImage")) useCustomImage = obj.get("useCustomImage").getAsBoolean();
		if (obj.has("imageFillMode")) { try { imageFillMode = ImageFillMode.valueOf(obj.get("imageFillMode").getAsString()); } catch (IllegalArgumentException ignored) {} }
		if (obj.has("highFrequencyPolling")) highFrequencyPolling = obj.get("highFrequencyPolling").getAsBoolean();
		// Real bug found (per user report — "the starred mob occlusion defaults to occluded when i restart my
		// game, something isn't saving to config"): savePersistedData() already wrote this out, but nothing
		// ever read it back — every restart silently fell through to the field's own default (true/occluded)
		// no matter what was saved.
		if (obj.has("occlusion3D")) occlusion3D = obj.get("occlusion3D").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Highlights " + getDisplayName() + " with a colored outline so it stands out.";
	}
}
