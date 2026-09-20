package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.mixin.BossHealthOverlayAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.UUID;

/** Movable "{boss name} {hp}%" readout, real HP straight from vanilla's own boss-bar packet — see {@link
 *  BossHealthOverlayAccessor}'s own doc comment for exactly which decompiled classes/fields confirm this
 *  (same signal Devonian's own boss-bar HP feature reads, ported over the same way SkyblockAddons/Devonian
 *  ports already in this project are: matched against real reference source, not guessed). Hypixel only
 *  ever shows one boss bar at a time in practice (Necron's F7 fight swaps the single bar's name between
 *  Storm/Goldor/Maxor/Necron rather than stacking several), so this reads whichever entry the overlay's
 *  map currently holds rather than building multi-line rendering for a case that doesn't happen. The
 *  optional phase suffix reuses {@link DungeonState#getF7Phase()} — SBS's own existing chat-line-confirmed
 *  phase tracking, not a new detection path. */
public class BossBarFeature extends Feature implements MoveableWidget {
	private int textColor = 0xFFFFFFFF;
	private boolean hideVanilla = false;
	private boolean showPhase = true;

	private String lastLine = null;
	// Per user report ("When the bossbar gets no updates, it should remember its last state. Im just waiting
	// on blood mobs to spawn currently and the watcher bossbar is gone... make sure it doesnt transfer from
	// like storm split into terminals and the bossbar says storm instead"): this class used to clear lastLine
	// on two fixed timeouts — a frozen (same UUID/progress) reading held too long, or vanilla's own
	// boss-overlay events map sitting empty too long — neither of which can tell "a real quiet gap mid-phase"
	// (Watcher waiting on blood mobs — should keep showing the last reading, however long it takes) apart
	// from "the dungeon actually moved on to a phase with no boss bar of its own" (Storm ending into
	// Terminals — should NOT keep showing the old line) purely from elapsed time; both cases can run
	// arbitrarily long. DungeonState's own F7 phase tracking already tells them apart directly — recorded
	// alongside lastLine every time it's set from a real event, and checked every tick before anything else:
	// a real phase change clears lastLine immediately (no waiting on a timeout), while an unchanged phase now
	// holds it indefinitely regardless of how long the gap runs, with isInDungeon() flipping false (an actual
	// dungeon exit) as the only other thing that clears it.
	private DungeonState.F7Phase lastLinePhase = null;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.02f, 1f);
	private final HudPosition position = defaultPosition.copy();

	private static BossBarFeature instance;
	// HudElementRegistry.addLast throws IllegalArgumentException on a duplicate id (confirmed via
	// decompiling Fabric's own HudElementRegistryImpl) — onEnable() used to call it unconditionally, so
	// toggling this feature off then back on crashed the render pipeline the moment it re-enabled. Same
	// guarded-registration pattern already used correctly elsewhere in this codebase (see
	// PlayerDisplayFeature/ChatCopyFeature): register exactly once, ever, and let the render callback's own
	// isVisible() check make it a no-op while disabled.
	private static boolean listenersRegistered = false;

	public BossBarFeature() {
		super("boss_bar", "Boss Bar", FeatureCategory.COMBAT, false);
		instance = this;
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() {
		return "Dungeons";
	}

	@Override
	protected void onEnable() {
		if (listenersRegistered) return;
		listenersRegistered = true;
		HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "boss_bar"),
			(graphics, deltaTracker) -> {
				if (instance == null || !instance.isVisible() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()
				|| com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
				Minecraft mc = Minecraft.getInstance();
				int x = Math.round(instance.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.render(graphics, x, y, instance.position.scale);
			});
	}

	@Override
	protected void onDisable() {
		lastLine = null;
		lastLinePhase = null;
	}

	@Override
	public void onTick(Minecraft client) {
		// Real bug found (per user report — "it still says 100% [P1] after leaving the bossfight. It should
		// fully hide all elements in regular dungeons then show in the blood room and the bossfight"): this
		// used to gate purely on vanilla's own boss-overlay events map being non-empty, with no dungeon-state
		// check at all — if Hypixel doesn't send a boss-bar REMOVE packet on the way out (leaving via a warp/
		// portal mid-fight rather than the fight ending cleanly), that map can keep holding the LAST real HP
		// reading indefinitely, and this widget had no other signal telling it the fight was actually over.
		//
		// Second real bug found (per user report — "Bossbar doesn't show the watcher's progress"): gating on
		// the narrower DungeonState.isInBoss() (true only once the real boss fight has actually started —
		// see bossEntryMessageSeen's own doc comment) meant the Watcher's own real vanilla boss bar, shown
		// during the F1-F7 gatekeeping wait BEFORE the boss fight begins, never got a chance to render at
		// all. Broadened to isInDungeon() instead...
		//
		// Third real bug found (per user report — "The bossbar has started showing in regular dungeons, not
		// specifically when blood has been opened or when the user is in the bossfight"): isInDungeon() alone
		// is true for the ENTIRE run, not just blood/boss — broadening this far to fix the Watcher case above
		// was too broad, and any real vanilla boss bar Hypixel happens to be showing for any other reason
		// while merely somewhere in the dungeon (not blood/boss at all) would render here unconditionally.
		// DungeonState.isWatcherGateActive() now covers exactly the real gap isInBoss() alone can't (the
		// Watcher fight itself, which starts on the confirmed Blood Door line and ends when the Watcher is
		// actually cleared — see that method's own doc comment for why isInBoss() doesn't become true until
		// that exact moment), so the two together are the precise "blood has been opened or in the bossfight"
		// condition the user described, instead of the much broader isInDungeon().
		if (!DungeonState.isInDungeon() || !(DungeonState.isInBoss() || DungeonState.isWatcherGateActive())) {
			lastLine = null;
			lastLinePhase = null;
			return;
		}
		DungeonState.F7Phase currentPhase = DungeonState.getF7Phase();
		// See lastLinePhase's own doc comment: a real phase change is the one signal that reliably tells
		// "still the same lull, just a long one" apart from "the dungeon has genuinely moved on and this old
		// line no longer applies" — checked before anything else below, and clears immediately (no grace
		// period) since there's nothing ambiguous left to wait out once this is true.
		if (lastLine != null && lastLinePhase != null && currentPhase != lastLinePhase) {
			lastLine = null;
			lastLinePhase = null;
		}
		var overlay = client.gui.hud.getBossOverlay();
		Map<UUID, LerpingBossEvent> events = ((BossHealthOverlayAccessor) (Object) overlay).skyblocksimplified$getEvents();
		// Per user report ("When the bossbar gets no updates, it should remember its last state. Im just
		// waiting on blood mobs to spawn currently and the watcher bossbar is gone"): an empty overlay map (no
		// active boss-bar packet right now) or a frozen one (same UUID/progress every tick) no longer clears
		// lastLine on their own at all — the phase-change check above is the only thing that does that now, so
		// a real quiet gap, however long, with the phase unchanged just keeps showing the last reading.
		if (events.isEmpty()) {
			// Real bug found (per user report — "the blood room bossbar fix didnt work. It needs to trigger on
			// 'The BLOOD DOOR has been opened!' in the chat"): the gate above (isWatcherGateActive()) correctly
			// opens the WINDOW this readout is allowed to show in starting from that exact chat line, but the
			// actual TEXT only ever came from vanilla's own boss-overlay packet (events, read below) — so with
			// "Hide Vanilla" on, the instant the door opens the real vanilla bar gets force-cancelled by
			// BossBarHideMixin (unconditional whenever this feature+toggle are on) while this readout stays
			// blank until Hypixel's own Watcher boss-bar packet happens to arrive, a real (if usually short) gap
			// that reads as "nothing shows when the door opens" even though the gate itself opened correctly.
			// Seeds a plain placeholder the instant the gate opens with nothing to show yet, so the bar-line
			// area is never blank during that gap — overwritten below by the real reading the moment Hypixel's
			// own packet actually arrives.
			if (lastLine == null && DungeonState.isWatcherGateActive() && !DungeonState.isInBoss()) {
				lastLine = "The Watcher";
				lastLinePhase = currentPhase;
			}
			return;
		}
		LerpingBossEvent event = events.values().iterator().next();
		float progress = event.getProgress();
		String name = event.getName().getString();
		int pct = Math.round(progress * 100f);

		// Per user report ("remove the [P4] when all watcher mobs are dead, i dont know why the bossbar says
		// that"): the Watcher fight is a pre-boss gate room, not a real F7 phase at all — DungeonState's own
		// height-band phase calc (see F7Phase's own doc comment) coincidentally lands in P4's Y range there,
		// which is meaningless for a fight that isn't part of the P1-P5 sequence. Suppressed only once
		// BloodCampFeature's own Watcher Mobs Left Counter confirms the fight is actually over (per the
		// user's exact wording), not unconditionally for the whole fight.
		boolean isWatcherBar = name.toLowerCase(java.util.Locale.ROOT).contains("watcher");
		String phaseSuffix = "";
		if (showPhase && currentPhase != DungeonState.F7Phase.UNKNOWN
			&& !(isWatcherBar && com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature.isWatcherAllMobsDead())) {
			phaseSuffix = " [" + currentPhase.name() + "]";
		}

		// Per user request ("i actually want the watcher N/N to replace the % in the bossbar. Tie this to
		// the show watcher mobs left and remove the gui element... just replaces the percentage with the
		// mobs killed out of mobs total instead"): swaps in Blood Camp's own "killed/total" reading in place
		// of the raw HP percentage, only for the Watcher's own bar and only once that reading actually has
		// data (falls back to the real percentage otherwise, e.g. before the first kill establishes a
		// baseline).
		String amountText = isWatcherBar && com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature.isWatcherCounterReady()
			? com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature.watcherCounterText()
			: pct + "%";

		lastLine = name + " " + amountText + phaseSuffix;
		lastLinePhase = currentPhase;
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	public boolean isVisible() { return isEnabled() && lastLine != null; }

	@Override
	public boolean isRelevantToCurrentIsland() { return true; }

	@Override
	public boolean hasVisibleContent() { return lastLine != null; }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		Font font = Minecraft.getInstance().font;
		String text = lastLine != null ? lastLine : "Maxor 42%";
		int width = Math.round(font.width(text) * scale);
		int height = Math.round(font.lineHeight * scale);
		if (Math.abs(scale - 1f) < 0.01f) {
			graphics.text(font, text, x, y, textColor);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			graphics.text(font, text, x, y, textColor);
			graphics.pose().popMatrix();
		}
		return new Size(width, height);
	}

	public int getTextColor() { return textColor; }
	public void setTextColor(int value) { textColor = value; }
	public boolean isHideVanilla() { return hideVanilla; }
	public void setHideVanilla(boolean value) { hideVanilla = value; }
	public boolean isShowPhase() { return showPhase; }
	public void setShowPhase(boolean value) { showPhase = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("textColor", textColor);
		obj.addProperty("hideVanilla", hideVanilla);
		obj.addProperty("showPhase", showPhase);
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("textColor")) textColor = obj.get("textColor").getAsInt();
		if (obj.has("hideVanilla")) hideVanilla = obj.get("hideVanilla").getAsBoolean();
		if (obj.has("showPhase")) showPhase = obj.get("showPhase").getAsBoolean();
		if (obj.has("anchorX")) position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY")) position.anchorY = obj.get("anchorY").getAsFloat();
		if (obj.has("scale")) position.scale = obj.get("scale").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Movable HUD readout showing the current boss's name and real HP percentage.";
	}
}
