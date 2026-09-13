package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task #597/#636 ("Leap Counter: SS/Maxor/EE2/EE3 counters still deferred (Core done in 1.1.14)"): this round
 * builds the remaining six zones off the user's own explicit spec (their message listing each class/room and
 * exactly how many need to leap for it to hide, plus an alternate phase-based hide trigger for each) —
 * "Healer: Simon Says, 3 need to leap for it to hide (OR terminals phase starts)" and five more in the same
 * shape. Per the user's own instruction ("The sentence/name for it is the corresponding coordinates of the
 * positional message preset"), every zone's center/range is taken verbatim from {@link
 * PositionalMessagesFeature#PRESETS} — those coordinates were already user-supplied and verified in an earlier
 * round, so re-deriving them here would just be re-guessing data that already exists. The phase-based
 * alternate hide triggers reuse this codebase's own already-tracked, chat-confirmed state ({@link
 * DungeonState#getF7Phase()} for "terminals phase"/"split starts" wording, {@link
 * DungeonState#getTerminalSection()} for "S2 starts"/"S3 starts" wording) rather than inventing new detection,
 * since both were already real, live-verified signals before this feature touched them.
 */
public class LeapCounterFeature extends Feature implements MoveableWidget {
	/** One tracked zone: a teammate headcount inside a sphere (matches {@link PositionalMessagesFeature}'s own
	 *  distance-check shape — radial XZ plus a Y-slack band — for consistency with the coordinates' original
	 *  source), hidden once enough teammates have leaped there ({@code requiredToHide}).
	 *
	 *  <p>Round 2 (per user correction — "The leap detectors and leap counters work, but they should trigger
	 *  in different phases. They should all trigger based on their coordinates. They match coordinates fully
	 *  with the presets in the positional messages, which means that when the user is playing the correct
	 *  role and at those coordinates (or near, within like 2 blocks) THEN it should show"): these zones
	 *  used to be visible to EVERYONE the whole time floor 7's boss fight was active, regardless of the
	 *  viewer's own class or position — the only gating was the hide condition. The user's own correction
	 *  points at the exact same coordinates ALREADY carrying a real, verified role+phase+section restriction
	 *  in {@link PositionalMessagesFeature#PRESETS} (the very source these coordinates were copied from) —
	 *  "At SS!" is HEALER-only during {@code BossPart.STORM}, "At Core!" is MAGE-only during
	 *  {@code BossPart.GOLDOR} + section S2/S3, etc. Ported that same real per-location role/phase/section
	 *  restriction here as the SHOW gate (not just an alternate hide trigger like before): a zone is only
	 *  ever visible to a viewer whose own live-tracked class matches {@code role}, while
	 *  {@link DungeonState#getF7Phase()} exactly equals {@code phase} (not "has reached", an exact match —
	 *  this zone simply isn't relevant before OR after its one real phase), within any listed
	 *  {@code sections} (empty = no restriction, same "ALL" semantics {@code PosMessage.sections} uses), AND
	 *  the viewer is themselves within {@code range} of the zone (same formula as
	 *  {@link PositionalMessagesFeature#isInRange}) — not merely "some teammate is standing there", the
	 *  viewer has to actually be at their own assigned spot for their own reminder to show, exactly matching
	 *  how Positional Messages' own trigger works. {@code requiredToHide} then hides it once enough teammates
	 *  (tracked separately from the viewer's own presence) have shown up. */
	private record Zone(String key, String label, double cx, double cy, double cz, double range,
						 int requiredToHide, DungeonClass role, DungeonState.F7Phase phase,
						 Set<DungeonState.TerminalSection> sections) {}

	private static final double Y_SLACK = 1.0;

	private static final DungeonState.TerminalSection S1 = DungeonState.TerminalSection.S1;
	private static final DungeonState.TerminalSection S2 = DungeonState.TerminalSection.S2;
	private static final DungeonState.TerminalSection S3 = DungeonState.TerminalSection.S3;

