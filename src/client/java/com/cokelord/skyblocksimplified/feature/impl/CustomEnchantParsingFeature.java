package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.item.RomanNumeralUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User-defined enchant+level lore restyling: each rule matches a specific enchant name and level (the
 * level is accepted as a roman numeral or plain decimal when typed — "VII" and "7" are the same rule —
 * see RomanNumeralUtil) and carries a color/chroma/bold/tilted style applied to just that "Name Level"
 * run within tooltip lore, leaving the rest of the line untouched. Matching against real lore (which
 * Hypixel always renders as roman) happens in EnchantTooltipFilter via EnchantLoreUtil.findTokens.
 */
public class CustomEnchantParsingFeature extends Feature {

	public static class Rule {
		public String name;
		// The live-edited display text for the level field (whatever the user last typed — "VII" or
		// "7"), kept separate from the parsed `level` int so mid-edit garbage doesn't reset the field.
		public String levelInput;
		public int level;
		public int color = 0xFFFFFFFF;
		public boolean bold = false;
		public boolean tilted = false;
		public boolean chromaEnabled = false;
		public float chromaSize = 1f;
		public float chromaSpeed = 1f;
		public float chromaSaturation = 1f;

		public Rule(String name, int level) {
			this.name = name;
			this.level = level;
			this.levelInput = RomanNumeralUtil.toRoman(level);
		}
	}

	/** Per user request ("per-tier color/bold/italic overrides"): a flat, name-independent style applied
	 *  to ANY enchant at this exact level (1-10) — for banding a whole loadout's enchants by tier (e.g.
	 *  every level X enchant in gold) without hand-adding a same-styled {@link Rule} per enchant name.
	 *  Only kicks in when no more specific per-name {@link Rule} already matches that exact (name, level)
	 *  pair — see {@link #findMatch} — so a rule the user set up for one specific enchant always wins. No
	 *  chroma here; the per-name Rule path already covers that for anyone who wants it on a specific enchant. */
	public static class TierOverride {
		public boolean enabled = false;
		public int color = 0xFFFFFFFF;
		public boolean bold = false;
		public boolean tilted = false;
	}

	/** What {@link #findMatch} hands back to the tooltip renderer — a flattened view over either a
	 *  {@link Rule} (chroma included) or a {@link TierOverride} (chroma always off), so the renderer
	 *  doesn't need to know or care which kind of match it got. */
	public record ResolvedStyle(int color, boolean bold, boolean tilted,
								 boolean chromaEnabled, float chromaSize, float chromaSpeed, float chromaSaturation) {
		private static ResolvedStyle of(Rule r) {
			return new ResolvedStyle(r.color, r.bold, r.tilted, r.chromaEnabled, r.chromaSize, r.chromaSpeed, r.chromaSaturation);
		}
		private static ResolvedStyle of(TierOverride t) {
			return new ResolvedStyle(t.color, t.bold, t.tilted, false, 1f, 1f, 1f);
		}
	}

	private final List<Rule> rules = new ArrayList<>();
	private final TierOverride[] tierOverrides = new TierOverride[10];
	{
		for (int i = 0; i < tierOverrides.length; i++) tierOverrides[i] = new TierOverride();
	}

	public CustomEnchantParsingFeature() {
		super("custom_enchant_parsing", "Custom Enchant Parsing", FeatureCategory.INVENTORY, false);
	}

	@Override
	public String getSubcategory() {
		return "Enchantments";
	}

	public List<Rule> getRules() {
		return rules;
	}

	/** Real bug found (per user report — "Users should need no regular parsings to show the enchant colors.
	 *  They only show up when i add a parsing"): EnchantTooltipFilter used to gate its whole restyle pass on
	 *  {@code getRules().isEmpty()} alone, so an enabled tier override with zero per-name Rules never even got
	 *  a chance to match — findMatch's own tier-override fallback was correct, it just never ran. This checks
	 *  both sources so a tier override alone is enough. */
	public boolean hasAnyActiveRule() {
		if (!rules.isEmpty()) return true;
		for (TierOverride tier : tierOverrides) {
			if (tier.enabled) return true;
		}
		return false;
	}

	public void addRule(String name, int level) {
		rules.add(new Rule(name, level));
	}

	public void removeRule(int index) {
		if (index >= 0 && index < rules.size()) rules.remove(index);
	}

	/** Index 0 = level 1 ... index 9 = level 10 — fixed 10-slot array, no add/remove. */
	public TierOverride[] getTierOverrides() {
		return tierOverrides;
	}

