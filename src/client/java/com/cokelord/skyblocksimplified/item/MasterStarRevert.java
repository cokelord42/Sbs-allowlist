package com.cokelord.skyblocksimplified.item;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared "Revert Master Stars" text transform (per user request: "I like the old ones more where they turn
 * red" — ported from SkyOcean's RevertMasterStarModifier). Originally lived only inside
 * {@link InventoryTooltipFilter} as a tooltip-only transform, but the exact same "[Item Name ✪✪✪✪✪➍]" text
 * also appears verbatim in two other places Hypixel renders an item's hover name: the action-bar "held item"
 * popup shown when swapping hotbar slots ({@code Hud#extractSelectedItemName}, hooked by
 * {@link com.cokelord.skyblocksimplified.mixin.VisualWordsSelectedItemNameMixin}) and chat/game messages like
 * "X is holding [item]" (per user report — "Same with the chat in lore and text"). Extracted here so all
 * three call sites share one implementation instead of three copies.
 *
 * <p>Real regression found (per user report — "the revert master stars doesnt show anymore, the stars look
 * the same"): a prior round rewrote this to process each Component NODE's own text in isolation (matching
 * {@code VisualWordsFeature#rewrite}'s per-node shape), on the theory that the flat whole-tree design was the
 * bug. It wasn't — the flat design (restored below) exists specifically BECAUSE the 5-star-plus-digit run is
 * routinely split across more than one styled run (Hypixel doesn't necessarily give the five stars and the
 * trailing circled-digit glyph the same Style), so searching one node's own text in isolation can miss a
 * match that only exists once multiple runs' text is considered together. {@link #revert} walks the whole
 * tree via {@code Component#visit} as one logical string (exactly as it did before that regression) to find
 * the match regardless of which run(s) it spans, then rebuilds. What's actually new versus the pre-regression
 * version is just the hover-tooltip handling in {@link #revertIfEnabled} and the invisible-character
 * tolerance in {@link #STAR_RUN_PATTERN} — both additive, neither touches the core matching/rebuild shape.
 */
public final class MasterStarRevert {
	private static boolean chatHookRegistered = false;

	private MasterStarRevert() {}

	// The exact 5-orange-star run Hypixel appends after a dungeon item's name, followed by one of five
	// "circled digit" glyphs (➊-➎) that replaced individually-colored stars for showing master star
	// progress. Ported from SkyOcean's own RevertMasterStarModifier (source provided by the user) — same
	// literal glyphs/regex idea, adapted to this project's plain Component-splicing style (see
	// EnchantTooltipFilter#restyleWithSpans for the same "keep everything outside the match, rebuild only
	// the matched run" technique) instead of SkyOcean's own component-regex library. Pure text pattern
	// match, no item metadata needed: this exact glyph sequence only ever appears on a dungeon item that
	// actually has master stars, so matching it directly is sufficient by itself.
	private static final String MASTER_STAR_DIGITS = "➊➋➌➍➎";

	// Real bug found (per repeated user report — "still doesnt work in chat or chat lore"): PlayerDisplayFeature's
	// own extensive, already-confirmed debugging history (see its own INVISIBLE_CHARS constant/doc comments)
	// established that Hypixel deliberately injects invisible zero-width Unicode characters into various text
	// specifically to defeat naive third-party parsing — a plain exact-literal match silently fails the
	// instant one such character lands between two stars (or between the last star and its digit), with no
	// visible symptom at all. Same confirmed character class PlayerDisplayFeature already established,
	// allowed as an optional gap between each star/the trailing digit — can only make the match MORE
	// permissive than a bare literal substring, never break the plain case where no such character is present
	// (zero-or-more `*`).
	private static final String INVISIBLE_GAP = "[\\u200B-\\u200F\\u2060-\\u2064\\u202A-\\u202E\\uFEFF]*";
	private static final Pattern STAR_RUN_PATTERN = Pattern.compile(
		"✪" + INVISIBLE_GAP + "✪" + INVISIBLE_GAP + "✪" + INVISIBLE_GAP + "✪" + INVISIBLE_GAP + "✪" + INVISIBLE_GAP + "([➊➋➌➍➎])");

	/** Applies {@link #revert} only when the "Revert Master Stars" feature is enabled; otherwise returns the
	 *  component unchanged. This is what every call site should use, so none of them need their own toggle
	 *  check.
	 *
	 *  <p>Also reverts the message's own {@link HoverEvent.ShowText} tooltip (a chat line's "hover to see
	 *  lore/name" popup) when it has one — same real gap {@code VisualWordsFeature#rewrite} already found and
	 *  fixed for its own find/replace feature ("Hypixel's /show and similar item-link hover tooltips are
	 *  delivered as a nested Component tree attached to the message's own Style, completely separate from the
	 *  visible text"). {@link #revert}'s own flat "walk once, rebuild once" design only ever sees the VISIBLE
	 *  text — a master-star run sitting only inside the hover tooltip (not repeated in the visible line)
	 *  would never reach it otherwise. */
	public static Component revertIfEnabled(Component original) {
		if (!isOn()) return original;
		Component reverted = revert(original);
		if (original.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText) {
			Component revertedHover = revert(showText.value());
			MutableComponent mutable = reverted.copy();
			mutable.setStyle(mutable.getStyle().withHoverEvent(new HoverEvent.ShowText(revertedHover)));
			return mutable;
		}
		return reverted;
	}

	/** Registers the chat/game-message hook (mirrors {@code VisualWordsFeature}'s own
	 *  {@code ClientReceiveMessageEvents.MODIFY_GAME} registration) so broadcast messages like Hypixel's "X is
	 *  holding [item]" also get the master-star revert applied. Safe to call multiple times; only registers
	 *  once.
	 *
	 *  <p>Per user request ("Make it follow the visual words protocol for updating chat and chat lore like i
	 *  said"): uses {@link #revertChatIfEnabled} here (and in {@code MasterStarRevertChatMixin}), NOT the flat
	 *  {@link #revertIfEnabled} the tooltip/action-bar call sites use — see that method's own doc comment for
	 *  why chat specifically gets {@code VisualWordsFeature#rewrite}'s exact recursive shape instead. */
	public static synchronized void registerChatHook() {
		if (chatHookRegistered) return;
		chatHookRegistered = true;
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			try {
				return revertChatIfEnabled(message);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Revert Master Stars chat rewrite failed, leaving the message unmodified", e);
				return message;
			}
		});
	}

	/** Chat/chat-lore-specific revert — per user request ("Make it follow the visual words protocol for
	 *  updating chat and chat lore like i said"), this mirrors {@code VisualWordsFeature#rewrite} exactly:
	 *  revert THIS node's own plain text only ({@link #revertPlainTextNode}), recurse into every sibling
	 *  separately, and recurse into every node's own {@link HoverEvent.ShowText} tooltip — never flattening
	 *  text across node boundaries the way {@link #revert} does for tooltip/action-bar. Same documented,
	 *  accepted tradeoff Visual Words itself has ("Matches are found per already-styled text run, not across
	 *  run boundaries... Good enough for the common case") — a star run split across differently-styled
	 *  sub-runs won't revert via this path, same as a search term split across runs never matches for Visual
	 *  Words either. Used ONLY for the two real chat entry points ({@link #registerChatHook} and
	 *  {@code MasterStarRevertChatMixin}); tooltip/action-bar keep using {@link #revertIfEnabled}, which is
	 *  confirmed to already work and isn't changed here. */
	public static Component revertChatIfEnabled(Component original) {
		if (!isOn()) return original;
		return revertChatRecursive(original);
	}

	private static Component revertChatRecursive(Component original) {
		MutableComponent result;
		if (original.getContents() instanceof PlainTextContents ptc) {
			result = revertPlainTextNode(ptc.text(), original.getStyle());
		} else {
			// Non-plain content (translatable text, keybind placeholders, score values, etc.) — nothing to
			// revert text in, just carry it (and its style) through unchanged, same as VisualWordsFeature's
			// own identical fallback.
			result = MutableComponent.create(original.getContents()).setStyle(original.getStyle());
		}
		for (Component sibling : original.getSiblings()) {
			result.append(revertChatRecursive(sibling));
		}
		if (original.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText) {
			Component revertedHover = revertChatRecursive(showText.value());
			result.setStyle(result.getStyle().withHoverEvent(new HoverEvent.ShowText(revertedHover)));
		}
		return result;
	}

	/** Same star-recoloring rules as {@link #revert} (see its own doc comment), scoped to ONE node's own
	 *  plain text — the chat-specific counterpart of that method. */
	private static MutableComponent revertPlainTextNode(String text, Style style) {
		Matcher matcher = STAR_RUN_PATTERN.matcher(text);
		if (!matcher.find()) {
			return MutableComponent.create(PlainTextContents.create(text)).setStyle(style);
		}
		int masterStars = MASTER_STAR_DIGITS.indexOf(matcher.group(1).charAt(0)) + 1;
		if (masterStars <= 0) {
			return MutableComponent.create(PlainTextContents.create(text)).setStyle(style);
		}
		MutableComponent result = Component.empty();
		if (matcher.start() > 0) {
			result.append(Component.literal(text.substring(0, matcher.start())).setStyle(style));
		}
		int starsEmitted = 0;
		for (int i = matcher.start(); i < matcher.end() && starsEmitted < 5; i++) {
			if (text.charAt(i) != '✪') continue;
			boolean isMaster = starsEmitted < masterStars;
			result.append(Component.literal("✪").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(isMaster ? 0xFF5555 : 0xFFAA00)).withItalic(false)));
			starsEmitted++;
		}
		if (matcher.end() < text.length()) {
			result.append(Component.literal(text.substring(matcher.end())).setStyle(style));
		}
		return result;
	}

	/** Per user request ("I like the old ones more where they turn red"): rebuilds the star run so the
	 *  {@code masterStars} highest-tier stars (1-5, however many the circled digit encoded) are colored red
	 *  and the rest orange — same left-to-right ordering SkyOcean's own old-style renderer used (master
	 *  stars first, then the remaining base stars) — instead of 5 orange stars plus one circled-digit icon.
	 *  Walks the WHOLE component tree as one flat logical string via {@code Component#visit} (see this
	 *  class's own doc comment for why this can't be scoped to a single node's own text). */
	public static Component revert(Component original) {
		String text = original.getString();
		Matcher matcher = STAR_RUN_PATTERN.matcher(text);
		if (!matcher.find()) return original;
		int spanStart = matcher.start();
		int spanEnd = matcher.end();
		int masterStars = MASTER_STAR_DIGITS.indexOf(matcher.group(1).charAt(0)) + 1;
		if (masterStars <= 0) return original;

		MutableComponent result = Component.empty();
		int[] cursor = {0};
		// Counts real "✪" glyphs emitted so far within the matched span — NOT a raw character offset, since
		// the span can be longer than 6 characters when an invisible gap character is present (see
		// STAR_RUN_PATTERN's own doc comment). Every other character within the span (the digit, and any
		// invisible gap character) is simply dropped rather than repositioned.
		int[] starsEmitted = {0};
		original.visit((style, runText) -> {
			int runStart = cursor[0];
			int runEnd = runStart + runText.length();
			cursor[0] = runEnd;
			int pos = runStart;
			while (pos < runEnd) {
				if (pos < spanStart) {
					int segEnd = Math.min(runEnd, spanStart);
					result.append(Component.literal(runText.substring(pos - runStart, segEnd - runStart)).setStyle(style));
					pos = segEnd;
				} else if (pos < spanEnd) {
					char c = runText.charAt(pos - runStart);
					if (c == '✪' && starsEmitted[0] < 5) {
						boolean isMaster = starsEmitted[0] < masterStars;
						// Real bug found (per user report — "the master stars in the actionbar are italic for
						// some reason"): Hud#extractSelectedItemName wraps its ENTIRE returned name in
						// ChatFormatting.ITALIC whenever the stack has a custom name (confirmed via javap
						// disassembly — every Hypixel item qualifies), which cascades onto any run here that
						// leaves italic unset. Forced false explicitly so the star glyphs render upright
						// regardless of what ambient style wraps the component they end up embedded in.
						result.append(Component.literal("✪").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(isMaster ? 0xFF5555 : 0xFFAA00)).withItalic(false)));
						starsEmitted[0]++;
					}
					pos++;
				} else {
					int segEnd = runEnd;
					result.append(Component.literal(runText.substring(pos - runStart, segEnd - runStart)).setStyle(style));
					pos = segEnd;
				}
			}
			return Optional.<Boolean>empty();
		}, Style.EMPTY);
		return result;
	}

	private static boolean isOn() {
		Feature feature = FeatureRegistry.get("revert_master_stars");
		return feature != null && feature.isEnabled();
	}
}
