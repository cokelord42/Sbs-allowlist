package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.SkyblockTextureMapApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.util.SkyblockTextureCache;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Map;
import java.util.Set;

/**
 * Per user request ("Skyblock recently added their own texture pack which i dont like... Add only the
 * removing texture pack part and nothing else like whitelist and such"), ported from Detexturify
 * (github.com/skies-starred/detexturify), minus its whitelist/blacklist/replacements/commands, its pack
 * cache and its color-shader override (the shader include only exists inside the pack, which is never
 * loaded here).
 *
 * <p>Three pieces, all required — blocking the pack alone would leave every custom item and tooltip
 * pointing at {@code hypixel_skyblock:*} assets that no longer exist (missing-texture cubes):
 * <ol>
 *   <li>ResourcePackPushMixin: Hypixel's SkyBlock pack push is cancelled and answered ACCEPTED +
 *       SUCCESSFULLY_LOADED so the server is satisfied without anything being downloaded (Detexturify's
 *       own URL check: contains "hypixel.net" and "SkyBlock").</li>
 *   <li>SkyblockItemModelMixin / SkyblockHeadProfileMixin: a {@code hypixel_skyblock:*} item model is
 *       swapped for the item's pre-pack vanilla model (and head skin) from {@link SkyblockTextureMapApi},
 *       falling back to the item's own default model. Special cases (quiver arrow, attuned/katana swords)
 *       are Detexturify's.</li>
 *   <li>SkyblockTooltipStyleMixin: a {@code hypixel_skyblock:*} tooltip style falls back to vanilla's.</li>
 * </ol>
 * Takes effect on the next pack push, i.e. the next server join or lobby switch.
 */
public class RemoveSkyblockTexturePackFeature extends Feature {
	public static final String HYPIXEL_NAMESPACE = "hypixel_skyblock";
	private static final Identifier ARROW = Identifier.withDefaultNamespace("arrow");
	private static final Identifier[] ATTUNE_SWORDS = {
		Identifier.withDefaultNamespace("stone_sword"), Identifier.withDefaultNamespace("golden_sword"),
		Identifier.withDefaultNamespace("iron_sword"), Identifier.withDefaultNamespace("diamond_sword")
	};
	private static final Set<String> KATANAS = Set.of("VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA");
	private static final byte KIND_STATIC = 1, KIND_KATANA = 2, KIND_ATTUNE = 3;

	private static RemoveSkyblockTexturePackFeature instance;
	// True once this connection's pack was actually blocked: the remapping must keep running even if the
	// module is switched off mid-session, since the pack still isn't loaded until the next join.
	private static volatile boolean packBlocked = false;

	public RemoveSkyblockTexturePackFeature() {
		super("remove_skyblock_texture_pack", "Remove Skyblock Texture Pack", FeatureCategory.INVENTORY, false);
		instance = this;
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> packBlocked = false);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		SkyblockTextureMapApi.ensureLoaded();
	}

	public static boolean shouldBlockPack(String url) {
		if (instance == null || !instance.isEnabled() || url == null) return false;
		return url.contains("hypixel.net") && url.contains("SkyBlock");
	}

	public static void onPackBlocked(String url) {
		packBlocked = true;
		SkyblockTextureMapApi.ensureLoaded();
		// Blocking the pack also removes Hypixel's icon font (every stat/gem/mob-type glyph) — keep just that.
		com.cokelord.skyblocksimplified.util.HypixelFontPack.ensure(url);
	}

	/** Whether the cached icon-font pack should be offered during resource reloads. */
	public static boolean isFontPackWanted() {
		return instance != null && instance.isEnabled();
	}

	public static boolean isRemapActive() {
		return packBlocked || (instance != null && instance.isEnabled());
	}

	/** See the class doc. {@code original} is the stack's real item_model component. */
	public static Identifier resolveModel(ItemStack stack, Identifier original) {
		if (original == null || !isRemapActive() || stack.isEmpty() || !HYPIXEL_NAMESPACE.equals(original.getNamespace())) return original;
		SkyblockTextureCache cache = (SkyblockTextureCache) (Object) stack;
		switch (cache.sbs$kind()) {
			case KIND_STATIC: return cache.sbs$model();
			case KIND_KATANA: return katanaModel(stack);
			case KIND_ATTUNE: {
				int mode = customData(stack).getIntOr("td_attune_mode", -1);
				if (mode >= 0 && mode < ATTUNE_SWORDS.length) return ATTUNE_SWORDS[mode];
				break;
			}
			default: break;
		}

		CompoundTag data = customData(stack);
		int attune = data.getIntOr("td_attune_mode", -1);
		if (attune == -1 && data.contains("quiver_arrow")) return cacheStatic(cache, ARROW);
		String id = data.getStringOr("id", null);
		if (id != null) {
			String key = id.replace(':', '-');
			if (KATANAS.contains(key)) {
				cache.sbs$kind(KIND_KATANA);
				return katanaModel(stack);
			}
			if (attune >= 0 && attune < ATTUNE_SWORDS.length) {
				cache.sbs$kind(KIND_ATTUNE);
				return ATTUNE_SWORDS[attune];
			}
		}
		Map<String, SkyblockTextureMapApi.Entry> map = SkyblockTextureMapApi.get();
		SkyblockTextureMapApi.Entry entry = map != null && id != null ? map.get(id.replace(':', '-')) : null;
		Identifier fallback = entry != null ? entry.model : stack.getItem().components().get(DataComponents.ITEM_MODEL);
		// Only memoize a fallback once the map has loaded — otherwise a stack first seen before the async
		// fetch finished would keep the generic model forever.
		return map != null ? cacheStatic(cache, fallback) : fallback;
	}

	/** Head skin for a remapped player head that Hypixel now sends without one (the pack used to supply it). */
	public static ResolvableProfile resolveProfile(ItemStack stack, ResolvableProfile original) {
		if (original != null || !isRemapActive() || stack.isEmpty()) return original;
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		if (model == null || !HYPIXEL_NAMESPACE.equals(model.getNamespace())) return original;
		SkyblockTextureCache cache = (SkyblockTextureCache) (Object) stack;
		if (cache.sbs$profileResolved()) return cache.sbs$profile();
		Map<String, SkyblockTextureMapApi.Entry> map = SkyblockTextureMapApi.get();
		if (map == null) return null;
		String id = customData(stack).getStringOr("id", null);
		SkyblockTextureMapApi.Entry entry = id != null ? map.get(id.replace(':', '-')) : null;
		ResolvableProfile profile = entry != null ? entry.profile() : null;
		cache.sbs$profile(profile);
		return profile;
	}

	public static Identifier resolveTooltipStyle(Identifier style) {
		if (style == null || !isRemapActive() || !HYPIXEL_NAMESPACE.equals(style.getNamespace())) return style;
		return null;
	}

	private static Identifier cacheStatic(SkyblockTextureCache cache, Identifier model) {
		cache.sbs$model(model);
		cache.sbs$kind(KIND_STATIC);
		return model;
	}

	private static Identifier katanaModel(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		return player != null && player.getCooldowns().isOnCooldown(stack) ? ATTUNE_SWORDS[1] : ATTUNE_SWORDS[3];
	}

	private static CompoundTag customData(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
	}

	@Override
	public String getDescription() {
		return "Blocks Hypixel's forced SkyBlock texture pack and shows items and tooltips the way they looked before it. Applies on your next server join or lobby switch.";
	}
}
