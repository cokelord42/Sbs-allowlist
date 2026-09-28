package com.cokelord.skyblocksimplified.util;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

/** Per-ItemStack memo for Remove Skyblock Texture Pack (implemented on ItemStack by
 *  ItemStackTextureCacheMixin), so the custom-data NBT copy + map lookup happens once per stack instead of
 *  every frame it's rendered. */
public interface SkyblockTextureCache {
	/** 0 = not resolved yet, 1 = static model cached, 2 = katana (cooldown-dependent), 3 = attuned sword. */
	byte sbs$kind();
	void sbs$kind(byte kind);
	Identifier sbs$model();
	void sbs$model(Identifier model);
	boolean sbs$profileResolved();
	ResolvableProfile sbs$profile();
	void sbs$profile(ResolvableProfile profile);
}
