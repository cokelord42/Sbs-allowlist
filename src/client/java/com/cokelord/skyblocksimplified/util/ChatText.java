package com.cokelord.skyblocksimplified.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;
import java.util.regex.Pattern;

/** Chat helpers: legacy "§"-coded rendering of a Component (for porting regexes written against formatted
 *  chat text, e.g. SBO's) and small send/print shortcuts. */
public final class ChatText {
	private static final Pattern FORMAT_CODES = Pattern.compile("§.");

	private static final java.util.Map<String, Character> COLOR_CODES = new java.util.HashMap<>();
	static {
		String codes = "0123456789abcdef";
		ChatFormatting[] values = ChatFormatting.values();
		for (int i = 0; i < 16; i++) COLOR_CODES.put(values[i].name().toLowerCase(java.util.Locale.ROOT), codes.charAt(i));
	}

	private ChatText() {}

	/** Same shape as SBO's {@code Component.formattedString()}: each styled run is prefixed with its color
	 *  code (named colors only) followed by bold/italic/underline/strikethrough/obfuscated codes. Literal
	 *  "§" codes Hypixel embeds inside text are kept as-is. */
	public static String legacy(Component component) {
		StringBuilder out = new StringBuilder();
		component.visit((style, content) -> {
			appendCodes(out, style);
			out.append(content);
			return Optional.empty();
		}, Style.EMPTY);
		return out.toString();
	}

	private static void appendCodes(StringBuilder out, Style style) {
		TextColor color = style.getColor();
		if (color != null) {
			Character code = COLOR_CODES.get(color.serialize());
			if (code != null) out.append('§').append(code);
		}
		if (style.isBold()) out.append("§l");
		if (style.isItalic()) out.append("§o");
		if (style.isUnderlined()) out.append("§n");
		if (style.isStrikethrough()) out.append("§m");
		if (style.isObfuscated()) out.append("§k");
	}

	public static String strip(String text) {
		return text == null ? "" : FORMAT_CODES.matcher(text).replaceAll("");
	}

	public static void clientMessage(String legacyText) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal(legacyText));
	}

	public static void clientMessage(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(component);
	}

	/** Runs a server command (no leading slash). */
	/** Queued onto the main loop (like Odin's sendCommand): callers are often chat handlers, and sending while
	 *  an incoming signed chat line is still being processed can get the command silently rejected. */
	public static void command(String command) {
		Minecraft mc = Minecraft.getInstance();
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		mc.execute(() -> {
			if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand(cmd);
		});
	}

	public static void partyChat(String message) {
		command("pc " + message);
	}

	public static void title(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.gui.hud.setTimes(fadeIn, stay, fadeOut);
		if (subtitle != null) mc.gui.hud.setSubtitle(Component.literal(subtitle));
		mc.gui.hud.setTitle(Component.literal(title));
	}
}
