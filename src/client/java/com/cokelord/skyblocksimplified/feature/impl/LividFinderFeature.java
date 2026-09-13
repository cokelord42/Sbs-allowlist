package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights the real Livid among the fake look-alikes in the F5/M5 boss fight — ported from SkyHanni's
 * DungeonLividFinder.kt, keeping its confirmed fixed wool-color indicator block position (6, 109, 43)
 * and its confirmed name-to-color mapping for the 9 named Livid skins. The correct Livid's color is
 * read off that wool block each tick (polled, since this project doesn't have a block-change event
 * hook) rather than SkyHanni's own skin-texture cache; "Hide Wrong Livids" hides every other candidate
 * via EntityHideRegistry.
 *
 * <p>Per user request ("Add rendering modes and occlusion toggle to the livid finder, and tracer
 * support"): now extends {@link MobHighlightFeature} instead of plain {@code Feature} — the real Livid
 * highlight used to only ever get {@link com.cokelord.skyblocksimplified.highlight.HighlightBoxRenderer}'s
 * generic no-owning-Feature fallback (a fixed outline-only 2D box), since that renderer only reads
 * render-mode/occlusion/tracer/fill settings off a registered rule's owning Feature when that Feature IS a
 * {@link MobHighlightFeature} — see that class's own doc comment. Extending it directly makes every one of
 * those settings (and their mod-menu rows) apply here for free, exactly like every other entity highlight
 * in this codebase (Zealot, Starred Mob, etc.); {@link #matches} just supplies a different match strategy
 * (the live wool-detected color) instead of a name predicate, mirroring how StarredMobHighlightFeature
 * overrides {@code matches} for its own tracked-entity-set strategy. The wool-detected color still only
 * decides WHICH entity matches — the box's own drawn color is the normal user-configurable "Highlight
 * Color" setting inherited from {@link MobHighlightFeature}, same as every other highlight module, rather
 * than being forced to match the wool color the way the old bespoke rendering did.
 */
public class LividFinderFeature extends MobHighlightFeature {
	private static final BlockPos INDICATOR_POS = new BlockPos(6, 109, 43);
	private static final Pattern LIVID_NAME = Pattern.compile("^(?<name>\\w+) Livid$");

	private static final Map<String, Integer> NAME_TO_COLOR = new LinkedHashMap<>();
	private static final Map<Block, Integer> WOOL_TO_COLOR = new LinkedHashMap<>();

	static {
		NAME_TO_COLOR.put("Vendetta", 0xFFFFFFFF);
		NAME_TO_COLOR.put("Doctor", 0xFF555555);
		NAME_TO_COLOR.put("Crossed", 0xFFFF55FF);
		NAME_TO_COLOR.put("Purple", 0xFFAA00AA);
		NAME_TO_COLOR.put("Scream", 0xFF5555FF);
		NAME_TO_COLOR.put("Hockey", 0xFFFF5555);
		NAME_TO_COLOR.put("Arcade", 0xFFFFFF55);
		NAME_TO_COLOR.put("Smile", 0xFF55FF55);
		NAME_TO_COLOR.put("Frog", 0xFF00AA00);

		WOOL_TO_COLOR.put(Blocks.WOOL.white(), 0xFFFFFFFF);
		WOOL_TO_COLOR.put(Blocks.WOOL.gray(), 0xFF555555);
		WOOL_TO_COLOR.put(Blocks.WOOL.magenta(), 0xFFFF55FF);
		WOOL_TO_COLOR.put(Blocks.WOOL.purple(), 0xFFAA00AA);
		WOOL_TO_COLOR.put(Blocks.WOOL.blue(), 0xFF5555FF);
		WOOL_TO_COLOR.put(Blocks.WOOL.red(), 0xFFFF5555);
		WOOL_TO_COLOR.put(Blocks.WOOL.yellow(), 0xFFFFFF55);
		WOOL_TO_COLOR.put(Blocks.WOOL.lime(), 0xFF55FF55);
		WOOL_TO_COLOR.put(Blocks.WOOL.green(), 0xFF00AA00);
	}

	private Block lastBlock = null;
	private Integer targetColor = null;
	private boolean hideWrongLivids = false;
	// Per user request ("Add a toggle for coloring the correct livid their color. For example, if the
	// correct livid color is purple then it should color the ESP purple"): when on, the highlight box is
	// drawn in the wool-detected REAL Livid's own color instead of the normal user-configurable Highlight
	// Color setting — see onTick's own doc comment for how this overrides the registered rule's color live,
	// without touching the persisted Highlight Color setting itself.
	private boolean colorByLividColor = false;

	public LividFinderFeature() {
		super("livid_finder", "Livid Finder", FeatureCategory.COMBAT, "Dungeons", 0xFFFFFFFF);
	}

	/** The wool-detected real Livid's color decides matching only — see this class's own doc comment for
	 *  why the drawn highlight color itself is the normal inherited {@link MobHighlightFeature#getColor()}
	 *  setting instead. */
	@Override
	protected boolean matches(Entity entity) {
		return targetColor != null && matchesColor(entity, targetColor);
	}

	public boolean isHideWrongLivids() {
		return hideWrongLivids;
	}

	public void setHideWrongLivids(boolean hideWrongLivids) {
		this.hideWrongLivids = hideWrongLivids;
	}

	public boolean isColorByLividColor() {
		return colorByLividColor;
	}

	public void setColorByLividColor(boolean value) {
		this.colorByLividColor = value;
		// Immediately restore the normal Highlight Color setting on the live rule when turning this off —
		// otherwise the wool-detected override color would keep showing until the next onTick happened to
		// re-register it (harmless either way since that's every tick in practice, but this avoids even a
		// one-frame stale color right after the toggle flips).
		if (!value && registered) MobHighlightRegistry.setRule(getId(), this::matches, getColor(), this);
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		EntityHideRegistry.clearRule(getId());
		lastBlock = null;
		targetColor = null;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("hideWrongLivids", hideWrongLivids);
		obj.addProperty("colorByLividColor", colorByLividColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("hideWrongLivids")) hideWrongLivids = obj.get("hideWrongLivids").getAsBoolean();
		if (obj.has("colorByLividColor")) colorByLividColor = obj.get("colorByLividColor").getAsBoolean();
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || client.level == null) {
			EntityHideRegistry.clearRule(getId());
			return;
		}

		Block current = client.level.getBlockState(INDICATOR_POS).getBlock();
		if (current != lastBlock) {
			lastBlock = current;
			targetColor = WOOL_TO_COLOR.get(current);
		}
		if (targetColor == null) {
			EntityHideRegistry.clearRule(getId());
			return;
		}

		int color = targetColor;
		// Real feature request (see colorByLividColor's own doc comment): re-registers the highlight rule
		// with the wool-detected real color every tick instead of the normal Highlight Color setting — this
		// only overrides the rule's live drawn color, never the persisted setting, and re-runs every tick
		// since the detected color can change mid-fight (a fresh Livid spawn reads a different wool color).
		if (colorByLividColor && registered) {
			MobHighlightRegistry.setRule(getId(), this::matches, color, this);
		}
		if (hideWrongLivids) {
			EntityHideRegistry.setRule(getId(), entity -> isLividCandidate(entity) && !matchesColor(entity, color));
		} else {
			EntityHideRegistry.clearRule(getId());
		}
	}

	private boolean isLividCandidate(Entity entity) {
		if (!(entity instanceof Player player)) return false;
		return LIVID_NAME.matcher(player.getName().getString()).matches();
	}

	private static boolean matchesColor(Entity entity, int color) {
		if (!(entity instanceof Player player)) return false;
		Matcher matcher = LIVID_NAME.matcher(player.getName().getString());
		if (!matcher.matches()) return false;
		Integer nameColor = NAME_TO_COLOR.get(matcher.group("name"));
		return nameColor != null && nameColor == color;
	}

	@Override
	public String getDescription() {
		return "Highlights the real Livid among the fake look-alikes in the F5/M5 boss fight.";
	}
}
