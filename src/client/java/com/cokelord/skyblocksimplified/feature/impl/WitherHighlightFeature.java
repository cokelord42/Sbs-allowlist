package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * Highlights the real Wither entities Hypixel's Floor 7 / Master 7 boss fight spawns — per user request
 * ("Add a 'Wither highlight' module into the Floor 7 subcategory that highlights withers... Add tracer
 * support"), same shape as StarredMobHighlightFeature: matches by real entity TYPE rather than a name
 * pattern, since these withers are genuine vanilla {@code WitherBoss} entities, not an ArmorStand-rigged
 * reskin. Render mode, thickness, fill, and tracers are all inherited as-is from {@link MobHighlightFeature}.
 *
 * <p>Real redesign (per user request — "remake this one and highlight withers in the f7 boss fight",
 * cross-checked against NoammAddons' own confirmed {@code WitherESP.kt}): two real gaps in the original
 * version. First, Hypixel spawns each phase's WitherBoss entity briefly INVISIBLE before it actually
 * materializes (NoammAddons excludes exactly this with {@code entity.isInvisible}) — the plain type match
 * here could pick up that not-yet-visible entity, which then genuinely can't render a highlight (nothing
 * to draw around) and reads as "the module does nothing." Second, real WitherBoss entities also exist
 * outside the F7 boss fight entirely (normal player-summoned Withers elsewhere on the island) — this now
 * gates matching to the F7 boss fight specifically, matching NoammAddons' own {@code isValidLoc()} check.
 * Also new: a separate configurable color per real boss phase (Maxor/Storm/Goldor/Necron — NoammAddons'
 * own confirmed 4-color scheme), applied via {@link #setColor} the moment {@link DungeonState#getF7Phase()}
 * changes, rather than one flat color for the whole fight.
 */
public class WitherHighlightFeature extends MobHighlightFeature {
	private int maxorColor = 0xFF5804A4;
	private int stormColor = 0xFF00D0FF;
	// Per user report ("Goldor defaults to #FFBB00"): was plain white, which is hard to distinguish from
	// the Goldor wither's own naturally pale/bone-colored model at a glance — a real amber now instead.
	private int goldorColor = 0xFFFFBB00;
	private int necronColor = 0xFFFF0000;

	// Per user request ("Wither Highlight: colorable per-boss fill matching outline color"): the base
	// MobHighlightFeature's own fillColor is one flat color shared by every fill-based render mode
	// (FULL_2D/FULL_3D), same as it is for every other MobHighlightFeature instance — fine for a single-
	// target highlight, but wrong here where the highlighted entity's actual color is meant to change every
	// boss phase. Each phase now gets its own fill color too, defaulted to the SAME value as that phase's
	// own outline color (so "matching outline color" is the out-of-the-box behavior, not something the user
	// has to configure manually first) — applied via setFillColor() the same moment setColor() already
	// applies the outline per phase, below.
	private int maxorFillColor = maxorColor;
	private int stormFillColor = stormColor;
	private int goldorFillColor = goldorColor;
	private int necronFillColor = necronColor;

	private DungeonState.F7Phase lastPhase = DungeonState.F7Phase.UNKNOWN;

	public WitherHighlightFeature() {
		super("wither_highlight", "Wither Highlight", FeatureCategory.COMBAT, "Floor 7", 0xFF5804A4);
	}

	@Override
	protected boolean matches(Entity entity) {
		if (!(entity instanceof WitherBoss) || entity.isInvisible()) return false;
		return DungeonState.isInBoss() && DungeonState.getFloorNumber() == 7;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!DungeonState.isInBoss() || DungeonState.getFloorNumber() != 7) {
			lastPhase = DungeonState.F7Phase.UNKNOWN;
			return;
		}
		DungeonState.F7Phase phase = DungeonState.getF7Phase();
		if (phase == lastPhase) return;
		lastPhase = phase;
		Integer color = switch (phase) {
			case P1 -> maxorColor;
			case P2 -> stormColor;
			case P3, P4 -> goldorColor;
			case P5 -> necronColor;
			case UNKNOWN -> null;
		};
		Integer fillColor = switch (phase) {
			case P1 -> maxorFillColor;
			case P2 -> stormFillColor;
			case P3, P4 -> goldorFillColor;
			case P5 -> necronFillColor;
			case UNKNOWN -> null;
		};
		if (color != null) setColor(color);
		if (fillColor != null) setFillColor(fillColor);
	}

	public int getMaxorColor() { return maxorColor; }
	public void setMaxorColor(int value) { maxorColor = value; if (lastPhase == DungeonState.F7Phase.P1) setColor(value); }
	public int getStormColor() { return stormColor; }
	public void setStormColor(int value) { stormColor = value; if (lastPhase == DungeonState.F7Phase.P2) setColor(value); }
	public int getGoldorColor() { return goldorColor; }
	public void setGoldorColor(int value) {
		goldorColor = value;
		if (lastPhase == DungeonState.F7Phase.P3 || lastPhase == DungeonState.F7Phase.P4) setColor(value);
	}
	public int getNecronColor() { return necronColor; }
	public void setNecronColor(int value) { necronColor = value; if (lastPhase == DungeonState.F7Phase.P5) setColor(value); }

	public int getMaxorFillColor() { return maxorFillColor; }
	public void setMaxorFillColor(int value) { maxorFillColor = value; if (lastPhase == DungeonState.F7Phase.P1) setFillColor(value); }
	public int getStormFillColor() { return stormFillColor; }
	public void setStormFillColor(int value) { stormFillColor = value; if (lastPhase == DungeonState.F7Phase.P2) setFillColor(value); }
	public int getGoldorFillColor() { return goldorFillColor; }
	public void setGoldorFillColor(int value) {
		goldorFillColor = value;
		if (lastPhase == DungeonState.F7Phase.P3 || lastPhase == DungeonState.F7Phase.P4) setFillColor(value);
	}
	public int getNecronFillColor() { return necronFillColor; }
	public void setNecronFillColor(int value) { necronFillColor = value; if (lastPhase == DungeonState.F7Phase.P5) setFillColor(value); }

	@Override
	public JsonElement savePersistedData() {
		JsonElement base = super.savePersistedData();
		JsonObject obj = base != null && base.isJsonObject() ? base.getAsJsonObject() : new JsonObject();
		obj.addProperty("maxorColor", maxorColor);
		obj.addProperty("stormColor", stormColor);
		obj.addProperty("goldorColor", goldorColor);
		obj.addProperty("necronColor", necronColor);
		obj.addProperty("maxorFillColor", maxorFillColor);
		obj.addProperty("stormFillColor", stormFillColor);
		obj.addProperty("goldorFillColor", goldorFillColor);
		obj.addProperty("necronFillColor", necronFillColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("maxorColor")) maxorColor = obj.get("maxorColor").getAsInt();
		if (obj.has("stormColor")) stormColor = obj.get("stormColor").getAsInt();
		if (obj.has("goldorColor")) goldorColor = obj.get("goldorColor").getAsInt();
		if (obj.has("necronColor")) necronColor = obj.get("necronColor").getAsInt();
		if (obj.has("maxorFillColor")) maxorFillColor = obj.get("maxorFillColor").getAsInt();
		if (obj.has("stormFillColor")) stormFillColor = obj.get("stormFillColor").getAsInt();
		if (obj.has("goldorFillColor")) goldorFillColor = obj.get("goldorFillColor").getAsInt();
		if (obj.has("necronFillColor")) necronFillColor = obj.get("necronFillColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Highlights Wither Boss (F7/M7) mobs with a colored outline you can customize per boss.";
	}
}
