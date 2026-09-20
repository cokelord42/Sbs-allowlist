package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.dungeon.TerminalTracker;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Plays a distinct sound on dungeon-terminal clicks and completion, and on the F7 gate/core-opening chat
 * lines. Ported from Odin's {@code TerminalSounds.kt}. Click sound only plays on a <em>correct</em> click,
 * matching Odin — the correctness check reads {@link TerminalTracker#isCorrectSlot}, the same shared solve
 * state {@link TerminalSolverFeature}'s own highlight/block logic reads, so this works correctly whether
 * or not Terminal Solver itself is enabled. Odin lets you type any vanilla sound's raw registry name;
 * per user request this instead offers a curated, always-valid picker ({@link TerminalSound}) cycled the
 * same way GUI Scale/etc. already are elsewhere in this project, rather than a free-text field that could
 * silently no-op on a typo'd sound name. */
public class TerminalSoundsFeature extends Feature {
	private static final Pattern GATE_PATTERN = Pattern.compile("^The gate has been destroyed!$");
	private static final Pattern CORE_PATTERN = Pattern.compile("^The Core entrance is opening!$");

	/** A curated subset of vanilla sounds distinct/short enough to work as a one-shot UI cue — not every
	 *  sound in the game, which would be an unusably long list to cycle through for what's meant to be a
	 *  quick "pick something that stands out" choice. */
	public enum TerminalSound {
		BLAZE_HURT("Blaze Hurt", () -> SoundEvents.BLAZE_HURT),
		NOTE_PLING("Note Pling", () -> SoundEvents.NOTE_BLOCK_PLING.value()),
		NOTE_BASS("Note Bass", () -> SoundEvents.NOTE_BLOCK_BASS.value()),
		EXPERIENCE_ORB("Experience Orb", () -> SoundEvents.EXPERIENCE_ORB_PICKUP),
		ITEM_PICKUP("Item Pickup", () -> SoundEvents.ITEM_PICKUP),
		LEVEL_UP("Level Up", () -> SoundEvents.PLAYER_LEVELUP),
		UI_CLICK("UI Click", () -> SoundEvents.UI_BUTTON_CLICK.value()),
		BELL("Bell", () -> SoundEvents.BELL_BLOCK),
		ANVIL_LAND("Anvil Land", () -> SoundEvents.ANVIL_LAND),
		CHEST_OPEN("Chest Open", () -> SoundEvents.CHEST_OPEN),
		ARROW_HIT("Arrow Hit Player", () -> SoundEvents.ARROW_HIT_PLAYER),
		ENDERMAN_TELEPORT("Enderman Teleport", () -> SoundEvents.ENDERMAN_TELEPORT),
		BEACON_ACTIVATE("Beacon Activate", () -> SoundEvents.BEACON_ACTIVATE),
		TOTEM_USE("Totem Use", () -> SoundEvents.TOTEM_USE),
		FIREWORK_LAUNCH("Firework Launch", () -> SoundEvents.FIREWORK_ROCKET_LAUNCH),
		VILLAGER_YES("Villager Yes", () -> SoundEvents.VILLAGER_YES);

		public final String label;
		private final Supplier<SoundEvent> sound;
		TerminalSound(String label, Supplier<SoundEvent> sound) { this.label = label; this.sound = sound; }
		public SoundEvent get() { return sound.get(); }
	}

	// Shared builtin-sound catalog derived from the TerminalSound enum above (in declared order) — per user
	// request ("Add sound importing to all sound modules... volume and pitch sliders... a toggle for
	// removing deadspace"), both of this feature's sound pickers now go through the shared CustomSoundOption
	// object instead of hand-rolling their own soundIndex/playSound(1f, 1f) calls. Also reused as-is by
	// DungeonsCopilotFeature's own title-update-sound picker, which used to reference this enum directly.
	public static final String[] SOUND_IDS;
	public static final String[] SOUND_LABELS;
	static {
		TerminalSound[] values = TerminalSound.values();
		SOUND_IDS = new String[values.length];
		SOUND_LABELS = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			SOUND_IDS[i] = values[i].get().location().toString();
			SOUND_LABELS[i] = values[i].label;
		}
	}

	private boolean clickSounds = true;
	private boolean completeSounds = false;
	private final CustomSoundOption clickSound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);
	private final CustomSoundOption completeSound = new CustomSoundOption(SOUND_IDS, SOUND_LABELS);
	private long lastPlayedMs = 0;

	private static boolean listenersRegistered = false;
	private static TerminalSoundsFeature instance;

	public TerminalSoundsFeature() {
		super("terminal_sounds", "Terminal Sounds", FeatureCategory.COMBAT, false);
		completeSound.setBuiltinIndex(TerminalSound.EXPERIENCE_ORB.ordinal());
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;

			ContainerClickRegistry.setRule("terminal_sounds", (slot, slotId, mouseButton, type) -> {
				if (instance != null && instance.isEnabled() && instance.clickSounds
					&& TerminalTracker.getCurrentType() != null && TerminalTracker.isCorrectSlot(slotId)) {
					instance.playClickSound();
				}
				return false;
			});

			TerminalTracker.addSolveListener(() -> {
				if (instance != null && instance.isEnabled() && instance.completeSounds) instance.playCompleteSound();
			});

			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
		}
	}

	private void onChatMessage(String text) {
		if (!DungeonState.isInDungeon()) return;
		if (GATE_PATTERN.matcher(text).matches() || CORE_PATTERN.matcher(text).matches()) {
			playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
		}
	}

	private void playClickSound() {
		long now = System.currentTimeMillis();
		if (now - lastPlayedMs <= 2) return;
		lastPlayedMs = now;
		clickSound.play();
	}

	private void playCompleteSound() {
		completeSound.play();
	}

	private static void playSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.playSound(sound, volume, pitch);
	}

	public boolean isClickSounds() { return clickSounds; }
	public void setClickSounds(boolean value) { clickSounds = value; }
	public boolean isCompleteSounds() { return completeSounds; }
	public void setCompleteSounds(boolean value) { completeSounds = value; }
	public CustomSoundOption getClickSound() { return clickSound; }
	public CustomSoundOption getCompleteSound() { return completeSound; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("clickSounds", clickSounds);
		obj.addProperty("completeSounds", completeSounds);
		obj.add("clickSound", clickSound.toJson());
		obj.add("completeSound", completeSound.toJson());
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("clickSounds")) clickSounds = obj.get("clickSounds").getAsBoolean();
		if (obj.has("completeSounds")) completeSounds = obj.get("completeSounds").getAsBoolean();
		loadSound(obj, "clickSound", clickSound);
		loadSound(obj, "completeSound", completeSound);
	}

	// Back-compat with the old flat TerminalSound-enum-name string fields, pre-CustomSoundOption.
	private static void loadSound(JsonObject obj, String key, CustomSoundOption sound) {
		if (!obj.has(key)) return;
		JsonElement value = obj.get(key);
		if (value.isJsonObject()) {
			sound.fromJson(value);
		} else if (value.isJsonPrimitive()) {
			try { sound.setBuiltinIndex(TerminalSound.valueOf(value.getAsString()).ordinal()); } catch (IllegalArgumentException ignored) {}
		}
	}

	@Override
	public String getDescription() {
		return "Plays a distinct sound on dungeon terminal clicks, completion, and the F7 gate/core opening.";
	}
}