	// Coordinates/ranges AND role/phase/section restrictions copied verbatim from
	// PositionalMessagesFeature.PRESETS (see class doc comment on why re-deriving them here would just be
	// re-guessing data that already exists, real and user-verified, one class over):
	// "At SS!" HEALER/STORM, "At HIGH EE2!" MAGE/GOLDOR+S1, "At EE3!" HEALER/GOLDOR+S2, "At Core!"
	// MAGE/GOLDOR+{S2,S3}, "At GY Dropoff!" BERSERK/MAXOR, "Waiting for Healer!" BERSERK/STORM.
	private static final List<Zone> ZONES = List.of(
		new Zone("ss", "SS", 108, 120, 94, 3, 3, DungeonClass.HEALER, DungeonState.F7Phase.P2, Set.of()),
		new Zone("high_ee2", "High EE2", 60.5, 132, 139, 2, 4, DungeonClass.MAGE, DungeonState.F7Phase.P3, Set.of(S1)),
		new Zone("ee3", "EE3", 2, 109, 104.5, 1, 4, DungeonClass.HEALER, DungeonState.F7Phase.P3, Set.of(S2)),
		new Zone("core", "Core", 54.5, 115, 50.5, 0.5, 4, DungeonClass.MAGE, DungeonState.F7Phase.P3, Set.of(S2, S3)),
		new Zone("waiting_healer", "Waiting for Healer", 101, 115, 51, 1, 1, DungeonClass.BERSERK, DungeonState.F7Phase.P2, Set.of()),
		new Zone("gy_dropoff", "GY Dropoff", 56.5, 169, 53.5, 1, 3, DungeonClass.BERSERK, DungeonState.F7Phase.P1, Set.of())
	);

	// Per the deferred spec's own "hide-after-timeout rules" requirement: a zone stays visible for a short
	// grace period after it first becomes "satisfied" (count/phase threshold reached), instead of disappearing
	// (and potentially popping back if a teammate briefly steps out at the boundary) the instant it happens.
	private static final long HIDE_AFTER_MS = 5000L;

	private final HudPosition defaultPosition = new HudPosition(0.5f, 0.15f, 1f);
	private final HudPosition position = defaultPosition.copy();

	// Round 3 (per user report — "It seems to go up one when someone leaps to me and i am at the
	// coordinates"): this used to be a LIVE snapshot recomputed from scratch every tick — counting ANY
	// teammate currently within range, regardless of their own class. Spirit Leap teleports the LEAPING
	// player to stand right next to whoever they targeted; if the viewer (who this widget is rendered for)
	// is standing in a zone, any teammate who leaps TO the viewer lands inside that same zone as a pure side
	// effect of the leap target being the viewer — nothing to do with that teammate actually being the
	// right class for this spot. Two real fixes: (1) only a teammate whose OWN class matches the zone's
	// assigned role can ever count toward it (a Berserk who leaps to a Healer standing at SS was never
	// supposed to count for SS in the first place — SS is the Healer's own spot); (2) counting is now
	// STICKY (a set of names who have been confirmed in range at least once this boss fight), not a live
	// recount that happens to catch a transient visitor mid-teleport — matches the original "N need to
	// leap for it to hide" framing (a tally of who HAS leaped there, not who's standing there this instant).
	private final Map<String, java.util.Set<String>> arrivedByZone = new LinkedHashMap<>();
	// Round 4 (per user report — "The leap overlay still seems to detect leaps that happened earlier or
	// something. It should only start detecting when the GUI element is active, and reset after hiding or
	// being completed"): Round 3's fix above only gated the tally loop on `isZoneVisible(zone)`, but that
	// gate is per-TICK, not per-teammate-arrival — the very first tick the zone becomes visible to the
	// viewer, it still scans every CURRENT teammate position and immediately counts anyone already standing
	// there, which is exactly the same "starts at 1 instead of 0" symptom under a different trigger (a
	// teammate who leaped there well before the viewer arrived, and simply never left, reads as an instant
	// count the moment the viewer's own gate opens). Tracks each zone's in-range membership every tick
	// (independent of visibility, so a real "was already there" case is known) and only tallies a teammate
	// into the sticky `arrived` set on the tick they TRANSITION from out-of-range to in-range while the zone
	// is actually visible — a standing presence from before the counter appeared is never counted, only a
	// fresh arrival is.
	private final Map<String, java.util.Set<String>> inRangeLastTick = new LinkedHashMap<>();
	private final Map<String, Long> lastUnsatisfiedMillis = new LinkedHashMap<>();
	// Per user request ("Allow users also to toggle the ones they want"): each zone independently enable-able,
	// default on so this round's new zones behave the same as the existing Core zone did before this change.
	private final Map<String, Boolean> zoneEnabled = new LinkedHashMap<>();
	// Per user request ("&h means custom color, that the user can choose in the settings for that module"):
	// used for the punctuation around the count fraction (see renderZones). Default matches this widget's
	// prior flat text color.
	private int customColor = 0xFFFFFFFF;

