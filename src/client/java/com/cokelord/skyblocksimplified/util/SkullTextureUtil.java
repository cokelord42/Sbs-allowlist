package com.cokelord.skyblocksimplified.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

/** Extracts a player-head's skin-texture base64 value, from either a held/GUI ItemStack or a placed
 *  skull block — shared by every feature that identifies Hypixel's custom decorative heads by their
 *  exact skin texture (Flare, dungeon object hider, Wither Essence blocks). */
public final class SkullTextureUtil {
	private SkullTextureUtil() {}

	public static String fromItem(ItemStack stack) {
		if (!stack.is(Items.PLAYER_HEAD)) return null;
		return fromProfile(stack.get(DataComponents.PROFILE));
	}

	public static String fromBlockEntity(BlockEntity entity) {
		if (!(entity instanceof SkullBlockEntity skull)) return null;
		return fromProfile(skull.getOwnerProfile());
	}

	private static String fromProfile(ResolvableProfile profile) {
		if (profile == null) return null;
		GameProfile gameProfile = profile.partialProfile();
		for (Property property : gameProfile.properties().get("textures")) {
			return property.value();
		}
		return null;
	}

	// Reverse of fromItem() above — builds a real, correctly-skinned player-head ItemStack straight from a
	// base64 skin blob (the same shape Hypixel's own item-resource API hands back for every player-head-
	// based custom item — pets, hats, accessories, decorative blocks — see SkyblockItemRepo.ItemInfo).
	//
	// CONFIRMED real root cause (via direct bytecode inspection of the actual authlib-9.0.75.jar dependency
	// this project compiles against, not a guess this time): PropertyMap's own constructor internally does
	// `this.properties = ImmutableMultimap.copyOf(multimap)` — it snapshots whatever mutable Multimap you
	// pass it into an IMMUTABLE one immediately. Every earlier revision of this method constructed an EMPTY
	// PropertyMap and then called `.put(...)` on it AFTERWARD — but by then `.put()` is calling through to
	// that already-immutable internal copy, which unconditionally throws UnsupportedOperationException (with
	// no message, exactly matching the real exception this project's own debug logging captured for
	// PUMPKIN_PERSONALITY). This wasn't specific to that one item at all — it meant EVERY call to this method
	// for EVERY skin-based icon has always thrown, silently falling back to the letter/Steve-head placeholder
	// the whole time; the two earlier fix attempts (adding the signature, deriving a per-texture UUID) never
	// had a chance to matter since the exception happened before either could be relevant. The actual fix:
	// populate a real, separate mutable Multimap FIRST, then hand it to PropertyMap's constructor already
	// complete — the one-time immutable snapshot happens correctly with the real data already in it, with no
	// mutation of the PropertyMap itself needed afterward at all.
	public static ItemStack buildHeadWithTexture(String base64Texture) {
		return buildHeadWithTexture(base64Texture, null);
	}

	// signature param — Hypixel's own "skin" object always includes a real Mojang signature alongside the
	// texture value, which this previously discarded entirely (see SkyblockItemRepo.ItemInfo.skinTexture's
	// own doc comment). Passing it through makes the constructed Property shaped exactly like a genuine
	// signed skin property instead of an unsigned one.
	/** For bundled/hardcoded raw Mojang texture hashes (e.g. "9bbe721d...", the hash half of a real
	 *  textures.minecraft.net URL) rather than an already-base64-encoded textures blob — wraps the hash
	 *  into the real JSON shape Mojang's skin property actually contains ({@code {"textures":{"SKIN":
	 *  {"url":"http://textures.minecraft.net/texture/<hash>"}}}}) and base64-encodes THAT, matching Odin's
	 *  own {@code createSkullStack}. A raw hash handed straight to {@link #buildHeadWithTexture(String)}
	 *  isn't valid base64 JSON at all and silently renders as the default Steve/letter head — this was a
	 *  real bug in InvincibilityTimerFeature's item icons before this method existed. */
	public static ItemStack fromTextureHash(String textureHash) {
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + textureHash + "\"}}}";
		String base64 = java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return buildHeadWithTexture(base64);
	}

	public static ItemStack buildHeadWithTexture(String base64Texture, String signature) {
		java.util.UUID iconProfileId = java.util.UUID.nameUUIDFromBytes(base64Texture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		com.google.common.collect.Multimap<String, Property> raw = com.google.common.collect.HashMultimap.create();
		raw.put("textures", signature != null ? new Property("textures", base64Texture, signature) : new Property("textures", base64Texture));
		PropertyMap properties = new PropertyMap(raw);
		GameProfile profile = new GameProfile(iconProfileId, "SbarIcon", properties);
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		return stack;
	}
}
