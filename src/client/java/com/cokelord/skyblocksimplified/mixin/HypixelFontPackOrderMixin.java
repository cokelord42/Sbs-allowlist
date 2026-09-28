package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.util.HypixelFontPack;
import com.google.common.collect.ImmutableList;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps the icon-font pack directly above vanilla (below every user pack), mirroring Detexturify's
 *  PackRepositoryMixin, so its private-use glyphs win over vanilla's fallback font without overriding
 *  anything a user resource pack changes. */
@Mixin(PackRepository.class)
public class HypixelFontPackOrderMixin {
	@Inject(method = "rebuildSelected", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$orderFontPack(Collection<String> selectedNames, CallbackInfoReturnable<List<Pack>> cir) {
		List<Pack> packs = cir.getReturnValue();
		if (packs == null || packs.isEmpty()) return;
		int ours = -1, vanilla = -1;
		for (int i = 0; i < packs.size(); i++) {
			String id = packs.get(i).getId();
			if (id.equals(HypixelFontPack.PACK_ID)) ours = i;
			else if (id.equals("vanilla")) vanilla = i;
		}
		if (ours < 0 || vanilla < 0 || ours == vanilla + 1) return;
		List<Pack> reordered = new ArrayList<>(packs);
		Pack pack = reordered.remove(ours);
		reordered.add(ours < vanilla ? vanilla : vanilla + 1, pack);
		cir.setReturnValue(ImmutableList.copyOf(reordered));
	}
}
