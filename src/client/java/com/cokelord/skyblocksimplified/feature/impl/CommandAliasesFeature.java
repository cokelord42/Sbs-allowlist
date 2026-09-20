package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Per user request — named "Command Aliases" here rather than literally "Chat Commands" (what the user
 * called it) to avoid colliding with the existing {@link ChatCommandsFeature} (a completely different
 * emote/party-shortcut feature that already owns that exact display name) — a plain row list, add/remove
 * like Positional Messages, where each row maps one real vanilla/Hypixel command a player might type to a
 * different one that's actually sent instead (the user's own example: "/warp dungeon hub" typed sends
 * "/dh" to the server instead). Matching is a whole-command, case-insensitive, leading-slash-optional
 * comparison — not a substring/prefix match — since the point is aliasing one full command to another, not
 * partial text replacement.
 *
 * <p>Hooks {@link ClientSendMessageEvents#MODIFY_COMMAND}, the same real Fabric API transform hook
 * {@link ChatCommandsFeature}'s own emote-in-commands feature already uses — its {@code command} string
 * never includes the leading slash (vanilla strips that before dispatch), matching this feature's own
 * normalization.
 */
public class CommandAliasesFeature extends Feature {
	public static final class Alias {
		// Stored with a leading slash always present — see MainScreen's collapseLeadingSlash, which keeps
		// the text field's displayed value identical to what's actually stored (no separate display-only
		// prefix to keep in sync). normalize() below strips it back off again for matching.
		public String triggerInput = "/";
		public String replacementInput = "/";
	}

	private final List<Alias> aliases = new ArrayList<>();

	private static CommandAliasesFeature instance;
	// ClientSendMessageEvents.MODIFY_COMMAND.register doesn't throw on a duplicate registration (unlike
	// HudElementRegistry), so onEnable() calling it unconditionally used to silently stack a second
	// permanent command-transform listener on every re-enable, each one re-running applyAlias() on whatever
	// the previous listener already produced. Same guarded-registration pattern already used correctly
	// elsewhere in this codebase (see PlayerDisplayFeature/ChatCopyFeature): register exactly once, ever.
	private static boolean listenersRegistered = false;

	public CommandAliasesFeature() {
		super("command_aliases", "Command Aliases", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ClientSendMessageEvents.MODIFY_COMMAND.register(command ->
			instance != null && instance.isEnabled() ? instance.applyAlias(command) : command);
	}

	private String applyAlias(String command) {
		String normalized = normalize(command);
		if (normalized.isEmpty()) return command;
		for (Alias alias : aliases) {
			String trigger = normalize(alias.triggerInput);
			if (!trigger.isEmpty() && trigger.equalsIgnoreCase(normalized)) {
				String replacement = normalize(alias.replacementInput);
				return replacement.isEmpty() ? command : replacement;
			}
		}
		return command;
	}

	private static String normalize(String s) {
		if (s == null) return "";
		s = s.trim();
		if (s.startsWith("/")) s = s.substring(1);
		return s.trim();
	}

	// Per user report ("The command aliases module doesnt work... Its very alike the odin chat commands we
	// added (/m7 runs /joininstance master_catacombs_floor_seven)"): MODIFY_COMMAND above only ever takes
	// effect for a command Minecraft/Hypixel doesn't ALREADY recognize as something else — if the chosen
	// trigger word happens to also parse against ANYTHING already in the client's merged command tree
	// (this mod's own /f1-f7 /m1-m7 /t1-t5 shortcuts, another mod's client command, or even a command
	// Hypixel merely ADVERTISES for tab-completion without the client being able to use it), Fabric's own
	// client-command dispatch can claim and short-circuit it before this rewrite ever runs — a real, if
	// narrow, gap the exact "/m7 runs /joininstance..." mechanism the user points at doesn't have, since
	// that one is a REAL locally-registered command that always wins outright. Single-word triggers (the
	// overwhelmingly common case — the user's own example is one word, "/dh") are now ALSO registered as
	// real client-side Brigadier commands at login time (see SkyblockSimplifiedClient's own
	// ClientCommandRegistrationCallback block, which calls the two methods below) — the exact same
	// mechanism, so a single-word alias always wins locally regardless of anything else in the merged
	// command tree, with no dependence on event/mixin ordering at all. Multi-word triggers (e.g. "warp
	// home") can't be represented as one Brigadier literal this way and still rely on MODIFY_COMMAND alone.

	/** Every currently-configured trigger that's a single word (no spaces once normalized) — safe to
	 *  register as a real Brigadier literal. Read once at login time by the client command registration
	 *  block; an alias added or renamed later in the same session still gets the MODIFY_COMMAND fallback
	 *  above until the next login rebuilds the command tree. */
	public List<String> singleWordTriggerSnapshot() {
		List<String> result = new ArrayList<>();
		for (Alias alias : aliases) {
			String trigger = normalize(alias.triggerInput);
			if (!trigger.isEmpty() && !trigger.contains(" ")) result.add(trigger);
		}
		return result;
	}

	/** The real command to send for a given (already-normalized) trigger, looked up fresh against whatever
	 *  aliases exist RIGHT NOW — not a snapshot from registration time — so editing an alias's replacement
	 *  text takes effect on the very next press without needing to relog. Null if no current alias still
	 *  has this exact trigger (e.g. the user renamed or removed it since login) or its replacement is empty. */
	public String findReplacementForTrigger(String normalizedTrigger) {
		for (Alias alias : aliases) {
			String trigger = normalize(alias.triggerInput);
			if (!trigger.isEmpty() && trigger.equalsIgnoreCase(normalizedTrigger)) {
				String replacement = normalize(alias.replacementInput);
				return replacement.isEmpty() ? null : replacement;
			}
		}
		return null;
	}

	// ---- GUI editing surface (called from MainScreen) ----
	public List<Alias> getAliases() { return aliases; }

	public void addAlias() {
		aliases.add(new Alias());
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	public void removeAlias(Alias alias) {
		aliases.remove(alias);
		com.cokelord.skyblocksimplified.config.ConfigManager.save();
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray array = new JsonArray();
		for (Alias alias : aliases) {
			JsonObject obj = new JsonObject();
			obj.addProperty("trigger", alias.triggerInput);
			obj.addProperty("replacement", alias.replacementInput);
			array.add(obj);
		}
		return array;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		aliases.clear();
		if (!data.isJsonArray()) return;
		for (JsonElement el : data.getAsJsonArray()) {
			if (!el.isJsonObject()) continue;
			JsonObject obj = el.getAsJsonObject();
			Alias alias = new Alias();
			if (obj.has("trigger")) alias.triggerInput = obj.get("trigger").getAsString();
			if (obj.has("replacement")) alias.replacementInput = obj.get("replacement").getAsString();
			aliases.add(alias);
		}
	}

	@Override
	public String getDescription() {
		return "Lets you define your own short aliases that expand into full chat commands when typed.";
	}
}
