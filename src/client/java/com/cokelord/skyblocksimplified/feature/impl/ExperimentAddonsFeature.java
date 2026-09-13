package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.api.ExperimentationTableApi;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.gui.RenderUtil;
import com.cokelord.skyblocksimplified.inventory.ContainerClickRegistry;
import com.cokelord.skyblocksimplified.mixin.AbstractContainerScreenAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Next-click helper for the Experimentation Table's Chronomatron/Ultrasequencer add-on minigames —
 * ported from SkyHanni's ExperimentsAddonsHelper.kt + SuperpairsClicksAlert.kt. Both minigames are a
 * memorize-then-replicate game: during the "read" phase Hypixel briefly lights up each tile of a hidden
 * sequence one at a time with no way to query it directly afterward, so this reconstructs that sequence
 * live (via the round/phase status items and the currently-lit tile's color/slot) and highlights the
 * next 1-2 correct clicks during the "replicate" phase. "Prevent Misclicks" and "Max Clicks Alert" are
 * separate SimpleToggleFeature entries read by id (same pattern as Croesus Chest Overlay's sub-toggles)
 * rather than bespoke persisted booleans.
 *
 * <p>Chronomatron colors are read from each tile's display name text (not a fixed enum), since Hypixel
 * only needs to be compared for equality here, not mapped to a specific game color system.
 *
 * <p>Next/second-click colors are exposed via getters/setters and persisted, ready for a MainScreen
 * color-picker to be wired up to them later — same scoping DungeonSecretChimeFeature's sound-id field
 * used ("exposes a persisted field ready for that UI to set").
 */
public class ExperimentAddonsFeature extends Feature {
	private static final int ROUND_STATUS_SLOT = 4;
	private static final int PHASE_STATUS_SLOT = 49;
	private static final int SLOT_SIZE = 16;
	// Halved from the original 0x80/0x80 alphas per user report ("too bright") — same "halve it" treatment
	// ActiveHotfPerksHighlightFeature's own highlight colors already got for the identical complaint.
	private static final int DEFAULT_NEXT_COLOR = 0x4000FF00;
	private static final int DEFAULT_SECOND_COLOR = 0x40FFFF00;

	// Plain text, no literal §-code requirements — Component#getString() (what stack.getHoverName()
	// returns) already strips all formatting, so these could never match anything against the real
	// item name and readPhase() always returned null. That silently skipped readChronomatron/
	// readUltrasequencer entirely on every frame, which is the actual "detects opening it but never
	// shows a next click" bug — not a sequence-tracking bug at all, the tracking never even ran.
	private static final Pattern ROUND_ITEM_PATTERN = Pattern.compile("Round: (?<round>\\d+)");
	private static final Pattern REPLICATE_PHASE_PATTERN = Pattern.compile("Timer: \\d+s");
	private static final Pattern READ_PHASE_PATTERN = Pattern.compile("Remember the pattern!");

	private enum Phase { READ, REPLICATE }

	private final List<String> hypixelChronomatronSequence = new ArrayList<>();
	// NOTE: an earlier revision also tracked the one specific physical slot each sequence step flashed from
	// (hypixelChronomatronSlots) and validated/highlighted clicks against that exact slot instead of by
	// color. That broke matching duplicate-color pairs — Chronomatron always uses each color on exactly two
	// physical tiles and either is a valid click, so requiring the one exact slot silently made the other,
	// visually-identical tile of the pair unmarkable and unclickable ("only the top block of a pair ever
	// highlights"). Removed; click-validation/highlighting below are color-based only (via
	// chronomatronSlotColors), which naturally covers every tile sharing a color, not just one.
	private final List<String> userChronomatronProgress = new ArrayList<>();
	// Chronomatron tiles only show their real color (terracotta) briefly while flashing — the rest of the
	// time (including while the player is expected to click them back in order) they sit as plain glass,
	// which chronoColorOf() correctly reads as "no color" since it isn't one. Highlighting/click-validation
	// used to call chronoColorOf() live against whatever the slot currently holds, so a tile that had
	// already reverted to glass could never be found as a highlight target or accepted as a valid click —
	// "highlights it as terracotta, then once it turns to glass it won't highlight or let me click it".
	// This remembers each slot's last-seen real color and keeps using that once the tile goes back to glass.
	private final Map<Integer, String> chronomatronSlotColors = new HashMap<>();
	private final List<Integer> hypixelUltrasequencerSlots = new ArrayList<>();
	private final List<Integer> userUltrasequencerProgress = new ArrayList<>();
	private final Map<Integer, ItemStack> ultrasequencerDyeMap = new HashMap<>();

	// Per user clarification ("the issue was that sometimes the server didn't register my click (because of
	// ping) and the mod still went to the next step even though it didnt actually click the current step"):
	// the PREVIOUS round's fix only addressed a different failure mode (this mod's OWN rules disagreeing
	// with each other about whether to veto a click) — committing progress the instant a click isn't
	// LOCALLY vetoed still has no actual server confirmation at all, so a click that's sent but genuinely
	// never processed server-side (dropped/delayed under real lag) commits regardless. There's no positive
	// per-click "the server accepted this" signal available to read here, so this can't be made perfect —
	// but it CAN be made to stop trusting its own guess forever: a click now sits PENDING for a short real
	// round-trip margin before actually counting, and gets silently discarded instead of committed if the
	// round/phase state changes underneath it in the meantime (Hypixel restarting the demonstration is the
	// one real, observable sign that whatever happened, this exact attempt didn't stick) — see
	// processPendingClicks. The next/second-click highlight still advances immediately on the optimistic
	// (committed + pending) count for a responsive feel; only the DURABLE progress used to gate future
	// clicks and detect round completion waits for confirmation.
	private record PendingChronoClick(String color, long armedAtMillis, int roundAtArm) {}
	private record PendingUltraClick(int slotIndex, long armedAtMillis, int roundAtArm) {}
	private final java.util.ArrayDeque<PendingChronoClick> pendingChronoClicks = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<PendingUltraClick> pendingUltraClicks = new java.util.ArrayDeque<>();
	private static final long CLICK_CONFIRM_DELAY_MILLIS = 300L;

	private boolean chronHasBeenEmpty = true;
	private int chronomatronSequenceIndex = 0;
	private int currentChronomatronRound = 0;
	private int currentUltraSequencerRound = 0;
	private Phase currentPhase = null;

	private int nextClickColor = DEFAULT_NEXT_COLOR;
	private int secondClickColor = DEFAULT_SECOND_COLOR;

	public ExperimentAddonsFeature() {
		super("experiment_addons", "Experiment Addons Helper", FeatureCategory.ENCHANTING, false);
		ExperimentationTableApi.start();

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
				if (!isEnabled() || !ExperimentationTableApi.inAddon()) return;
				readState(containerScreen.getMenu());
				checkMaxClicksAlert(containerScreen.getMenu());
			});
			ScreenEvents.remove(screen).register(s -> {
				if (ExperimentationTableApi.inAddon()) resetAddonsData();
			});
		});
		// Highlight boxes drawn from PreItemRenderRegistry (fires before item icons are drawn, so the box
		// sits UNDER the tile's own item icon instead of painted over it — per user request, "change all
		// item highlights... to render BEHIND the item they are highlighting") instead of alongside the
		// state-reading logic above in ScreenEvents.afterExtract (which drew on top of everything). State
		// reading stays in afterExtract since it isn't rendering and doesn't need to be render-timed.
		com.cokelord.skyblocksimplified.gui.PreItemRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !ExperimentationTableApi.inAddon()) return;
			if (currentPhase == Phase.REPLICATE) renderHighlights(graphics, screen);
		});
		// The Ultrasequencer's click-order numeral still needs to draw ON TOP of the item icon (it's
		// meant-to-be-read text, not a background frame) — kept on PreTooltipRenderRegistry, underneath the
		// tooltip but still above every item.
		com.cokelord.skyblocksimplified.gui.PreTooltipRenderRegistry.addListener((graphics, screen, mouseX, mouseY) -> {
			if (!isEnabled() || !ExperimentationTableApi.inAddon()) return;
			if (currentPhase == Phase.REPLICATE && ExperimentationTableApi.inUltrasequencer()) renderUltrasequencerNumerals(graphics, screen);
		});
	}

	@Override
	protected void onEnable() {
		ContainerClickRegistry.setRule(getId(), this::shouldCancelClick);
		ContainerClickRegistry.setAllowedListener(getId(), this::onClickAllowed);
	}

	@Override
	protected void onDisable() {
		ContainerClickRegistry.clearRule(getId());
		ContainerClickRegistry.clearAllowedListener(getId());
	}

	public int getNextClickColor() {
		return nextClickColor;
	}

	public void setNextClickColor(int color) {
		nextClickColor = color;
	}

	public int getSecondClickColor() {
		return secondClickColor;
	}

	public void setSecondClickColor(int color) {
		secondClickColor = color;
	}

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("nextClickColor", nextClickColor);
		obj.addProperty("secondClickColor", secondClickColor);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement data) {
		if (!data.isJsonObject()) return;
		JsonObject obj = data.getAsJsonObject();
		if (obj.has("nextClickColor")) nextClickColor = obj.get("nextClickColor").getAsInt();
		if (obj.has("secondClickColor")) secondClickColor = obj.get("secondClickColor").getAsInt();
	}

	private void resetAddonsData() {
		hypixelChronomatronSequence.clear();
		userChronomatronProgress.clear();
		chronomatronSlotColors.clear();
		hypixelUltrasequencerSlots.clear();
		userUltrasequencerProgress.clear();
		ultrasequencerDyeMap.clear();
		pendingChronoClicks.clear();
		pendingUltraClicks.clear();
		currentChronomatronRound = 0;
		currentUltraSequencerRound = 0;
		chronomatronSequenceIndex = 0;
		currentPhase = null;
		chronHasBeenEmpty = true;
		maxClicksAlerted = false;
	}

	// <editor-fold desc="State reading">
	private void readState(AbstractContainerMenu menu) {
		Phase oldPhase = currentPhase;
		currentPhase = readPhase(menu);
		processPendingClicks();
		if (currentPhase == null) return;

		if (ExperimentationTableApi.inChronomatron()) readChronomatron(menu, oldPhase);
		if (ExperimentationTableApi.inUltrasequencer()) readUltrasequencer(menu);
	}

	private Phase readPhase(AbstractContainerMenu menu) {
		ItemStack stack = safeSlotItem(menu, PHASE_STATUS_SLOT);
		if (stack == null) return null;
		String name = stack.getHoverName().getString();
		if (REPLICATE_PHASE_PATTERN.matcher(name).matches()) return Phase.REPLICATE;
		if (READ_PHASE_PATTERN.matcher(name).matches()) return Phase.READ;
		return null;
	}

	private void readChronomatron(AbstractContainerMenu menu, Phase oldPhase) {
		ItemStack roundStack = safeSlotItem(menu, ROUND_STATUS_SLOT);
		if (roundStack == null) return;
		Matcher roundMatcher = ROUND_ITEM_PATTERN.matcher(roundStack.getHoverName().getString());
		if (!roundMatcher.matches()) return;
		int round;
		try {
			round = Integer.parseInt(roundMatcher.group("round"));
		} catch (NumberFormatException e) {
			return;
		}
		currentChronomatronRound = round;

		int hypixelSizeNow = hypixelChronomatronSequence.size();
		int userSizeNow = userChronomatronProgress.size();

		List<String> activeColors = new ArrayList<>();
		for (Slot slot : menu.slots) {
			String color = chronoColorOf(slot.getItem());
			if (color != null) {
				chronomatronSlotColors.put(slot.index, color);
				if (!activeColors.contains(color)) activeColors.add(color);
			}
		}

		if (activeColors.isEmpty()) {
			chronHasBeenEmpty = true;
		} else if (!chronHasBeenEmpty) {
			return;
		} else {
			chronHasBeenEmpty = false;
		}

		String clickedColor = null;
		for (String candidate : activeColors) {
			String expected = chronomatronSequenceIndex < hypixelChronomatronSequence.size()
				? hypixelChronomatronSequence.get(chronomatronSequenceIndex) : null;
			if (expected == null || candidate.equals(expected)) {
				clickedColor = candidate;
				break;
			}
		}
		if (clickedColor == null) return;

		// currentPhase is always REPLICATE or READ here (readState already returned early on a null
		// phase read), so this only ever chooses between the two real branches below.
		boolean shouldReadLastReplicate = oldPhase == Phase.READ || hypixelSizeNow < currentChronomatronRound;
		boolean isReadingReady = oldPhase == null || oldPhase == Phase.READ;
		boolean shouldNotReadYet = currentPhase == Phase.REPLICATE ? !shouldReadLastReplicate : !isReadingReady;
		if (shouldNotReadYet) return;

		if (chronomatronSequenceIndex == hypixelSizeNow) {
			hypixelChronomatronSequence.add(clickedColor);
			chronomatronSequenceIndex = 0;
			userChronomatronProgress.clear();
		} else {
			chronomatronSequenceIndex++;
		}
	}

	private record UltraSlot(int sequenceNumber, int slotIndex) {}

	private void readUltrasequencer(AbstractContainerMenu menu) {
		List<UltraSlot> ordered = new ArrayList<>();
		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String clean = stack.getHoverName().getString().replaceAll("§.", "").trim();
			if (clean.isEmpty()) continue;
			int sequenceNumber;
			try {
				sequenceNumber = Integer.parseInt(clean);
			} catch (NumberFormatException e) {
				continue;
			}
			currentUltraSequencerRound = Math.max(currentUltraSequencerRound, sequenceNumber);
			ultrasequencerDyeMap.putIfAbsent(sequenceNumber, stack.copy());
			ordered.add(new UltraSlot(sequenceNumber, slot.index));
		}
		ordered.sort(Comparator.comparingInt(UltraSlot::sequenceNumber));

		boolean isOld = currentUltraSequencerRound != ordered.size();
		boolean alreadyKnown = hypixelUltrasequencerSlots.size() == ordered.size();
		if (isOld || alreadyKnown) return;

		hypixelUltrasequencerSlots.clear();
		userUltrasequencerProgress.clear();
		for (UltraSlot u : ordered) hypixelUltrasequencerSlots.add(u.slotIndex());
	}

	private ItemStack safeSlotItem(AbstractContainerMenu menu, int index) {
		if (menu.slots.size() <= index) return null;
		ItemStack stack = menu.getSlot(index).getItem();
		return stack.isEmpty() ? null : stack;
	}

	private String chronoColorOf(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String path = id.getPath();
		if (!path.equals("stained_hardened_clay") && !path.endsWith("_terracotta")) return null;
		String clean = stack.getHoverName().getString().replaceAll("§.", "").trim();
		return clean.isEmpty() ? null : clean.toLowerCase(Locale.ROOT);
	}
	// </editor-fold>

	// <editor-fold desc="Prevent misclicks">
	// Real bug found (per user report — "the experimentation table solvers are really buggy and sometimes
	// go forward a click even if the click didn't go through"): shouldCancelClick (and the two handle*
	// methods it used to call) used to mutate userChronomatronProgress/userUltrasequencerProgress directly
	// as a SIDE EFFECT of deciding whether to veto the click — but this predicate only knows whether ITS
	// OWN opinion is "cancel" or "allow"; it has no way to know whether some OTHER feature sharing
	// ContainerClickRegistry's same choke point (Slot Locking, etc.) vetoes the exact same click afterward.
	// A click this method judged "correct" could still end up cancelled overall by a different rule, and
	// the tracked progress had already advanced as if it went through regardless — exactly the reported
	// desync. Now a pure decision with no side effects; the actual progress commit only happens in
	// onClickAllowed below, which ContainerClickRegistry only calls once every registered rule has agreed
	// not to cancel the click.
	private boolean shouldCancelClick(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		if (!isToggleEnabled("experiment_addons_prevent_misclicks")) return false;
		if (currentPhase != Phase.REPLICATE || slot == null) return false;
		if (ExperimentationTableApi.inChronomatron()) return !isExpectedChronomatronClick(slot);
		if (ExperimentationTableApi.inUltrasequencer()) return !isExpectedUltrasequencerClick(slot);
		return false;
	}

	private int chronomatronOptimisticProgress() { return userChronomatronProgress.size() + pendingChronoClicks.size(); }
	private int ultraOptimisticProgress() { return userUltrasequencerProgress.size() + pendingUltraClicks.size(); }

	private boolean isExpectedChronomatronClick(Slot slot) {
		int index = chronomatronOptimisticProgress();
		if (index >= hypixelChronomatronSequence.size()) return true;
		// Color-based check, not exact-slot: Chronomatron always uses each color on exactly two physical
		// tiles and either one is a valid click (see highlightChronomatron's own doc comment for the "only
		// the top duplicate ever highlights/clicks" bug this fixes) — requiring slot.index to equal the one
		// specific slot that happened to flash wrongly rejected clicks on its visually-identical partner.
		String liveColor = chronoColorOf(slot.getItem());
		if (liveColor != null) chronomatronSlotColors.put(slot.index, liveColor);
		String clicked = chronomatronSlotColors.getOrDefault(slot.index, liveColor);
		String expected = hypixelChronomatronSequence.get(index);
		return clicked != null && clicked.equals(expected);
	}

	private boolean isExpectedUltrasequencerClick(Slot slot) {
		int index = ultraOptimisticProgress();
		if (index >= hypixelUltrasequencerSlots.size()) return true;
		int expectedSlot = hypixelUltrasequencerSlots.get(index);
		return slot.index == expectedSlot;
	}

	/** Only reached once ContainerClickRegistry has confirmed no rule (including this one's own) is
	 *  vetoing the click — but that's still only a CLIENT-side judgment (see the pending-click fields' own
	 *  doc comment above), so this queues the click as pending rather than committing it immediately;
	 *  processPendingClicks (called every frame from readState) is what actually commits or discards it. */
	private void onClickAllowed(Slot slot, int slotId, int mouseButton, ContainerInput type) {
		if (!isToggleEnabled("experiment_addons_prevent_misclicks")) return;
		if (currentPhase != Phase.REPLICATE || slot == null) return;
		if (ExperimentationTableApi.inChronomatron()) {
			int index = chronomatronOptimisticProgress();
			if (index >= hypixelChronomatronSequence.size()) return;
			String expected = hypixelChronomatronSequence.get(index);
			// Color-based, same as isExpectedChronomatronClick above — accepts either physical tile of a
			// matching duplicate-color pair, not just the single one that happened to flash.
			String clicked = chronomatronSlotColors.get(slot.index);
			boolean matches = clicked != null && clicked.equals(expected);
			if (matches) {
				pendingChronoClicks.add(new PendingChronoClick(expected, System.currentTimeMillis(), currentChronomatronRound));
			}
		} else if (ExperimentationTableApi.inUltrasequencer()) {
			int index = ultraOptimisticProgress();
			if (index >= hypixelUltrasequencerSlots.size()) return;
			int expectedSlot = hypixelUltrasequencerSlots.get(index);
			if (slot.index == expectedSlot) {
				pendingUltraClicks.add(new PendingUltraClick(slot.index, System.currentTimeMillis(), currentUltraSequencerRound));
			}
		}
	}

	/** Commits (or discards) every pending click once it's aged past a short real-round-trip margin,
	 *  oldest first — called every frame from readState. A pending click is discarded instead of committed
	 *  if the round it was clicked against has since moved on (round number changed) or the game has
	 *  restarted the demonstration (back to READ phase) while it was still waiting: either is real,
	 *  observable evidence that this exact attempt didn't stick server-side, which committing anyway is
	 *  exactly the reported bug. Stops at the first still-too-young or still-valid-but-undecided entry each
	 *  call, same as an ordinary FIFO queue drain — later entries can't confirm before earlier ones anyway. */
	private void processPendingClicks() {
		long now = System.currentTimeMillis();
		while (!pendingChronoClicks.isEmpty()) {
			PendingChronoClick p = pendingChronoClicks.peekFirst();
			if (currentPhase == Phase.READ || currentChronomatronRound != p.roundAtArm()) {
				pendingChronoClicks.pollFirst();
				continue;
			}
			if (now - p.armedAtMillis() < CLICK_CONFIRM_DELAY_MILLIS) break;
			pendingChronoClicks.pollFirst();
			userChronomatronProgress.add(p.color());
		}
		while (!pendingUltraClicks.isEmpty()) {
			PendingUltraClick p = pendingUltraClicks.peekFirst();
			if (currentPhase == Phase.READ || currentUltraSequencerRound != p.roundAtArm()) {
				pendingUltraClicks.pollFirst();
				continue;
			}
			if (now - p.armedAtMillis() < CLICK_CONFIRM_DELAY_MILLIS) break;
			pendingUltraClicks.pollFirst();
			userUltrasequencerProgress.add(p.slotIndex());
		}
	}
	// </editor-fold>

	// <editor-fold desc="Next-click highlighting">
	// PreItemRenderRegistry fires from inside AbstractContainerScreen's own already-translated
	// pose().translate(leftPos, topPos) block — slot.x/slot.y are already the right coordinates here, no
	// manual leftPos/topPos offset needed (see that registry's own doc comment). renderUltrasequencerNumerals
	// below is a SEPARATE listener on PreTooltipRenderRegistry instead (fires after leftPos/topPos has been
	// popped, so it still needs the manual offset) since that text needs to stay ON TOP of the item icon to
	// be legible, unlike the background highlight boxes here.
	private void renderHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		if (ExperimentationTableApi.inUltrasequencer() && currentUltraSequencerRound >= 1) {
			highlightUltrasequencerBoxes(graphics, screen);
		}
		if (ExperimentationTableApi.inChronomatron() && currentChronomatronRound >= 1) {
			highlightChronomatron(graphics, screen);
		}
	}

	private void highlightChronomatron(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		// Optimistic (committed + still-pending-confirmation) count, not just the durable one — so the
		// highlight still advances immediately on a correct click instead of visibly lagging behind by the
		// pending-confirmation delay (see the pending-click fields' own doc comment).
		int progress = chronomatronOptimisticProgress();
		if (progress >= hypixelChronomatronSequence.size()) return;

		// Real bug found (per user report — "only the top block of a matching duplicate-color pair ever gets
		// marked, the bottom one never highlights or lets me click it"): Chronomatron's grid always uses each
		// color on exactly two physical tiles, and either one is a valid click for a given sequence step —
		// but this used to short-circuit here on ONLY the single slot hypixelChronomatronSlots recorded as
		// having actually flashed for that step (a single-value slot-per-color record), so a step's other,
		// visually-identical tile of the same color was never highlighted and (see isExpectedChronomatronClick/
		// onClickAllowed below, which mirrored this same exact-slot check) never even accepted as a correct
		// click. Falling through unconditionally to the color-based scan below fixes both: it already walks
		// every slot and marks ALL of them sharing the expected color, not just one.
		AbstractContainerMenu menu = screen.getMenu();

		String next = hypixelChronomatronSequence.get(progress);
		String second = progress + 1 < hypixelChronomatronSequence.size() ? hypixelChronomatronSequence.get(progress + 1) : null;

		for (Slot slot : menu.slots) {
			String liveColor = chronoColorOf(slot.getItem());
			if (liveColor != null) chronomatronSlotColors.put(slot.index, liveColor);
			String color = chronomatronSlotColors.get(slot.index);
			if (color == null) continue;
			int highlightColor;
			if (color.equals(next)) highlightColor = nextClickColor;
			else if (color.equals(second)) highlightColor = secondClickColor;
			else continue;
			highlightSlot(graphics, slot, highlightColor);
		}
	}

	// Per user spec: a literal 1/2/3/... numeral over each slot in click order (not a fading box) — green
	// for the current click, yellow for the next one, red for everything after that.
	private static final int ULTRA_CURRENT_COLOR = 0xFF55FF55;
	private static final int ULTRA_NEXT_COLOR = 0xFFFFFF55;
	private static final int ULTRA_LATER_COLOR = 0xFFFF5555;

	private void highlightUltrasequencerBoxes(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		int progress = ultraOptimisticProgress();
		if (progress >= hypixelUltrasequencerSlots.size()) return;
		AbstractContainerMenu menu = screen.getMenu();

		for (int i = progress; i < hypixelUltrasequencerSlots.size(); i++) {
			int slotIndex = hypixelUltrasequencerSlots.get(i);
			if (slotIndex >= menu.slots.size()) continue;
			int stepsAhead = i - progress;
			int color = stepsAhead == 0 ? ULTRA_CURRENT_COLOR : stepsAhead == 1 ? ULTRA_NEXT_COLOR : ULTRA_LATER_COLOR;
			Slot slot = menu.getSlot(slotIndex);
			// Halved from the original 0x60 alpha, same "too bright" report as Chronomatron's highlight —
			// the number text drawn on top (see renderUltrasequencerNumerals) stays at its own full
			// opacity for legibility, only this background box is toned down.
			highlightSlot(graphics, slot, (color & 0xFFFFFF) | 0x30000000);
		}
	}

	private void renderUltrasequencerNumerals(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
		int progress = ultraOptimisticProgress();
		if (progress >= hypixelUltrasequencerSlots.size()) return;

		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int leftPos = accessor.skyblocksimplified$getLeftPos();
		int topPos = accessor.skyblocksimplified$getTopPos();
		AbstractContainerMenu menu = screen.getMenu();
		Font font = Minecraft.getInstance().font;

		for (int i = progress; i < hypixelUltrasequencerSlots.size(); i++) {
			int slotIndex = hypixelUltrasequencerSlots.get(i);
			if (slotIndex >= menu.slots.size()) continue;
			int stepsAhead = i - progress;
			int color = stepsAhead == 0 ? ULTRA_CURRENT_COLOR : stepsAhead == 1 ? ULTRA_NEXT_COLOR : ULTRA_LATER_COLOR;
			Slot slot = menu.getSlot(slotIndex);
			int x = leftPos + slot.x;
			int y = topPos + slot.y;
			String number = String.valueOf(i + 1);
			int textX = x + (SLOT_SIZE - font.width(number)) / 2;
			int textY = y + (SLOT_SIZE - font.lineHeight) / 2;
			graphics.text(font, number, textX + 1, textY + 1, 0xFF000000);
			graphics.text(font, number, textX, textY, color);
		}
	}

	private void highlightSlot(GuiGraphicsExtractor graphics, Slot slot, int color) {
		int x = slot.x;
		int y = slot.y;
		RenderUtil.fillRounded(graphics, x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, 2, color);
	}
	// </editor-fold>

	// <editor-fold desc="Max clicks alert">
	// Chronomatron: fixed threshold read from the Bookshelf item's own stack count (the max useful round
	// is always 10). Ultrasequencer's stop point is detected differently — see checkSixStackThreshold.
	private static final int CHRONOMATRON_MAX_CLICKS = 10;
	private boolean maxClicksAlerted = false;

	private void checkMaxClicksAlert(AbstractContainerMenu menu) {
		if (!isToggleEnabled("experiment_addons_max_clicks_alert")) return;
		if (ExperimentationTableApi.inChronomatron()) {
			checkBookshelfThreshold(menu, CHRONOMATRON_MAX_CLICKS);
		} else if (ExperimentationTableApi.inUltrasequencer()) {
			// Round-count/bookshelf-threshold detection isn't reliable here per user report — instead
			// watch for any non-player-inventory stack reaching a size of 6, which is the actual signal
			// Hypixel shows on the table once the maximum useful Ultrasequencer chain has been reached.
			checkSixStackThreshold(menu);
		}
	}

	private void checkBookshelfThreshold(AbstractContainerMenu menu, int threshold) {
		int bookshelfCount = -1;
		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || stack.getItem() != net.minecraft.world.item.Items.BOOKSHELF) continue;
			bookshelfCount = stack.getCount();
			break;
		}
		if (bookshelfCount < threshold) {
			maxClicksAlerted = false;
			return;
		}
		alertMaxClicks();
	}

	private void checkSixStackThreshold(AbstractContainerMenu menu) {
		var playerInventory = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getInventory() : null;
		boolean foundSixStack = false;
		for (Slot slot : menu.slots) {
			if (playerInventory != null && slot.container == playerInventory) continue;
			// The round/phase status items (read elsewhere in this class off these exact slot indices) are
			// Hypixel's own clock/counter icons, which commonly encode their live numeric value AS the
			// item's own stack count (e.g. a clock item showing a count of 6 while 6 seconds remain on the
			// timer) — coincidentally hitting exactly the same count this scan is watching for, which is
			// the actual "false-triggers on the timer item" bug: the timer's own countdown value, not a
			// real max-chain signal from a genuine sequence-tile stack, was setting off the alert.
			if (slot.index == ROUND_STATUS_SLOT || slot.index == PHASE_STATUS_SLOT) continue;
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && stack.getCount() == 6) {
				foundSixStack = true;
				break;
			}
		}
		if (!foundSixStack) {
			maxClicksAlerted = false;
			return;
		}
		alertMaxClicks();
	}

	private void alertMaxClicks() {
		if (maxClicksAlerted) return;
		maxClicksAlerted = true;

		playBeep();
		var player = Minecraft.getInstance().player;
		if (player != null) {
			Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§eYou have reached the maximum extra Superpairs clicks from this add-on!"));
		}
	}

	private void playBeep() {
		Minecraft mc = Minecraft.getInstance();
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", "ui.button.click");
		SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(id);
		mc.getSoundManager().play(SimpleSoundInstance.forUI(soundEvent, 1f, 1f));
	}
	// </editor-fold>

	private boolean isToggleEnabled(String id) {
		Feature feature = FeatureRegistry.get(id);
		return feature != null && feature.isEnabled();
	}

	@Override
	public String getDescription() {
		return "Highlights the next correct click for the Experimentation Table's Chronomatron and Ultrasequencer minigames.";
	}
}
