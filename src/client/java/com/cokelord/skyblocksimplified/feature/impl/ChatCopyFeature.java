package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.keybind.KeyCombo;
import com.cokelord.skyblocksimplified.mixin.ChatComponentAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies a chat line's full plain text to the clipboard: press the configured keybind while hovering a
 * line with the mouse (chat must be focused — press Enter/T first, same as vanilla's own click-a-link
 * behavior, since that's the only time chat actually receives mouse input at all) — per user request, for
 * players without another mod that already does this.
 *
 * <p>Per user follow-up ("instead of doing Hold + right click the user can just select a bind instead of
 * needing to right click, and if they click the bind it should copy the currently hovered chat message"):
 * this used to require holding the configured combo AND right-clicking the line; now a single press of the
 * combo alone copies whatever line the mouse is currently over. Detected via an edge-triggered tick poll
 * (KeyCombo has no built-in "just pressed" state, only isHeld()) rather than a mouseClicked mixin, since
 * there's no click to intercept anymore — reads the live cursor position off MouseHandler instead of a
 * click event's coordinates.
 *
 * <p>Chat's own click-handling (ChatScreen/ChatComponent) resolves clicks down to a Style via a newer
 * ActiveTextCollector-based API this project doesn't otherwise need — rather than depend on that, this
 * replicates vanilla's own well-known, stable chat layout math directly (bottom-anchored at
 * screenHeight-40, each line getLineHeight() tall, scaled by the chatScale option) to find which real
 * chat line the cursor is over, then copies that line's ORIGINAL (untrimmed) message text — not just the
 * wrapped visual segment hovered — via GuiMessage.Line.parent().content(), matching what most players
 * mean by "copy this chat message."
 */
public class ChatCopyFeature extends Feature {
	private static final int BOTTOM_MARGIN = 40;
	private static final int CHAT_X0 = 2;

	private final KeyCombo combo = new KeyCombo();
	private boolean wasHeld = false;
	private static boolean listenersRegistered = false;
	private static ChatCopyFeature instance;

	public ChatCopyFeature() {
		super("chat_copy", "Right-Click Chat To Copy", FeatureCategory.INVENTORY, false);
		instance = this;
	}

	@Override
	public String getSubcategory() {
		return "Misc";
	}

	@Override
	public KeyCombo getPrimaryKeyCombo() {
		return combo;
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (instance == null) return;
			if (!instance.isEnabled() || instance.combo.isEmpty()) {
				instance.wasHeld = false;
				return;
			}
			boolean held = instance.combo.isHeld();
			if (held && !instance.wasHeld && client.gui.screen() instanceof ChatScreen) {
				double mouseX = client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth();
				double mouseY = client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight();
				instance.tryCopyAt(mouseX, mouseY);
			}
			instance.wasHeld = held;
		});
	}

	/** Copies whichever chat line the given (GUI-scaled) coordinates land on, if any. */
	private boolean tryCopyAt(double mouseX, double mouseY) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		ChatComponent chat = mc.gui.hud.getChat();
		ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
		List<GuiMessage.Line> lines = accessor.skyblocksimplified$getTrimmedMessages();
		if (lines.isEmpty()) return false;

		double chatScale = mc.options.chatScale().get();
		if (chatScale <= 0) return false;
		int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
		if (lineHeight <= 0) return false;

		double localX = (mouseX - CHAT_X0) / chatScale;
		double localY = (mc.getWindow().getGuiScaledHeight() - BOTTOM_MARGIN - mouseY) / chatScale;
		// Real bug found (user report: "The copy chat line is working when not hovering a line, i.e
		// outside the chat box entirely") — this used to only check localX/localY >= 0, with no UPPER
		// bound at all, so any point above-left of the chat's bottom-left anchor still resolved to SOME
		// backing trimmedMessages index (however far up in history) and copied it, even when it was well
		// outside the box actually drawn on screen — empty space above a short chat history, or past its
		// configured width. ChatComponent.getWidth/getHeight (both public static, confirmed via javap)
		// compute the real local-pixel box vanilla itself renders into (chat is always focused here, since
		// this only runs while a ChatScreen is open, so chatHeightFocused is the right option to mirror).
		double chatWidthPixels = ChatComponent.getWidth(mc.options.chatWidth().get());
		double chatHeightPixels = ChatComponent.getHeight(mc.options.chatHeightFocused().get());
		if (localX < 0 || localY < 0 || localX >= chatWidthPixels || localY >= chatHeightPixels) return false;

		int lineFromBottom = (int) (localY / lineHeight);
		int scrollbarPos = accessor.skyblocksimplified$getChatScrollbarPos();
		// ChatComponent's own trimmedMessages list is index-0-newest (addMessageToDisplayQueue prepends via
		// addFirst(), confirmed via bytecode) — the bottom/most-recent visible line (lineFromBottom == 0) is
		// exactly index `scrollbarPos` into that list, not `lines.size() - 1 - scrollbarPos`, which is the
		// formula for an OLDEST-first list. That inverted assumption was the actual "copies from the top
		// instead of the hovered line" bug — every click resolved to (approximately) the mirror-opposite
		// line from whichever one was actually under the cursor.
		int index = scrollbarPos + lineFromBottom;
		if (index < 0 || index >= lines.size()) return false;

		Component fullMessage = lines.get(index).parent().content();
		String text = fullMessage.getString().replaceAll("§.", "");
		if (text.isBlank()) return false;

		mc.keyboardHandler.setClipboard(text);
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§7Copied chat line to clipboard."));
		return true;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonArray comboArr = new JsonArray();
		for (String s : combo.serialize()) comboArr.add(new JsonPrimitive(s));
		JsonObject obj = new JsonObject();
		obj.add("combo", comboArr);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!(data instanceof JsonObject obj)) return;
		if (obj.has("combo") && obj.get("combo").isJsonArray()) {
			List<String> serialized = new ArrayList<>();
			for (JsonElement e : obj.getAsJsonArray("combo")) serialized.add(e.getAsString());
			combo.setKeys(KeyCombo.deserialize(serialized).getKeys());
		}
	}

	@Override
	public String getDescription() {
		return "Lets you copy a chat line's text to your clipboard with a keybind while hovering over it.";
	}
}
