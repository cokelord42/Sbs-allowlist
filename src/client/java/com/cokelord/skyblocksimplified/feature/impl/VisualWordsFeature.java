package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find/replace for visible chat text — per user request, a "+" list of (find, replace) pairs, same shape
 * as Positional Messages' add-a-row UI. Every occurrence of "find" anywhere in a message becomes
 * "replace", keeping whatever color/formatting that text already had: no new style is introduced — each
 * already-same-styled text run is transformed as one plain string, then re-wrapped in its own original
 * {@link Style}, never a different one.
 *
 * <p>Scoped to GAME messages only — Fabric's {@code ClientReceiveMessageEvents} has no MODIFY_CHAT
 * counterpart (real player chat is cryptographically signed to its exact text, and Fabric doesn't offer
 * rewriting its displayed content the way GAME/system messages allow) — which covers Hypixel's own
 * system/boss/action-bar text, not real player-typed party/guild/public chat.
 *
 * <p>Matches are found per already-styled text run, not across run boundaries — a search term split
 * across two differently-styled pieces of the same message (rare in practice, e.g. a rainbow-colored
 * word) won't be found. Good enough for the common case of a whole word/phrase sharing one color; not
 * attempted as a full cross-run rebuild.
 */
public class VisualWordsFeature extends Feature {
	public static final class WordReplacement {
		public String find = "";
		public String replace = "";
		// Perf: replacedText() used to call Pattern.compile fresh for every rule on every single chat
		// component (and every sibling of it) of every incoming message — dungeon chat especially is dense
		// enough that this added up to real repeated regex-compilation work for text that's usually
		// identical rule to rule. transient so Gson (this class round-trips through config save/load)
		// doesn't try to persist a compiled Pattern; cache is invalidated automatically whenever find text
		// actually changes, so no visual/behavioral difference versus recompiling every time.
		private transient Pattern cachedPattern;
		private transient String cachedFor;

		Pattern compiledPattern() {
			if (cachedPattern == null || !find.equals(cachedFor)) {
				cachedPattern = Pattern.compile(Pattern.quote(find), Pattern.CASE_INSENSITIVE);
				cachedFor = find;
			}
			return cachedPattern;
		}
	}

	private final List<WordReplacement> replacements = new ArrayList<>();

	private static boolean listenerRegistered = false;
	private static VisualWordsFeature instance;

