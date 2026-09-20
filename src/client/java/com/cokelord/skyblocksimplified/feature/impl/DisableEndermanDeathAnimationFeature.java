package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.EntityHideRegistry;
import com.cokelord.skyblocksimplified.sound.SoundMuteRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;

/**
 * Hides an Enderman entirely the moment it starts dying (EntityHideRegistry, checked against the real
 * {@code deathTime > 0} state every render — per user report, only cancelling the fall-over rotation
 * (EndermanDeathFlopMixin, still applied too as a harmless belt-and-braces fallback) left it standing
 * there fully visible for the rest of the real ~20-tick vanilla death sequence, not actually "disappeared"
 * as asked for), plus two independent sub-toggles for its sounds — confirmed real vanilla sound event ids
 * (Minecraft Wiki's Enderman sounds table): "entity.enderman.death" (dying sound), and
 * "entity.enderman.stare" (the loud scream when an Enderman is angered by eye contact — notably higher
 * volume than its other sounds) plus "entity.enderman.teleport" (the teleport whoosh) grouped under one
 * "teleport sound" sub-toggle per user report describing them together as "the sound an enderman makes
 * when it sees a player... when it teleports".
 */
public class DisableEndermanDeathAnimationFeature extends Feature {
	private boolean disableDyingSound = false;
	private boolean disableTeleportSound = false;
	private boolean registered = false;

	public DisableEndermanDeathAnimationFeature() {
		super("disable_enderman_death_animation", "Disable Enderman Dying Animation", FeatureCategory.COMBAT, false);
	}

	@Override
	public String getSubcategory() {
		return "Slayers";
	}

	@Override
	public String getSlayerType() {
		return "Voidgloom";
	}

	public boolean isDisableDyingSound() { return disableDyingSound; }
	public void setDisableDyingSound(boolean v) { disableDyingSound = v; }
	public boolean isDisableTeleportSound() { return disableTeleportSound; }
	public void setDisableTeleportSound(boolean v) { disableTeleportSound = v; }

	@Override
	protected void onEnable() {
		if (!registered) {
			SoundMuteRegistry.setRule(getId(), this::shouldMute);
			EntityHideRegistry.setRule(getId(), this::isDyingEnderman);
			registered = true;
		}
	}

	@Override
	protected void onDisable() {
		if (registered) {
			SoundMuteRegistry.clearRule(getId());
			EntityHideRegistry.clearRule(getId());
			registered = false;
		}
	}

	private boolean shouldMute(SoundInstance instance) {
		String path = instance.getIdentifier().getPath();
		if (disableDyingSound && path.equals("entity.enderman.death")) return true;
		return disableTeleportSound && (path.equals("entity.enderman.stare") || path.equals("entity.enderman.teleport"));
	}

	private boolean isDyingEnderman(Entity entity) {
		return entity instanceof EnderMan enderMan && enderMan.deathTime > 0;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("disableDyingSound", disableDyingSound);
		obj.addProperty("disableTeleportSound", disableTeleportSound);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("disableDyingSound")) disableDyingSound = obj.get("disableDyingSound").getAsBoolean();
		if (obj.has("disableTeleportSound")) disableTeleportSound = obj.get("disableTeleportSound").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Hides an Enderman the instant it starts its death animation, instead of watching it fall over.";
	}
}