	private static boolean listenersRegistered = false;
	private static LeapCounterFeature instance;

	public LeapCounterFeature() {
		super("leap_counter", "Leap Counter", FeatureCategory.COMBAT, false);
		instance = this;
		for (Zone zone : ZONES) zoneEnabled.put(zone.key(), true);
		HudWidgetRegistry.register(this);
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	public boolean isZoneEnabled(String key) { return zoneEnabled.getOrDefault(key, true); }
	public void setZoneEnabled(String key, boolean enabled) { zoneEnabled.put(key, enabled); }
	public List<String[]> getZoneLabels() {
		List<String[]> labels = new ArrayList<>();
		for (Zone zone : ZONES) labels.add(new String[]{zone.key(), zone.label()});
		return labels;
	}

	@Override
	public HudPosition getPosition() { return position; }

	@Override
	public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "leap_counter"),
				(graphics, deltaTracker) -> {
					if (instance == null || !instance.isVisible()) return;
					instance.render(graphics, Math.round(instance.position.anchorX * Minecraft.getInstance().getWindow().getGuiScaledWidth()),
						Math.round(instance.position.anchorY * Minecraft.getInstance().getWindow().getGuiScaledHeight()), instance.position.scale);
				});
		}
	}

	@Override
	public void onTick(Minecraft client) {
		if (!isEnabled() || !DungeonState.isInBoss() || DungeonState.getFloorNumber() != 7) {
			arrivedByZone.clear();
			inRangeLastTick.clear();
			return;
		}
		long now = System.currentTimeMillis();
		for (Zone zone : ZONES) {
			java.util.Set<String> arrived = arrivedByZone.computeIfAbsent(zone.key(), k -> new java.util.HashSet<>());
			// Tracks in-range membership every tick (independent of visibility) so a "was already standing
			// here" teammate can be told apart from one who just walked/leaped in — see the field-level
			// comment on inRangeLastTick for the full story.
			java.util.Set<String> previouslyInRange = inRangeLastTick.getOrDefault(zone.key(), java.util.Set.of());
			java.util.Set<String> currentlyInRange = new java.util.HashSet<>();
			boolean visible = isZoneVisible(zone);
			// Real bug found (per user report — "it counts the user as one of the players within the area. It
			// should only count players that are in that area EXCEPT the user"): getTeammates() includes the
			// LOCAL viewer's own entry — since the viewer only ever sees this zone's counter while standing in
			// it themselves (isZoneVisible's own position gate), they were unconditionally tallied as their own
			// first "arrival" the moment the counter appeared, regardless of any other teammate. Switched to
			// getTeammatesNoSelf() so only genuinely OTHER party members ever count toward the total.
			for (DungeonPlayer teammate : DungeonState.getTeammatesNoSelf()) {
				Player entity = teammate.entity;
				if (entity == null || teammate.clazz != zone.role()) continue;
				double dx = entity.getX() - zone.cx(), dz = entity.getZ() - zone.cz(), dy = entity.getY() - zone.cy();
				if (dx * dx + dz * dz <= zone.range() * zone.range() && Math.abs(dy) <= zone.range() + Y_SLACK) {
					currentlyInRange.add(teammate.name);
					if (visible && !previouslyInRange.contains(teammate.name)) arrived.add(teammate.name);
				}
			}
			inRangeLastTick.put(zone.key(), currentlyInRange);
			int count = arrived.size();
			if (!isZoneSatisfied(zone, count)) lastUnsatisfiedMillis.put(zone.key(), now);
		}
	}

	// Round 2 (see Zone's own doc comment): the phase/section-based early-hide this used to also check is
	// gone — that's now enforced by isZoneVisible's own exact-phase SHOW gate below (a zone whose phase has
	// moved past its one real relevant phase simply never passes that gate any more, so a separate ordinal
	// hide check here would just be redundant).
	private boolean isZoneSatisfied(Zone zone, int count) {
		return count >= zone.requiredToHide();
	}

	// Round 2 (see Zone's own doc comment): the viewer's own class must match the zone's real assigned role,
	// the dungeon must currently be in exactly that zone's real relevant phase (not "has reached" — before
	// or after that one phase, the zone just isn't relevant), within any listed sections, AND the viewer
	// must themselves be within range of the real coordinates — same distance formula
	// PositionalMessagesFeature#isInRange uses, so "near" here means the exact same "near" that feature's
	// own presets already use for these identical coordinates, not a separate guessed number.
	private boolean isZoneVisible(Zone zone) {
		if (!isZoneEnabled(zone.key())) return false;
		Long lastUnsatisfied = lastUnsatisfiedMillis.get(zone.key());
		boolean withinHideGrace = lastUnsatisfied == null || (System.currentTimeMillis() - lastUnsatisfied) < HIDE_AFTER_MS;
		if (!withinHideGrace) return false;
		DungeonClass self = selfClass();
		if (self == null || self != zone.role()) return false;
		if (DungeonState.getF7Phase() != zone.phase()) return false;
		if (!zone.sections().isEmpty() && !zone.sections().contains(DungeonState.getTerminalSection())) return false;
		return isNearZone(zone);
	}

	private boolean isNearZone(Zone zone) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		double dx = mc.player.getX() - zone.cx(), dz = mc.player.getZ() - zone.cz(), dy = mc.player.getY() - zone.cy();
		return dx * dx + dz * dz <= zone.range() * zone.range() && Math.abs(dy) <= zone.range() + Y_SLACK;
	}

	/** The local player's own live-tracked dungeon class, or null if not resolved yet — same lookup
	 *  {@link PositionalMessagesFeature#selfClass()} already uses (matches the local player's own name
	 *  against {@link DungeonState#getTeammates()}), duplicated here rather than shared since it's a small,
	 *  self-contained helper and these two features aren't otherwise coupled. */
	private static DungeonClass selfClass() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		String selfName = mc.player.getName().getString();
		for (DungeonPlayer teammate : DungeonState.getTeammates()) {
			if (teammate.name.equals(selfName)) return teammate.clazz;
		}
		return null;
	}

	@Override
	public boolean isVisible() {
		if (!isEnabled() || !DungeonState.isInBoss() || DungeonState.getFloorNumber() != 7) return false;
		for (Zone zone : ZONES) if (isZoneVisible(zone)) return true;
		return false;
	}

	@Override
	public boolean isRelevantToCurrentIsland() { return DungeonState.isInDungeon(); }

	@Override
	public boolean alwaysCentersOnAnchor() { return true; }

	@Override
	public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		return renderZones(graphics, x, y, scale, false);
	}

	@Override
	public Size renderExample(GuiGraphicsExtractor graphics, int x, int y, float scale) {
		return renderZones(graphics, x, y, scale, true);
	}

	// Per user request ("The leap counter needs an infinileap icon far left on it"): the real Hypixel
	// accessory item ("Infinileap", internal id INFINITE_SPIRIT_LEAP — confirmed live against Hypixel's own
	// /v2/resources/skyblock/items) resolved through this codebase's own real item-icon resolver, the same
	// one Personal Compactor/Quick Action Buttons already use — no texture data of our own needed.
	private static final String LEAP_ICON_ID = "INFINITE_SPIRIT_LEAP";
	private static final int ICON_SIZE = 16;

	// Per user request ("0 should always be dark red... 1/4 and 1/3 can be regular red... 2/4, 2/3 and 3/4
	// can all be yellow... 4/4 and 3/3 and 2/2 should all be [lime green]"): a count of 0 is always dark
	// red, 1 is always red, hitting the required amount is always lime green, and everything in between is
	// yellow — verified against every example the user gave (2/3, 2/4, 3/4 -> yellow; 4/4, 3/3, 2/2 ->
	// green), not just the two endpoints.
	private static int countColor(int count, int required) {
		if (count <= 0) return 0xFFAA0000;
		if (count == 1) return 0xFFFF5555;
		if (count >= required) return 0xFF55FF55;
		return 0xFFFFFF55;
	}

	// Per user report ("There is no place ever where the user is in two places requiring leaps, so make it
	// only display 1 row and only render when the user is at the coordinates"): isZoneVisible's own role/
	// exact-phase/section/position gates already make it very unlikely for two zones to both pass for the
	// same viewer at once in steady state, but DungeonState's phase and terminal-section fields update as two
	// separate, non-atomic writes — a viewer positioned right where two zones' ranges happen to be close
	// together could still see a brief multi-row flicker across exactly that kind of transition tick. Finding
	// only the FIRST matching zone (in ZONES' own fixed order) instead of drawing every match removes that
	// possibility entirely, matching the real invariant that only one zone is ever relevant to a given viewer.
	private Zone firstVisibleZone() {
		for (Zone zone : ZONES) if (isZoneVisible(zone)) return zone;
		return null;
	}

	private Size renderZones(GuiGraphicsExtractor graphics, int x, int y, float scale, boolean example) {
		var font = Minecraft.getInstance().font;
		Zone zone = example ? ZONES.get(0) : firstVisibleZone();
		if (zone == null) return new Size(0, 0);
		net.minecraft.world.item.ItemStack icon = com.cokelord.skyblocksimplified.util.SkyblockItemIcons.getIcon(LEAP_ICON_ID);
		boolean hasIcon = icon != null && !icon.isEmpty();
		int iconGap = hasIcon ? ICON_SIZE + 2 : 0;

		int required = zone.requiredToHide();
		int count = example ? Math.min(2, required) : arrivedByZone.getOrDefault(zone.key(), java.util.Set.of()).size();
		String prefix = "§e" + zone.label() + ": §f";
		String countStr = String.valueOf(count);
		String reqStr = String.valueOf(required);
		int textWidth = font.width(prefix) + font.width(countStr) + font.width("/") + font.width(reqStr);
		int width = Math.round((iconGap + textWidth) * scale);
		int height = Math.round(Math.max(font.lineHeight, hasIcon ? ICON_SIZE : 0) * scale);
		int drawX = x - width / 2;
		if (Math.abs(scale - 1f) < 0.01f) {
			drawZoneLine(graphics, font, icon, hasIcon, drawX, y, prefix, countStr, reqStr, count, required);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(scale);
			graphics.pose().translate(-x, -y);
			drawZoneLine(graphics, font, icon, hasIcon, drawX, y, prefix, countStr, reqStr, count, required);
			graphics.pose().popMatrix();
		}
		return new Size(width, height);
	}

	private void drawZoneLine(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
							   net.minecraft.world.item.ItemStack icon, boolean hasIcon, int drawX, int drawY,
							   String prefix, String countStr, String reqStr, int count, int required) {
		int textX = drawX;
		if (hasIcon) {
			graphics.item(icon, drawX, drawY - 2);
			textX += ICON_SIZE + 2;
		}
		graphics.text(font, prefix, textX, drawY, 0xFFFFFFFF);
		textX += font.width(prefix);
		graphics.text(font, countStr, textX, drawY, countColor(count, required));
		textX += font.width(countStr);
		graphics.text(font, "/", textX, drawY, customColor);
		textX += font.width("/");
		// Per user request ("The second part of the leap counter (the last number) should always be green
		// signaling that's the amount they need to reach. Lime green."): the required-count half of the
		// fraction is fixed context (the target, not a live value), unlike the count on its own left which
		// still needs countColor's red/yellow/green progress coding.
		graphics.text(font, reqStr, textX, drawY, 0xFF55FF55);
	}

	public int getCustomColor() { return customColor; }
	public void setCustomColor(int value) { customColor = value; }

	@Override
	public com.google.gson.JsonElement savePersistedData() {
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.addProperty("anchorX", position.anchorX);
		obj.addProperty("anchorY", position.anchorY);
		obj.addProperty("scale", position.scale);
		obj.addProperty("customColor", customColor);
		com.google.gson.JsonObject zones = new com.google.gson.JsonObject();
		for (Map.Entry<String, Boolean> entry : zoneEnabled.entrySet()) zones.addProperty(entry.getKey(), entry.getValue());
		obj.add("zones", zones);
		return obj;
	}

	@Override
	public void loadPersistedData(com.google.gson.JsonElement el) {
		if (!el.isJsonObject()) return;
		com.google.gson.JsonObject obj = el.getAsJsonObject();
		if (obj.has("anchorX") && obj.has("anchorY")) {
			float s = obj.has("scale") ? obj.get("scale").getAsFloat() : position.scale;
			position.set(obj.get("anchorX").getAsFloat(), obj.get("anchorY").getAsFloat(), s);
		}
		if (obj.has("customColor")) customColor = obj.get("customColor").getAsInt();
		if (obj.has("zones") && obj.get("zones").isJsonObject()) {
			com.google.gson.JsonObject zones = obj.getAsJsonObject("zones");
			for (Zone zone : ZONES) {
				if (zones.has(zone.key())) zoneEnabled.put(zone.key(), zones.get(zone.key()).getAsBoolean());
			}
		}
	}

	@Override
	public String getDescription() {
		return "Counts and displays how many times your party has used Spirit Leap this run, per class/room preset.";
	}
}
