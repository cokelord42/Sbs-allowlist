package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Replaces lava's rendered texture/fog with water's — per user request ("Lava to water, or just remake it
 * yourself, i think it just replaces all lava with water clientside or something"), ported from
 * NoammAddons' confirmed {@code LavaToWater.kt}/{@code MixinLavaFogEnvironment.java}. Purely a client-side
 * rendering swap: the two real Mixins ({@link com.cokelord.skyblocksimplified.mixin.LavaFluidModelMixin},
 * {@link com.cokelord.skyblocksimplified.mixin.LavaFogEnvironmentMixin}) only intercept which baked model/
 * fog color get drawn for a lava fluid state — the actual block/fluid data (what the server thinks is
 * there, hitboxes, damage, swimming physics) is untouched, exactly like NoammAddons' own framing of this
 * as a pure visual replacement.
 *
 * <p>Real bug found (per user report — "Lava replace water doesn't replace all lava. It needs to check for
 * lava constantly"): {@code LavaFluidModelMixin} only ever gets consulted when a chunk SECTION'S geometry
 * is actually (re)compiled — real Minecraft only rebuilds a section's baked mesh on a real trigger (a block
 * change nearby, first load, etc.), not every frame, so any lava that was already baked into a section's
 * mesh BEFORE this feature was toggled on (or before that section entered render distance while the
 * mixin's own {@code isEnabled()} check would have caught it) keeps showing its old lava mesh forever,
 * since nothing else ever asks {@code FluidStateModelSet} to re-resolve it. Fixed by periodically forcing
 * nearby chunk sections to actually re-render (the same real {@code ClientLevel#setSectionDirtyWithNeighbors}
 * hook a genuine nearby block update already triggers), so every lava section within render range gets a
 * fresh look from the mixin on a real interval instead of only once at toggle time.
 */
public class LavaToWaterFeature extends Feature {
	// Real cost tradeoff: forcing a section rebuild isn't free (same class of cost as vanilla's own F3+A
	// "reload chunks" — rebuilding real render geometry), so this only refreshes a modest radius around the
	// player, and only every few seconds rather than every tick, instead of a genuinely "check every frame"
	// literal reading of the request.
	private static final int REFRESH_INTERVAL_TICKS = 100; // 5 real seconds
	private static final int SECTION_RADIUS = 4; // ~4 chunks around the player, both horizontally and vertically

	private boolean colorTint = false;
	private int tintColor = 0xFF3F76E4;
	private boolean hideFog = true;
	private int refreshCooldownTicks = 0;

	public LavaToWaterFeature() {
		super("lava_to_water", "Lava to Water", FeatureCategory.PERFORMANCE, false);
	}

	@Override
	protected void onDisable() {
		refreshCooldownTicks = 0;
	}

	@Override
	public void onTick(Minecraft client) {
		if (client.level == null || client.player == null) return;
		if (--refreshCooldownTicks > 0) return;
		refreshCooldownTicks = REFRESH_INTERVAL_TICKS;

		BlockPos pos = client.player.blockPosition();
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		int cy = Math.floorDiv(pos.getY(), 16);
		for (int dx = -SECTION_RADIUS; dx <= SECTION_RADIUS; dx++) {
			for (int dz = -SECTION_RADIUS; dz <= SECTION_RADIUS; dz++) {
				if (!client.level.hasChunk(cx + dx, cz + dz)) continue;
				for (int dy = -SECTION_RADIUS; dy <= SECTION_RADIUS; dy++) {
					client.level.setSectionDirtyWithNeighbors(cx + dx, cy + dy, cz + dz);
				}
			}
		}
	}

	public boolean isColorTint() { return colorTint; }
	public void setColorTint(boolean value) { colorTint = value; }
	public int getTintColor() { return tintColor; }
	public void setTintColor(int value) { tintColor = value; }
	public boolean isHideFog() { return hideFog; }
	public void setHideFog(boolean value) { hideFog = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("colorTint", colorTint);
		obj.addProperty("tintColor", tintColor);
		obj.addProperty("hideFog", hideFog);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("colorTint")) colorTint = obj.get("colorTint").getAsBoolean();
		if (obj.has("tintColor")) tintColor = obj.get("tintColor").getAsInt();
		if (obj.has("hideFog")) hideFog = obj.get("hideFog").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Replaces lava's texture and fog with water's, client-side only, to make it easier to see through.";
	}
}
