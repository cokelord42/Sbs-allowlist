package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.combat.DamageSplashDetector;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shortens Hypixel's real floating damage-indicator numbers (the ArmorStand nametags spawned on hit —
 * see {@link DamageSplashDetector}) from raw comma-grouped digits (e.g. "1,400,000") to a K/M/B-suffixed
 * short form (e.g. "1.4M") — per user request, with a user-configurable decimal count. Ported in spirit
 * from NoammAddons' {@code DamageSplash.kt} (confirmed real regex/detection source). Purely a client-side
 * nametag override, never touches anything server-authoritative — the same technique NoammAddons' own
 * reference source uses for this exact feature.
 *
 * <p>Real bug found (per user report — "doesnt render fast enough. For a split second i see the original
 * damage message" — then again, after a first attempt at fixing this by rewriting {@code getCustomName()}
 * every game tick instead of every 4th, "still see it for a split second"): a game tick (up to 50ms) and a
 * render FRAME are not the same thing — the old tick-based rewrite could still leave Hypixel's raw splash
 * on screen for one or more render FRAMES before the next tick (and that rewrite) ever ran, especially at
 * an uncapped/high frame rate. No amount of scanning more often per tick closes that gap, because the gap
 * is tick-vs-frame, not "not often enough." Fixed by moving off the tick-poll model entirely:
 * {@link #computeOverrideName} is now called from {@code EntityRendererMixin}'s existing per-FRAME
 * {@code extractRenderState} hook (the exact point vanilla itself builds that frame's nametag), overriding
 * {@code EntityRenderState#nameTag} for THIS frame before it's ever drawn — so there is no tick to wait for
 * and therefore no frame where the raw number could render at all. Stateless by construction (recomputed
 * fresh from the entity's own untouched real customName every frame) rather than mutating the entity's
 * customName once and relying on that mutation outrunning the renderer.
 */
public class DamageTruncatorFeature extends Feature {
	// Same confirmed-real glyph set DamageSplashDetector already matches against, split into three groups
	// so the leading/trailing decoration survives the rewrite untouched — only the digit run in the middle
	// gets replaced.
	private static final Pattern DAMAGE_TEXT = Pattern.compile("^([✧✯]?)(\\d+)([⚔+✧❤♞☄✷ﬗ✯]*)$");

	// Matches NoammAddons' own addRandomColorCodes palette for a "true damage" (✧/✯-decorated) splash.
	private static final List<String> RAINBOW_COLORS = List.of("§6", "§c", "§e", "§f");

	private static DamageTruncatorFeature instance;

	private int decimals = 1;

	public DamageTruncatorFeature() {
		super("damage_truncator", "Damage Truncator", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Combat"; }

	/** Called from {@code EntityRendererMixin}'s per-frame {@code extractRenderState} hook — see this
	 *  class's own doc comment for why it has to run there and not on a tick. Returns the truncated/
	 *  recolored nametag Component to render this entity with this frame, or null to leave it alone
	 *  (feature off, not a damage splash, or a value under 1000 that already reads fine as-is). */
	public static Component computeOverrideName(Entity entity) {
		if (instance == null || !instance.isEnabled()) return null;
		if (!(entity instanceof ArmorStand) || !DamageSplashDetector.isDamageSplash(entity)) return null;
		Component customName = entity.getCustomName();
		if (customName == null) return null;
		String raw = customName.getString();
		Matcher matcher = DAMAGE_TEXT.matcher(raw.replace(",", ""));
		if (!matcher.matches()) return null;
		long value;
		try {
			value = Long.parseLong(matcher.group(2));
		} catch (NumberFormatException e) {
			return null;
		}
		// Below 1000 already reads fine as a plain number — nothing to truncate.
		if (value < 1000) return null;
		// Real bug found (per user report, twice over — "lacks color", then again "still has no color
		// coding" after the first fix): two separate real-data-driven attempts both came up empty —
		// forwarding the original Component's own Style color (there isn't one: DamageSplashDetector's
		// own confirmed regex requires the customName's plain getString() to be pure digits/decoration
		// glyphs with no embedded formatting at all, so there is nothing for a Style scan to find), and
		// then scoreboard team color (see EntityRendererMixin's doc comment for that being the real
		// vanilla mechanism for a teamed entity's nametag color — but these splash ArmorStands simply
		// aren't on a team either). Decompiling NoammAddons' own DamageSplash.kt (the confirmed source
		// this feature is already ported from) settles it: that mod doesn't detect or forward any real
		// per-splash color either — Hypixel sends these completely uncolored on the wire, and NoammAddons
		// just imposes its own fixed color scheme (flat dark aqua for a normal hit, a rotating rainbow
		// per digit for a "true damage" ✧/✯-decorated one). Porting that same real, confirmed scheme
		// here instead of continuing to chase per-splash color data that provably isn't there.
		boolean trueDamage = !matcher.group(1).isEmpty() || matcher.group(3).indexOf('✧') >= 0 || matcher.group(3).indexOf('✯') >= 0;
		String truncated = instance.truncate(value);
		String colored = trueDamage ? rainbow(truncated) : "§3" + truncated;
		return Component.literal(matcher.group(1) + colored + matcher.group(3));
	}

	// Ported from NoammAddons' addRandomColorCodes: a random legacy color per character, never repeating
	// the immediately preceding one, reset (§r) after each character so the trailing decoration glyph isn't
	// dragged into whatever color the last digit happened to land on.
	private static String rainbow(String text) {
		StringBuilder result = new StringBuilder();
		String lastColor = null;
		for (char c : text.toCharArray()) {
			String color;
			do {
				color = RAINBOW_COLORS.get(ThreadLocalRandom.current().nextInt(RAINBOW_COLORS.size()));
			} while (color.equals(lastColor));
			result.append(color).append(c).append("§r");
			lastColor = color;
		}
		return result.toString();
	}

	private String truncate(long value) {
		double scaled;
		String suffix;
		if (value >= 1_000_000_000L) { scaled = value / 1_000_000_000d; suffix = "B"; }
		else if (value >= 1_000_000L) { scaled = value / 1_000_000d; suffix = "M"; }
		else { scaled = value / 1_000d; suffix = "K"; }
		String formatted = String.format(Locale.ROOT, "%." + decimals + "f", scaled);
		// A whole-number result (e.g. "2.0" at 1 decimal) reads better without the trailing zero/dot —
		// per user request that 0 decimals should read as a plain integer, not "2." with a stray dot.
		if (decimals > 0 && formatted.contains(".")) {
			formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return formatted + suffix;
	}

	public int getDecimals() { return decimals; }
	public void setDecimals(int value) { decimals = Math.max(0, Math.min(4, value)); }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("decimals", decimals);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("decimals")) decimals = Math.max(0, Math.min(4, obj.get("decimals").getAsInt()));
	}

	@Override
	public String getDescription() {
		return "Shortens Hypixel's floating damage numbers from full digits (1,400,000) to a short form (1.4M).";
	}
}
