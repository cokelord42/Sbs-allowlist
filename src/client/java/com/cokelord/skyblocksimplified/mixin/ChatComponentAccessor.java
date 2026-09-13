package com.cokelord.skyblocksimplified.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exposes ChatComponent's private trimmedMessages (the actual wrapped, on-screen chat lines, newest last)
 *  and chatScrollbarPos (how many lines scrolled up from the bottom) — needed to resolve which real chat
 *  line a screen click landed on, for ChatCopyFeature's "copy this line" hold-keybind. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("trimmedMessages")
	List<GuiMessage.Line> skyblocksimplified$getTrimmedMessages();

	@Accessor("chatScrollbarPos")
	int skyblocksimplified$getChatScrollbarPos();
}
