package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per user request ("Make highlight party members into its own module instead of being in the hide glow
 * module. However, add the hide glow module as a subtoggle inside highlight party members ASWELL so the
 * hide glow we currently have links with the one in the teammate highlight. They are related, but some
 * people may use the hide glow for other stuff. Also add the 3d rendering to teammate highlighting."):
 * split out of {@link PlayerGlowFeature}'s old "Highlight Party Members" subtoggle into its own real
 * module. Extends {@link MobHighlightFeature} directly (in flat-color mode) instead of driving
 * {@link MobHighlightRegistry} by hand — that's what gets this module the full render-mode selector
 * (2D outline/fill, real depth-tested 3D outline/fill) for free, the same infrastructure every other
 * mob-highlight module in this codebase already shares (see {@code HighlightBoxRenderer}'s
 * {@code FeatureRegistry.get(ruleId)} lookup, which only resolves render settings when the rule's id is a
 * real registered Feature id — this feature's own id is exactly that, unlike the old ad hoc rule ids).
 *
 * <p>"Color by Role" mode is the one exception: it needs 5 simultaneously-active colors (one per
 * {@link DungeonClass}), which {@link MobHighlightRegistry}'s one-rule-one-color model can't express as a
 * single rule. In that mode this registers 5 separate rule ids instead of the inherited single one — those
 * don't resolve back to this Feature via {@code FeatureRegistry.get}, so they always render through the
 * generic 2D-box fallback regardless of the Render Mode setting. Flat-color mode is required for 3D.
 */
public class HighlightPartyMembersFeature extends MobHighlightFeature {
	// Local to this feature, deliberately not touching DungeonClass.color — that field is also used by
	// LeapMenuFeature/StarredMobHighlightFeature's own teammate-class tint, and the user gave an explicit,
	// different color mapping for this specific highlight ("archer orange, berserk red, healer pink, mage
	// blue, tank gray") rather than asking to change those other features' existing palette.
	private static final Map<DungeonClass, Integer> ROLE_COLORS = new EnumMap<>(DungeonClass.class);
	static {
		ROLE_COLORS.put(DungeonClass.ARCHER, 0xFFFF8C00);
		ROLE_COLORS.put(DungeonClass.BERSERK, 0xFFFF0000);
		ROLE_COLORS.put(DungeonClass.HEALER, 0xFFFF69B4);
		ROLE_COLORS.put(DungeonClass.MAGE, 0xFF3366FF);
		ROLE_COLORS.put(DungeonClass.TANK, 0xFF808080);
	}

	private boolean colorByRole = false;

	public HighlightPartyMembersFeature() {
		super("highlight_party_members", "Highlight Party Members", FeatureCategory.COMBAT, "Dungeons", 0xFF55FF55);
	}

	@Override
	protected boolean matches(Entity entity) {
		return teammateOf(entity) != null;
	}

	private static boolean matchesWithClass(Entity entity, DungeonClass clazz) {
		DungeonPlayer teammate = teammateOf(entity);
		return teammate != null && teammate.clazz == clazz;
	}

	private static DungeonPlayer teammateOf(Entity entity) {
		if (!(entity instanceof Player player)) return null;
		String name = player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammatesNoSelf()) {
			if (teammate.name.equals(name)) return teammate;
		}
		return null;
	}

	private static String roleRuleId(DungeonClass clazz) {
		return "highlight_party_members_role_" + clazz.name().toLowerCase(Locale.ROOT);
	}

	@Override
	protected void onEnable() {
		if (registered) return;
		registered = true;
		updateRules();
	}

	@Override
	protected void onDisable() {
		if (!registered) return;
		registered = false;
		clearRules();
	}

	private void updateRules() {
		clearRules();
		if (!isEnabled()) return;
		if (colorByRole) {
			// Real bug found (per user report — "The 3d teammate highlight does not work. Its still 2d even
			// on 3d mode"): these 5 synthetic per-role rule ids don't match this feature's own registered id,
			// so FeatureRegistry.get(ruleId) (what HighlightBoxRenderer used to resolve render settings)
			// always returned null for them — Color by Role mode could never get this feature's Render Mode,
			// thickness, fill, or tracer settings applied at all, silently falling back to the fixed-2D-only
			// generic path regardless of what Render Mode was actually set to. Passing `this` here lets
			// HighlightBoxRenderer read those settings straight off the Rule itself now (see
			// MobHighlightRegistry.Rule's own doc comment) while still using each role's own distinct color.
			for (DungeonClass clazz : ROLE_COLORS.keySet()) {
				MobHighlightRegistry.setRule(roleRuleId(clazz), entity -> matchesWithClass(entity, clazz), ROLE_COLORS.get(clazz), this);
			}
		} else {
			MobHighlightRegistry.setRule(getId(), this::matches, getColor(), this);
		}
	}

	private void clearRules() {
		MobHighlightRegistry.clearRule(getId());
		for (DungeonClass clazz : ROLE_COLORS.keySet()) MobHighlightRegistry.clearRule(roleRuleId(clazz));
	}

	@Override
	public void setColor(int color) {
		super.setColor(color);
		updateRules();
	}

	public boolean isColorByRole() { return colorByRole; }
	public void setColorByRole(boolean value) { colorByRole = value; updateRules(); }

	// --- Linked "Hide Glow" subtoggle (per user request — see class doc comment) ---
	public boolean isHideGlowEnabled() {
		PlayerGlowFeature glow = PlayerGlowFeature.getInstance();
		return glow != null && glow.isEnabled();
	}

	public void setHideGlowEnabled(boolean value) {
		PlayerGlowFeature glow = PlayerGlowFeature.getInstance();
		if (glow != null) glow.setEnabled(value);
	}

	@Override
	public JsonElement savePersistedData() {
		JsonElement base = super.savePersistedData();
		JsonObject obj = base instanceof JsonObject o ? o : new JsonObject();
		obj.addProperty("colorByRole", colorByRole);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("colorByRole")) colorByRole = obj.get("colorByRole").getAsBoolean();
		updateRules();
	}

	@Override
	public String getDescription() {
		return "Highlights your dungeon party members with a colored outline so you can spot them at a glance.";
	}
}
