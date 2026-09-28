package com.cokelord.skyblocksimplified.pv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/** Client-only mannequin wearing the viewed player's skin and armor, for the Player Viewer's model preview
 *  (the same vanilla entity skyblock-pv uses). Setting the synced profile makes ClientMannequin look the
 *  skin up through vanilla's own skin cache. Never added to the world. */
public class ViewerMannequin extends ClientMannequin {
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(-1_000_000);

	public ViewerMannequin(UUID uuid, List<ItemStack> armorBootsFirst) {
		super(Minecraft.getInstance().level, Minecraft.getInstance().playerSkinRenderCache());
		// Entities only get an ID when added to a level, and this one never is — but rendering its worn items
		// seeds item models with getId(), which throws while the ID is still unassigned (a real crash). Negative
		// IDs never collide with server-assigned ones.
		this.setId(NEXT_ID.getAndDecrement());
		ResolvableProfile profile = ResolvableProfile.createUnresolved(uuid);
		this.getEntityData().set(DATA_PROFILE, profile);
		// A UUID-only profile has no skin textures; fetch the full profile (Mojang session server, blocking, so
		// off-thread) and look the skin up from that — the same two steps skyblock-pv's FakePlayer uses.
		Minecraft mc = Minecraft.getInstance();
		this.skinLookup = java.util.concurrent.CompletableFuture
			.supplyAsync(() -> mc.services().profileResolver().fetchById(uuid))
			.thenCompose(full -> full.isPresent()
				? mc.playerSkinRenderCache().lookup(ResolvableProfile.createResolved(full.get()))
				: java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty()));
		EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
		for (int i = 0; i < slots.length && i < armorBootsFirst.size(); i++) this.setItemSlot(slots[i], armorBootsFirst.get(i));
	}

	// ClientMannequin only applies its looked-up skin inside tick(), and this mannequin never ticks (it's not
	// in the world) — so it stayed on the default Steve/Alex skin. Keep our own lookup and read it directly.
	private java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.client.renderer.PlayerSkinRenderCache.RenderInfo>> skinLookup;

	@Override
	public net.minecraft.world.entity.player.PlayerSkin getSkin() {
		if (skinLookup == null || skinLookup.isCompletedExceptionally()) return super.getSkin();
		return skinLookup.getNow(java.util.Optional.empty()).map(net.minecraft.client.renderer.PlayerSkinRenderCache.RenderInfo::playerSkin).orElseGet(super::getSkin);
	}

	@Override
	public boolean shouldShowName() { return false; }

	@Override
	public Component belowNameDisplay() { return null; }
}
