package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;

/**
 * Generic "is there a nearby floating-text ArmorStand containing this glyph" indicator — ported from
 * SkyHanni's DamageIndicatorManager's shurikenIndicator/twilightIndicator (Extremely Real Shuriken /
 * Twilight Arrow Poison procs), keeping its confirmed glyphs ("§b✯" / "§5ᛤ"). Hypixel shows these as a
 * floating nametag near the affected boss; SkyHanni labels the specific boss entity in world space,
 * which needs a world-to-screen projection this project doesn't have generic infra for yet, so this
 * shows a simple "active nearby" HUD line instead of a per-boss floating label.
 *
 * The glyph passed in (e.g. "§b✯") carries a leading color code, but this is matched against an
 * ArmorStand's custom nametag via getCustomName().getString() — confirmed via decompiled source that
 * getString() strips Style-based formatting entirely (see TreeProgressDisplayFeature's doc comment), so
 * if Hypixel sends this coloring via structured Style rather than literal "§" characters, the nametag
 * string would never contain "§" and a plain contains(glyph) check would silently never match. Both
 * sides are stripped of any "§." runs before comparing so the check matches either form.
 */
public class NametagGlyphIndicatorFeature extends TabWidgetOverlayFeature {
	private final String glyph;
	private final String label;
	private boolean active = false;
	private int tickCounter = 0;

	public NametagGlyphIndicatorFeature(String id, String displayName, String glyph, String label, float anchorY) {
		super(id, displayName, FeatureCategory.COMBAT, null, 0.01f, anchorY);
		this.glyph = glyph.replaceAll("§.", "");
		this.label = label;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.level == null) return;
		// Two of these run at once (shuriken + twilight), each doing a full entity scan — a presence
		// indicator doesn't need 20/sec updates, so only scan 5x/sec to keep that cost down.
		if (tickCounter++ % 4 != 0) return;
		active = false;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand)) continue;
			var customName = stand.getCustomName();
			if (customName != null && customName.getString().replaceAll("§.", "").contains(glyph)) {
				active = true;
				break;
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		return active ? List.of(label) : List.of();
	}

	@Override
	public String getDescription() {
		return "Shows a small on-screen indicator whenever a nearby floating nametag contains a specific status-effect glyph (Extremely Real Shuriken / Twilight Arrow Poison).";
	}
}