	public VisualWordsFeature() {
		super("visual_words", "Visual Words", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Misc";
	}

	@Override
	protected void onEnable() {
		if (!listenerRegistered) {
			listenerRegistered = true;
			ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
				if (instance == null || !instance.isEnabled() || instance.replacements.isEmpty()) return message;
				try {
					return instance.rewrite(message);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Visual Words rewrite failed, leaving the message unmodified", e);
					return message;
				}
			});
			// Per user request ("the visual words should change EVERY possible word, not just chat. That
			// includes item lore, item names..."): item tooltips are a completely separate rendering path from
			// chat (real ItemStack display-name/lore Components built by ItemTooltipCallback, never funneled
			// through ClientReceiveMessageEvents at all) — the ORIGINAL "not replacing anything" report was a
			// real user testing this by hovering an item (e.g. "Hyperion"), which the chat-only hook above could
			// never have caught regardless of the case-sensitivity bug fixed below. Line 0 of `lines` is always
			// the item's own display name (vanilla inserts it before this callback fires), so rewriting every
			// line here covers both the name and the real lore in one pass. Tablist/scoreboard text use yet
			// other, separate rendering paths not covered by this round's fix — flagged, not attempted blind.
			ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
				if (instance == null || !instance.isEnabled() || instance.replacements.isEmpty()) return;
				try {
					for (int i = 0; i < lines.size(); i++) {
						lines.set(i, instance.rewrite(lines.get(i)));
					}
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Visual Words tooltip rewrite failed, leaving the tooltip unmodified", e);
				}
			});
		}
	}

	private Component rewrite(Component original) {
		MutableComponent result;
		if (original.getContents() instanceof PlainTextContents ptc) {
			result = replacedText(ptc.text(), original.getStyle());
		} else {
			// Non-plain content (translatable text, keybind placeholders, score values, etc.) — nothing to
			// find/replace text in, just carry it (and its style) through unchanged.
			result = MutableComponent.create(original.getContents()).setStyle(original.getStyle());
		}
		for (Component sibling : original.getSiblings()) {
			result.append(rewrite(sibling));
		}
		// Real bug found (per user report — "visual words doesnt work in the lore on the chat hover /show
		// command"): Hypixel's /show and similar item-link hover tooltips are delivered as a
		// HoverEvent.ShowText nested Component tree attached to the message's own Style, completely separate
		// from the visible text walked above via getSiblings() — this only ever recursed into the visible
		// sibling chain, never into a Style's own HoverEvent payload, so a hovered item's lore text (itself
		// just another Component tree, same shape as any chat message) never got rewritten. Rewriting it here
		// and swapping the style's HoverEvent covers it the same way as any other chat text.
		if (original.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText) {
			Component rewrittenHover = rewrite(showText.value());
			result.setStyle(result.getStyle().withHoverEvent(new HoverEvent.ShowText(rewrittenHover)));
		}
		return result;
	}

	/** Used by {@link com.cokelord.skyblocksimplified.mixin.VisualWordsActionBarMixin} — see its own doc
	 *  comment for why the vanilla action bar (both server-pushed actionbar text and the local
	 *  hotbar-slot-swap item name) needs its own separate hook instead of the chat-message rewrite above. */
	public static Component rewriteIfEnabled(Component component) {
		if (instance == null || !instance.isEnabled() || instance.replacements.isEmpty()) return component;
		try {
			return instance.rewrite(component);
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Visual Words action bar rewrite failed, leaving the message unmodified", e);
			return component;
		}
	}

	// Real bug found (per user report — "I typed Hyperion and all text that is Hyperion does not change.
	// Make sure its not case sensitive"): String#replace does a literal, case-SENSITIVE substring match — a
	// real message reading "you found a HYPERION" or "hyperion" never matched a configured "Hyperion" rule
	// at all. Pattern.quote keeps the user's find text literal (no accidental regex metacharacters), and
	// Matcher.quoteReplacement does the same for the replacement text on the other side.
	private MutableComponent replacedText(String text, Style style) {
		String result = text;
		for (WordReplacement r : replacements) {
			if (r.find != null && !r.find.isEmpty()) {
				result = r.compiledPattern().matcher(result).replaceAll(Matcher.quoteReplacement(r.replace != null ? r.replace : ""));
			}
		}
		return MutableComponent.create(PlainTextContents.create(result)).setStyle(style);
	}

	// ---- GUI editing surface (called from MainScreen) ----
	public List<WordReplacement> getReplacements() {
		return replacements;
	}

	public void addReplacement() {
		replacements.add(new WordReplacement());
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void removeReplacement(int index) {
		if (index < 0 || index >= replacements.size()) return;
		replacements.remove(index);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray array = new JsonArray();
		for (WordReplacement r : replacements) {
			JsonObject obj = new JsonObject();
			obj.addProperty("find", r.find);
			obj.addProperty("replace", r.replace);
			array.add(obj);
		}
		JsonObject obj = new JsonObject();
		obj.add("replacements", array);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		replacements.clear();
		if (obj.has("replacements")) {
			for (JsonElement el : obj.getAsJsonArray("replacements")) {
				JsonObject e = el.getAsJsonObject();
				WordReplacement r = new WordReplacement();
				if (e.has("find") && !e.get("find").isJsonNull()) r.find = e.get("find").getAsString();
				if (e.has("replace") && !e.get("replace").isJsonNull()) r.replace = e.get("replace").getAsString();
				replacements.add(r);
			}
		}
	}

	@Override
	public String getDescription() {
		return "Find-and-replace for chat text: every occurrence of a word/phrase you set is swapped for your replacement.";
	}
}