	/** Case-insensitive name match plus exact level match against a token detected in real tooltip lore
	 *  (EnchantLoreUtil.findTokens) first; if no rule matches, falls back to that level's tier override
	 *  when enabled. Null if neither applies. */
	public ResolvedStyle findMatch(String name, String levelText) {
		int level = RomanNumeralUtil.parseLevel(levelText);
		if (level <= 0) return null;
		for (Rule rule : rules) {
			if (rule.level == level && rule.name.equalsIgnoreCase(name)) return ResolvedStyle.of(rule);
		}
		if (level >= 1 && level <= tierOverrides.length) {
			TierOverride tier = tierOverrides[level - 1];
			if (tier.enabled) return ResolvedStyle.of(tier);
		}
		return null;
	}

	private static JsonObject saveRule(Rule rule) {
		JsonObject obj = new JsonObject();
		obj.addProperty("name", rule.name);
		obj.addProperty("levelInput", rule.levelInput);
		obj.addProperty("level", rule.level);
		obj.addProperty("color", rule.color);
		obj.addProperty("bold", rule.bold);
		obj.addProperty("tilted", rule.tilted);
		obj.addProperty("chromaEnabled", rule.chromaEnabled);
		obj.addProperty("chromaSize", rule.chromaSize);
		obj.addProperty("chromaSpeed", rule.chromaSpeed);
		obj.addProperty("chromaSaturation", rule.chromaSaturation);
		return obj;
	}

	private static Rule ruleFromJson(JsonObject obj) {
		String name = obj.has("name") ? obj.get("name").getAsString() : "";
		int level = obj.has("level") ? obj.get("level").getAsInt() : 1;
		Rule rule = new Rule(name, level);
		if (obj.has("levelInput")) rule.levelInput = obj.get("levelInput").getAsString();
		if (obj.has("color")) rule.color = obj.get("color").getAsInt();
		if (obj.has("bold")) rule.bold = obj.get("bold").getAsBoolean();
		if (obj.has("tilted")) rule.tilted = obj.get("tilted").getAsBoolean();
		if (obj.has("chromaEnabled")) rule.chromaEnabled = obj.get("chromaEnabled").getAsBoolean();
		if (obj.has("chromaSize")) rule.chromaSize = obj.get("chromaSize").getAsFloat();
		if (obj.has("chromaSpeed")) rule.chromaSpeed = obj.get("chromaSpeed").getAsFloat();
		if (obj.has("chromaSaturation")) rule.chromaSaturation = obj.get("chromaSaturation").getAsFloat();
		return rule;
	}

	private static JsonObject saveTierOverride(TierOverride tier) {
		JsonObject obj = new JsonObject();
		obj.addProperty("enabled", tier.enabled);
		obj.addProperty("color", tier.color);
		obj.addProperty("bold", tier.bold);
		obj.addProperty("tilted", tier.tilted);
		return obj;
	}

	private static void tierOverrideFromJson(TierOverride tier, JsonObject obj) {
		if (obj.has("enabled")) tier.enabled = obj.get("enabled").getAsBoolean();
		if (obj.has("color")) tier.color = obj.get("color").getAsInt();
		if (obj.has("bold")) tier.bold = obj.get("bold").getAsBoolean();
		if (obj.has("tilted")) tier.tilted = obj.get("tilted").getAsBoolean();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray array = new JsonArray();
		for (Rule rule : rules) array.add(saveRule(rule));
		JsonArray tierArray = new JsonArray();
		for (TierOverride tier : tierOverrides) tierArray.add(saveTierOverride(tier));
		JsonObject obj = new JsonObject();
		obj.add("rules", array);
		obj.add("tierOverrides", tierArray);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("rules")) {
			rules.clear();
			for (JsonElement el : obj.getAsJsonArray("rules")) {
				rules.add(ruleFromJson(el.getAsJsonObject()));
			}
		}
		if (obj.has("tierOverrides")) {
			JsonArray tierArray = obj.getAsJsonArray("tierOverrides");
			for (int i = 0; i < tierOverrides.length && i < tierArray.size(); i++) {
				tierOverrideFromJson(tierOverrides[i], tierArray.get(i).getAsJsonObject());
			}
		}
	}

	@Override
	public String getDescription() {
		return "Lets you recolor or restyle specific enchantment + level combinations wherever they appear in item lore.";
	}
}
