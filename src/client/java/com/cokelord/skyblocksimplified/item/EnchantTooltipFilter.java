package com.cokelord.skyblocksimplified.item;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.CustomEnchantParsingFeature;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * One shared tooltip listener for both enchant-related lore toggles — Hide Vanilla Enchants (the real
 * Enchantments/StoredEnchantments data-component lines vanilla auto-inserts right under the item name,
 * separate from Hypixel's own lore-based enchant listing) and Hide Enchant Description (Hypixel only
 * prints each enchant's italic flavor-text line when the item has few enough enchants to fit them; this
 * hides them in that case too, using the same enchant-line detection EnchantLoreUtil provides).
 */
public final class EnchantTooltipFilter {
	private static boolean registered = false;

	private EnchantTooltipFilter() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (isOn("hide_vanilla_enchants")) hideVanillaEnchants(stack, lines);
			if (isOn("hide_enchant_description")) hideEnchantDescriptions(lines);
			if (isOn("custom_enchant_parsing")) restyleCustomEnchants(lines);
		});
	}

	// Vanilla always inserts these lines immediately after the display name (index 0), before any custom
	// Hypixel lore, with exactly one line per real enchantment — so removing `count` lines starting at
	// index 1 removes exactly (and only) vanilla's own auto-generated block.
	private static void hideVanillaEnchants(net.minecraft.world.item.ItemStack stack, List<Component> lines) {
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		int count = (enchantments != null ? enchantments.size() : 0) + (stored != null ? stored.size() : 0);
		if (count == 0 || lines.size() <= 1) return;
		int toRemove = Math.min(count, lines.size() - 1);
		for (int i = 0; i < toRemove; i++) {
			lines.remove(1);
		}
	}

	private static void hideEnchantDescriptions(List<Component> lines) {
		int enchantLineCount = 0;
		for (Component line : lines) {
			if (EnchantLoreUtil.isEnchantLine(line.getString())) enchantLineCount++;
		}
		// Hypixel already omits descriptions once there are 10+ enchants — nothing to hide there.
		if (enchantLineCount == 0 || enchantLineCount >= 10) return;
		for (int i = lines.size() - 1; i >= 1; i--) {
			Component prev = lines.get(i - 1);
			Component cur = lines.get(i);
			// getStyle() only reads the component's OWN top-level style, not the resolved style of its
			// text (which can carry italic on a nested sibling instead) — the same style-inheritance gap
			// already confirmed and fixed for restyleWithSpans above. That mismatch meant an italic
			// description line's isItalic() check came back false and this never removed anything.
			if (EnchantLoreUtil.isEnchantLine(prev.getString()) && isAnyRunItalic(cur)) {
				lines.remove(i);
			}
		}
	}

	private static boolean isAnyRunItalic(Component component) {
		return component.visit((style, text) -> style.isItalic() && !text.isEmpty()
			? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.<Boolean>empty(), Style.EMPTY).isPresent();
	}

	/** Scans every lore line for "Name Level" tokens (EnchantLoreUtil.findTokens) and, for each one that
	 *  matches a configured rule, splices a re-styled Component in over just that run — the rest of the
	 *  line (other enchants, stacking counts, commas) is rebuilt unchanged from the original plain text.
	 *  Runs every time the tooltip is composed (i.e. every frame it's shown), same as the HUD's own
	 *  chroma, so chroma-enabled rules animate live rather than freezing at whatever hue they started on. */
	private record MatchedSpan(int start, int end, CustomEnchantParsingFeature.ResolvedStyle style) {}

	private static void restyleCustomEnchants(List<Component> lines) {
		if (!(FeatureRegistry.get("custom_enchant_parsing") instanceof CustomEnchantParsingFeature feature) || !feature.hasAnyActiveRule()) return;
		for (int i = 0; i < lines.size(); i++) {
			Component original = lines.get(i);
			String text = original.getString();
			List<EnchantLoreUtil.EnchantToken> tokens = EnchantLoreUtil.findTokens(text);
			if (tokens.isEmpty()) continue;

			// Collect only the tokens that actually match a rule — everything else on the line (other
			// enchants sharing the same lore line, separating commas, stack counts) must keep its own
			// original per-run style, not fall back to a flat default. Rebuilding unmatched spans from
			// plain text (Component.literal on a getString() substring) discarded that original styling
			// entirely, which read as "the whole line" changing color whenever a matched enchant shared
			// its line with an unmatched one.
			List<MatchedSpan> spans = new ArrayList<>();
			for (EnchantLoreUtil.EnchantToken token : tokens) {
				CustomEnchantParsingFeature.ResolvedStyle style = feature.findMatch(token.name(), token.levelText());
				if (style == null) continue;
				// Per user request: the comma separating this enchant from the next one on the same line
				// should pick up the same color too, not stay in the line's original style.
				int end = token.end();
				if (end < text.length() && text.charAt(end) == ',') end++;
				spans.add(new MatchedSpan(token.start(), end, style));
			}
			if (spans.isEmpty()) continue;

			lines.set(i, restyleWithSpans(original, text, spans));
		}
	}

	/** Walks {@code original}'s real styled runs (Component#visit resolves nested-sibling style
	 *  inheritance for us) and re-emits each one, splitting a run at any matched-span boundary that falls
	 *  inside it — matched characters get the rule's style, every other character keeps exactly the style
	 *  it already had instead of falling back to a flat default. */
	private static Component restyleWithSpans(Component original, String fullText, List<MatchedSpan> spans) {
		MutableComponent result = Component.empty();
		int[] cursor = {0};
		original.visit((style, runText) -> {
			int runStart = cursor[0];
			int runEnd = runStart + runText.length();
			cursor[0] = runEnd;

			int pos = runStart;
			while (pos < runEnd) {
				MatchedSpan active = null;
				for (MatchedSpan span : spans) {
					if (span.start() <= pos && pos < span.end()) {
						active = span;
						break;
					}
				}
				// Real bug found (per user report — real crash log, StringIndexOutOfBoundsException on
				// runText.substring once a SECOND enchant rule was added): this used to clamp segmentEnd using
				// ANY span's end the instant ANY span was active for this position, not specifically the
				// active span's own end — with two-plus rules matched on the same line, an EARLIER span
				// (already closed before this run even starts) could clamp segmentEnd down to a value LESS
				// than runStart, producing a negative-length substring range. Only the active span's own end
				// (bounded by the current style run) may ever shrink segmentEnd now.
				int segmentEnd = runEnd;
				if (active != null) {
					segmentEnd = Math.min(segmentEnd, active.end());
				} else {
					for (MatchedSpan span : spans) {
						if (span.start() > pos) segmentEnd = Math.min(segmentEnd, span.start());
					}
				}
				String segmentText = runText.substring(pos - runStart, segmentEnd - runStart);
				if (active != null) {
					result.append(buildStyledToken(active.style(), segmentText));
				} else {
					result.append(Component.literal(segmentText).setStyle(style));
				}
				pos = segmentEnd;
			}
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return result;
	}

	/** Builds a Component for one matched "Name Level" run with the rule's chosen style. Chroma cycles
	 *  each character's hue independently (mirroring CustomScoreboardFeature.drawTextLine's approach for
	 *  its own chroma text), which needs a real per-character Style/TextColor since — unlike the HUD's
	 *  own draw calls — vanilla's tooltip renderer has no "default tint" argument to fall back on. */
	private static Component buildStyledToken(CustomEnchantParsingFeature.ResolvedStyle style, String text) {
		Style baseStyle = Style.EMPTY.withBold(style.bold()).withItalic(style.tilted());
		if (!style.chromaEnabled() || text.isEmpty()) {
			return Component.literal(text).setStyle(baseStyle.withColor(TextColor.fromRgb(style.color() & 0xFFFFFF)));
		}
		MutableComponent result = Component.empty();
		double t = System.currentTimeMillis() / 1000.0 * style.chromaSpeed();
		int len = text.length();
		for (int i = 0; i < len; i++) {
			float progress = (i + 0.5f) / len;
			float hue = (float) ((t * 360.0 + progress * 360.0 * style.chromaSize()) % 360.0);
			int rgb = RenderUtil.hsvToRgb(hue, style.chromaSaturation(), 1f) & 0xFFFFFF;
			result.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(baseStyle.withColor(TextColor.fromRgb(rgb))));
		}
		return result;
	}

	private static boolean isOn(String featureId) {
		Feature feature = FeatureRegistry.get(featureId);
		return feature != null && feature.isEnabled();
	}
}
