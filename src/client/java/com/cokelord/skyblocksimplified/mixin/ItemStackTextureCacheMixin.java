package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.SkyblockTextureCache;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemStack.class)
public class ItemStackTextureCacheMixin implements SkyblockTextureCache {
	@Unique private byte sbs$kind;
	@Unique private Identifier sbs$model;
	@Unique private boolean sbs$profileResolved;
	@Unique private ResolvableProfile sbs$profile;

	@Override public byte sbs$kind() { return sbs$kind; }
	@Override public void sbs$kind(byte kind) { sbs$kind = kind; }
	@Override public Identifier sbs$model() { return sbs$model; }
	@Override public void sbs$model(Identifier model) { sbs$model = model; }
	@Override public boolean sbs$profileResolved() { return sbs$profileResolved; }
	@Override public ResolvableProfile sbs$profile() { return sbs$profile; }
	@Override public void sbs$profile(ResolvableProfile profile) { sbs$profile = profile; sbs$profileResolved = true; }
}
