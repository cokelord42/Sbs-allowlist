package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.hud.TabListReader;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.cokelord.skyblocksimplified.sound.SoundPlayListenerRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alerts when the Moonglade (Galatea) beacon buff is off cooldown — ported from SkyHanni's
 * MoongladeBeaconWarning.kt, keeping its confirmed tab-list line and its 9-minute repeat-alert cooldown
 * so it doesn't spam chat every tick while the beacon sits ready and unclaimed.
 *
 * <p>The beacon-tuning solver (Moonglade Beacon Solver) is folded in here as a toggle per user request.
 * Ported from SkyHanni's MoongladeBeacon.kt as closely as reasonable given the complexity of the real
 * minigame: it requires matching THREE independent values — color, speed, and pitch — not just color.
 * Color is read directly (a stained glass pane item in the reference row); speed and pitch aren't shown
 * anywhere readable, so SkyHanni infers them by observation: speed from how many ticks pass between the
 * reference indicator moving to a new slot, pitch from the pitch of the repeating "note_block.bass" sound
 * cue (confirmed real via SkyHanni's onPlaySound hook) mapped to the nearest of 3 known pitch values.
 * Deliberately scoped down from SkyHanni's full version: no separate "enchanted tuning"/upgrade-strength
 * dual solve, no over-click prevention, no Stereo Pants conflict warning — just the core "which of my 3
 * select slots currently matches the reference" highlight, which is the actual ask.
 */
public class MoongladeBeaconAlertFeature extends TabWidgetOverlayFeature {
	private static final String READY_LINE = "Cooldown: AVAILABLE";
	private static final long REPEAT_COOLDOWN_MILLIS = 9 * 60_000L;

	private static final int MATCH_SLOT_START = 10;
	private static final int MATCH_SLOT_END = 16;
	private static final int COLOR_SELECT_SLOT = 46;
	private static final int SPEED_SELECT_SLOT = 48;
	private static final int PITCH_SELECT_SLOT = 50;
	private static final int SLOT_SIZE = 16;
	private static final int DEFAULT_SOLVER_COLOR = 0x8000FF00;

	// Plain text (no §-code requirement) — the "Current color: X" wording/format is confirmed from
	// SkyHanni's real pattern; "Current Speed:"/"Current Pitch:" are best-effort (SkyHanni reads those
	// through its own private repo-pattern text this project doesn't have confirmed) but follow the exact
	// same lore-line convention as the confirmed color one.
	private static final Pattern CURRENT_COLOR = Pattern.compile("Current [Cc]olor: (?<color>.+)");
	private static final Pattern CURRENT_SPEED = Pattern.compile("Current [Ss]peed: (?<speed>\\d+)");
	private static final Pattern CURRENT_PITCH = Pattern.compile("Current [Pp]itch: (?<pitch>Low|Normal|High)");
	private static final String PITCH_SOUND_ID = "block.note_block.bass";
	private static final float PITCH_MATCH_TOLERANCE = 0.05f;

	private enum BeaconColor {
		WHITE("White"), ORANGE("Orange"), MAGENTA("Magenta"), LIGHT_BLUE("Light Blue"),
		YELLOW("Yellow"), LIME("Lime"), PINK("Pink"), CYAN("Cyan"), PURPLE("Purple"),
		BLUE("Blue"), BROWN("Brown"), GREEN("Green"), RED("Red");

		final String displayName;
		Item item;

		BeaconColor(String displayName) {
			this.displayName = displayName;
		}
	}

	// Confirmed real from SkyHanni's BeaconSpeed enum: ticks between reference moves at each speed level.
	private enum BeaconSpeed {
		SPEED_1(52), SPEED_2(42), SPEED_3(32), SPEED_4(22), SPEED_5(12);

		final int tickSpeed;
		final int guiSpeed;

		BeaconSpeed(int tickSpeed) {
			this.tickSpeed = tickSpeed;
			this.guiSpeed = ordinal() + 1;
		}

		static BeaconSpeed byClosestTickSpeed(double measured) {
			BeaconSpeed best = null;
			double bestDiff = Double.MAX_VALUE;
			for (BeaconSpeed speed : values()) {
				double diff = Math.abs(speed.tickSpeed - measured);
				if (diff < bestDiff) {
					bestDiff = diff;
					best = speed;
				}
			}
			return best;
		}
	}

	// Confirmed real from SkyHanni's BeaconPitch enum.
	private enum BeaconPitch {
		LOW(0.0952381f), NORMAL(0.7936508f), HIGH(1.4920635f);

		final float pitch;

		BeaconPitch(float pitch) {
			this.pitch = pitch;
		}

		static BeaconPitch byPitch(float pitch) {
			for (BeaconPitch candidate : values()) {
				if (Math.abs(candidate.pitch - pitch) < PITCH_MATCH_TOLERANCE) return candidate;
			}
			return null;
		}
	}

	private static final Map<Item, BeaconColor> ITEM_TO_COLOR = new HashMap<>();

	static {
		for (BeaconColor color : BeaconColor.values()) {
			Identifier id = Identifier.fromNamespaceAndPath("minecraft", color.name().toLowerCase(Locale.ROOT) + "_stained_glass_pane");
			Item item = BuiltInRegistries.ITEM.getValue(id);
			color.item = item;
			ITEM_TO_COLOR.put(item, color);
		}
	}

	private boolean ready = false;
	private long lastAlertMillis = Long.MIN_VALUE;

	private boolean solverEnabled = false;
	private int solverColor = DEFAULT_SOLVER_COLOR;

	// Reference-side state (inferred by observation, not directly readable).
	private int currentRefSlot = -1;
	private int ticksSinceRefMove = 0;
	private BeaconSpeed referenceSpeed = null;
	private BeaconPitch referencePitch = null;
	private boolean inTuningScreen = false;

	public MoongladeBeaconAlertFeature() {
		super("moonglade_beacon_ready_alert", "Moonglade Beacon Ready Alert", FeatureCategory.FORAGING, "Foraging", 0.01f, 0.36f);

		SoundPlayListenerRegistry.setListener("moonglade_beacon_solver", instance -> {
			if (!isEnabled() || !solverEnabled || !inTuningScreen) return;
			if (!PITCH_SOUND_ID.equals(instance.getIdentifier().getPath())) return;
			BeaconPitch pitch = BeaconPitch.byPitch(instance.getPitch());
			if (pitch != null) referencePitch = pitch;
		});

		// Wrapped in try-catch — see BlessingParticleFilter's doc comment on its own registration for the
		// full reasoning: this registers during feature construction, before FeatureRegistry::tickAll, and
		// an uncaught throw here would silently skip tickAll (and every feature it ticks) for that frame.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				if (!inTuningScreen) return;
				ticksSinceRefMove++;
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("MoongladeBeaconAlertFeature tick threw, skipping this tick", e);
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;
			String title = containerScreen.getTitle().getString();
			boolean isTuningScreen = title.equals("Tune Frequency") || title.equals("Upgrade Signal Strength");
			if (!isTuningScreen) return;

			inTuningScreen = true;
			currentRefSlot = -1;
			ticksSinceRefMove = 0;
			referenceSpeed = null;
			referencePitch = null;

			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (!isEnabled() || !solverEnabled) return;
				trackReferenceSpeed(containerScreen.getMenu());
				renderSolverHighlights(graphics, containerScreen);
			});
			ScreenEvents.remove(screen).register(s -> inTuningScreen = false);
		});
	}

	public boolean isSolverEnabled() {
		return solverEnabled;
	}

	public void setSolverEnabled(boolean solverEnabled) {
		this.solverEnabled = solverEnabled;
	}

	public int getSolverColor() {
		return solverColor;
	}

	public void setSolverColor(int solverColor) {
		this.solverColor = solverColor;
	}

	// Per user correction: the Moonglade Beacon is on Moonglade Marsh specifically (real API mode
	// "foraging_2", confirmed via SkyHanni's IslandType.kt) — a distinct Foraging island from "The Park"
	// (foraging_1), not the same place.
	@Override
	public boolean isRelevantToCurrentIsland() {
		return com.cokelord.skyblocksimplified.util.IslandGate.isInGalatea();
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled()) return;
		boolean found = false;
		for (String line : TabListReader.readLines()) {
			if (line.contains(READY_LINE)) {
				found = true;
				break;
			}
		}
		ready = found;
		if (found && System.currentTimeMillis() - lastAlertMillis >= REPEAT_COOLDOWN_MILLIS) {
			lastAlertMillis = System.currentTimeMillis();
			var player = client.player;
			if (player != null) {
				client.gui.hud.getChat().addClientSystemMessage(Component.literal("§aMoonglade Beacon is ready!"));
			}
		}
	}

	@Override
	protected List<String> currentLines() {
		return ready ? List.of("§aMoonglade Beacon: Ready!") : List.of();
	}

	/** Finds which of the reference row's slots currently holds the moving indicator (any non-empty,
	 *  non-glass-pane item — the pane itself sits still; only the "current position" marker moves) and
	 *  times how many ticks pass between it changing slots, the same signal SkyHanni's real solver uses
	 *  to infer a speed value that's never shown anywhere readable. */
	private void trackReferenceSpeed(AbstractContainerMenu menu) {
		int markerSlot = -1;
		for (int i = MATCH_SLOT_START; i <= MATCH_SLOT_END; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && !ITEM_TO_COLOR.containsKey(stack.getItem())) {
				markerSlot = i;
				break;
			}
		}
		if (markerSlot < 0) return;
		if (currentRefSlot < 0) {
			currentRefSlot = markerSlot;
			ticksSinceRefMove = 0;
			return;
		}
		if (markerSlot == currentRefSlot) return;

		if (ticksSinceRefMove > 0) {
			BeaconSpeed measured = BeaconSpeed.byClosestTickSpeed(ticksSinceRefMove);
			if (measured != null) referenceSpeed = measured;
		}
		currentRefSlot = markerSlot;
		ticksSinceRefMove = 0;
	}

	private void renderSolverHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = screen.getMenu();

		BeaconColor referenceColor = null;
		for (int i = MATCH_SLOT_START; i <= MATCH_SLOT_END; i++) {
			BeaconColor found = ITEM_TO_COLOR.get(menu.getSlot(i).getItem().getItem());
			if (found != null) {
				referenceColor = found;
				break;
			}
		}

		BeaconColor ourColor = readLoreValue(menu, COLOR_SELECT_SLOT, CURRENT_COLOR, "color", MoongladeBeaconAlertFeature::colorFromName);
		BeaconSpeed ourSpeed = readLoreValue(menu, SPEED_SELECT_SLOT, CURRENT_SPEED, "speed", MoongladeBeaconAlertFeature::speedFromGuiNumber);
		BeaconPitch ourPitch = readLoreValue(menu, PITCH_SELECT_SLOT, CURRENT_PITCH, "pitch", MoongladeBeaconAlertFeature::pitchFromName);

		if (referenceColor != null && referenceColor == ourColor) highlightSlot(graphics, screen, COLOR_SELECT_SLOT);
		if (referenceSpeed != null && referenceSpeed == ourSpeed) highlightSlot(graphics, screen, SPEED_SELECT_SLOT);
		if (referencePitch != null && referencePitch == ourPitch) highlightSlot(graphics, screen, PITCH_SELECT_SLOT);
	}

	private <T> T readLoreValue(AbstractContainerMenu menu, int slotIndex, Pattern pattern, String group, java.util.function.Function<String, T> parse) {
		if (menu.slots.size() <= slotIndex) return null;
		ItemStack stack = menu.getSlot(slotIndex).getItem();
		if (stack.isEmpty()) return null;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return null;
		for (Component line : lore.lines()) {
			Matcher matcher = pattern.matcher(line.getString());
			if (matcher.matches()) return parse.apply(matcher.group(group));
		}
		return null;
	}

	private static BeaconColor colorFromName(String name) {
		for (BeaconColor candidate : BeaconColor.values()) {
			if (candidate.displayName.equalsIgnoreCase(name)) return candidate;
		}
		return null;
	}

	private static BeaconSpeed speedFromGuiNumber(String number) {
		try {
			int gui = Integer.parseInt(number);
			for (BeaconSpeed candidate : BeaconSpeed.values()) {
				if (candidate.guiSpeed == gui) return candidate;
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return null;
	}

	private static BeaconPitch pitchFromName(String name) {
		try {
			return BeaconPitch.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private void highlightSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int slotIndex) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		Slot slot = screen.getMenu().getSlot(slotIndex);
		int x = accessor.skyblocksimplified$getLeftPos() + slot.x;
		int y = accessor.skyblocksimplified$getTopPos() + slot.y;
		RenderUtil.fillRounded(graphics, x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, 2, solverColor);
	}

	// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when i
	// relaunch"): these used to build a brand new JsonObject, discarding the position/scale the
	// TabWidgetOverlayFeature base class now persists (see that class's own doc comment) — now calls
	// through super and merges its own keys onto the same object.
	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = (JsonObject) super.savePersistedData();
		obj.addProperty("solverEnabled", solverEnabled);
		obj.addProperty("solverColor", solverColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		super.loadPersistedData(data);
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("solverEnabled")) solverEnabled = obj.get("solverEnabled").getAsBoolean();
		if (obj.has("solverColor")) solverColor = obj.get("solverColor").getAsInt();
	}

	@Override
	public String getDescription() {
		return "Alerts you when the Moonglade Beacon color-select puzzle is ready, and highlights the matching slots.";
	}
}
