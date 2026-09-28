package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.DungeonClass;
import com.cokelord.skyblocksimplified.dungeon.DungeonPlayer;
import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.sound.CustomSoundOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per user request: a new, separate module (explicitly NOT folded into {@link DungeonNotificationsFeature})
 * that detects an OTHER party member (never the mod user themselves) physically reaching one of three
 * fixed box regions in the F7 boss fight's terminal phase, and posts a chat alert naming them — a genuinely
 * different mechanism from {@link PositionalMessagesFeature}'s existing "At EE2!"/"At EE3!"/"At Core!"
 * presets (which only ever fire for the LOCAL player standing at their own assigned spot, as a personal
 * reminder) and from {@link LeapCounterFeature}'s "High EE2"/"EE3"/"Core" zone counters (which only ever
 * show a headcount, never a named per-player alert). This module is about telling the REST of the party that
 * a specific teammate has already reached the next section early.
 *
 * <p>Each zone is a real two-corner box (not a center+range sphere like the presets above) per user request
 * ("I want the coordinates to be boxes... this way making sure it triggers even when the user is not
 * specifically at certain points incase they use a different early enter location") — {@link AABB}'s own
 * constructor already normalizes whichever corner order is given, so the exact corner values supplied don't
 * need to be pre-sorted into min/max.
 *
 * <p>EE2 and EE3 are hard-gated to their own one relevant {@link DungeonState.TerminalSection} — per user
 * spec ("when the next section starts the early enter notification for that section will be cut off so
 * players running over it wont notify when that section is already active"), a player physically inside the
 * EE2 box while section 2 (or later) is already active is no longer "early" at all, so detection for that
 * zone simply doesn't run once its one relevant section has passed. Core is different: per user spec
 * (originally "reprihand the alert until section 3 is active", corrected the round after to "the core early
 * enter should be active from section 2 and on" once the withhold-until-S3 gate silently ate a real S2 entry),
 * a player reaching Core before section 2 starts is still detected and remembered, just not ALERTED until
 * section 2 actually starts — see {@link #onZoneEntered} and the section-2-transition drain in {@link #onTick}.
 *
 * <p>Every alert is edge-triggered (fires once on the tick a teammate transitions from outside to inside a
 * box, not every tick they linger there) and fires at most once per teammate per zone per dungeon run
 * (tracked in {@link #alreadyAlerted}, reset on a fresh {@link DungeonState#getRunId()}) — otherwise a
 * teammate standing near a box edge could spam the same alert every time they crossed it.
 */
public class EarlyEnterDetectionFeature extends Feature {
	private enum Zone {
		EE2(70, 107, 121, 55, 138, 141, DungeonState.TerminalSection.S1, null, "EE2", 0xFF55FFFF),
		EE3(17, 107, 108, -3, 134, 87, DungeonState.TerminalSection.S2, null, "EE3", 0xFFFFAA00),
		// Real bug found (per user report — "The early enter detection for core should support all classes,
		// not just mage. Some people play like mage roles where they do mage stuff with different classes
		// for optimal class xp gain, and the stuff wont work then"): originally restricted to DungeonClass.
		// MAGE specifically (per the ORIGINAL user spec, "for mage going to core"), but a real player can run
		// the same early-Core strategy on any class for their own XP-farming reasons — no class restriction
		// left, same as EE2/EE3 above. Its activeSection is S2 (not S3) per the "active from section 2 and on"
		// correction — see the class doc comment and onZoneEntered/onTick, which no longer withhold Core's
		// alert once section 2 has started.
		CORE(57, 114, 53, 50, 117, 47, DungeonState.TerminalSection.S2, null, "Core", 0xFFAA00AA);

		final AABB box;
		final DungeonState.TerminalSection activeSection;
		final DungeonClass requiredClass;
		final String label;
		final int renderColor;

		Zone(double x1, double y1, double z1, double x2, double y2, double z2,
			 DungeonState.TerminalSection activeSection, DungeonClass requiredClass, String label, int renderColor) {
			this.box = new AABB(x1, y1, z1, x2, y2, z2);
			this.activeSection = activeSection;
			this.requiredClass = requiredClass;
			this.label = label;
			this.renderColor = renderColor;
		}
	}

	// Per user request ("spam the sound... allow users to also set how many times it should be played in
	// order"): played back-to-back with a short fixed gap rather than all at once (which would just overlap
	// into noise) — see the drain loop in onTick.
	private static final long SOUND_REPEAT_INTERVAL_MILLIS = 400L;

	// Per user request ("add an option to render the boxes with an opacity slider so i can test the boxes
	// working"): a plain testing aid.
	private boolean renderBoxes = false;
	private float boxOpacityPercent = 40f;
	// Per user request ("make a toggle for rendering all boxes even if its not s1, s2, s3"): draws all three
	// regardless of the live terminal section when on; turning it off instead only draws whichever single
	// zone is actually relevant to the CURRENT live section (EE2 during S1, EE3/Core during S2 and on — same
	// mapping detection itself uses), matching what would realistically be shown during real play.
	//
	// Real bug found (per user report — "when i enable 'render boxes' they render unconditionally in other
	// worlds aswell. They should only render when its the section before the box"): this used to default to
	// true so a tester could see every box's placement at once — but combined with renderBoxesStatic's own
	// missing dungeon gate (see that method's own doc comment), this meant the boxes rendered at their fixed
	// F7 world coordinates in literally ANY world, section-relevance ignored, the moment the toggle was
	// flipped on — meaningless (and visually confusing) anywhere that isn't actually the F7 terminal room.
	// Defaulted to false now: real section-relevance is the sane default, with "show everything at once"
	// still available as an explicit opt-in for testing.
	private boolean renderAllBoxesRegardlessOfSection = false;

	private int soundRepeatCount = 3;
	private final CustomSoundOption sound = new CustomSoundOption(TerminalSoundsFeature.SOUND_IDS, TerminalSoundsFeature.SOUND_LABELS);

	// Per user request ("add a new option to color the screen when it warns, add a hex picker to select which
	// color it should color the entire screen (big overlay above everything) and add a slider for opacity. It
	// should stop coloring after 5 seconds OR when the user leaps"): a full-screen tint fired alongside every
	// real alert, cleared by whichever of the two stop conditions happens first — see the leap chat listener
	// registered in onEnable and the 5-second check in the HUD render callback.
	private boolean screenColorEnabled = false;
	private int screenColor = 0xFFFF0000;
	private float screenColorOpacityPercent = 40f;
	private long screenColorUntilMillis = 0L;
	private static final long SCREEN_COLOR_DURATION_MILLIS = 5_000L;
	// Per user request ("Add a new option in the early enter detection module that coordinates the leap menu
	// and the early enter detection, where when it detect someone early entering, going into the leap menu
	// hides the ones that are not leaping and shows only the early enter person in the center. This should
	// automatically turn itself off after 5 seconds, just like the red screen tinting"): same duration/self-
	// clearing shape as screenColorUntilMillis above, checked live from LeapMenuFeature (see
	// getActiveLeapMenuHighlightUsername) rather than pushed to it — so it "turns off" for free the instant
	// the 5 seconds pass, with no separate timer/callback needed on the Leap Menu side at all.
	private boolean coordinateWithLeapMenu = false;
	private String highlightedEarlyEnterUsername = null;
	private long highlightedEarlyEnterUntilMillis = 0L;
	// Real, confirmed Hypixel line sent to the LOCAL player the instant any real Spirit Leap teleport lands
	// (same regex DungeonsCopilotFeature's own LEAP_COMPLETED_PATTERN already confirmed) — this line is only
	// ever sent to the player who leaped, so no local-player-name check is needed.
	private static final Pattern LEAP_COMPLETED_PATTERN = Pattern.compile("^You have teleported to (\\w{1,16})!$");
	private static final Identifier SCREEN_COLOR_HUD_ID = Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "early_enter_screen_color");

	private final Map<Zone, Set<String>> insideLastTick = new EnumMap<>(Zone.class);
	private final Map<Zone, Set<String>> alreadyAlerted = new EnumMap<>(Zone.class);
	// Per Core's own "active from section 2 and on" spec — name -> class, snapshotted at the moment of early
	// entry rather than re-resolved later, since DungeonPlayer instances aren't stable across ticks (see
	// LeapCounterFeature's own established "track by plain name string" convention) but a class doesn't
	// change mid-run.
	private final Map<String, DungeonClass> coreWaitingForSection2 = new LinkedHashMap<>();

	private DungeonState.TerminalSection lastSection = DungeonState.TerminalSection.NONE;
	private int lastSeenRunId = -1;

	private int pendingSoundPlays = 0;
	private long nextSoundPlayAtMillis = 0L;

	private static boolean listenersRegistered = false;
	private static EarlyEnterDetectionFeature instance;

	/** Whether a player is inside the EE2 (section 2) early-enter box — the Levers device sits inside it, so
	 *  a device completed during S1 while someone is in there is the S2 Levers device (Dungeons Copilot).
	 *  With {@code playerName} set, checks that player (the completer named in chat); if they aren't loaded,
	 *  or with null, checks every loaded player. Works whether or not this feature is enabled. */
	public static boolean isInSection2Box(String playerName) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return false;
		if (playerName != null) {
			for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
				if (p.getName().getString().equals(playerName)) return Zone.EE2.box.contains(p.position());
			}
		}
		for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
			if (Zone.EE2.box.contains(p.position())) return true;
		}
		return false;
	}

	public EarlyEnterDetectionFeature() {
		super("early_enter_detection", "Early Enter Detection", FeatureCategory.COMBAT, false);
		instance = this;
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		resetState();
		if (!listenersRegistered) {
			listenersRegistered = true;
			World3DRenderer.addRenderCallback(EarlyEnterDetectionFeature::renderBoxesStatic);
			ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
				if (instance != null && instance.isEnabled()) instance.onChatMessage(message.getString());
				return true;
			});
			// addLast (not attachElementBefore) — per user request ("big overlay above everything"), this
			// needs to draw on top of every other HUD element, not tucked behind one specific anchor.
			//
			// Per a later user request ("make sure its below z level of the leap menu and the black
			// background, but above all else"): skipped here specifically while the Leap Menu's own replaced
			// screen is open, since LeapMenuFeature.renderReplacement now calls renderScreenColorOverlayIfActive
			// itself, sandwiched between its own background fill and its quadrant boxes — drawing it here too
			// in that case would double-render it (and, since HUD renders before the Screen either way, this
			// copy would just sit UNDER the leap menu's own copy, adding nothing but a second alpha layer).
			HudElementRegistry.addLast(SCREEN_COLOR_HUD_ID, (graphics, deltaTracker) -> {
				if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
					&& LeapMenuFeature.shouldReplaceRender(screen)) return;
				renderScreenColorOverlayIfActive(graphics);
			});
		}
	}

	/** Shared by the normal HUD hook (registered in {@link #onEnable}) and
	 *  {@link LeapMenuFeature#renderReplacement} — see that hook's own doc comment for why the Leap Menu case
	 *  calls this directly instead of relying on the HUD hook while its own screen is open. */
	static void renderScreenColorOverlayIfActive(GuiGraphicsExtractor graphics) {
		if (instance == null || !instance.isEnabled() || !instance.screenColorEnabled) return;
		if (System.currentTimeMillis() >= instance.screenColorUntilMillis) return;
		Minecraft mc = Minecraft.getInstance();
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		int alpha = Math.round(instance.screenColorOpacityPercent / 100f * 255f);
		graphics.fill(0, 0, width, height, (alpha << 24) | (instance.screenColor & 0xFFFFFF));
	}

	/** {@link LeapMenuFeature} calls this every frame it renders its quadrants — a non-null result (and only
	 *  while it stays non-null, since the 5-second expiry is checked live here rather than pushed out via a
	 *  callback) means "hide every other teammate and show just this one, centered." See this class's own
	 *  {@code coordinateWithLeapMenu} field doc comment for why turning it back off needs no extra code on
	 *  either side. */
	public static String getActiveLeapMenuHighlightUsername() {
		if (instance == null || !instance.isEnabled() || !instance.coordinateWithLeapMenu) return null;
		if (instance.highlightedEarlyEnterUsername == null) return null;
		if (System.currentTimeMillis() >= instance.highlightedEarlyEnterUntilMillis) return null;
		return instance.highlightedEarlyEnterUsername;
	}

	// See screenColorUntilMillis's own doc comment — the leap stop condition. Never cancels the real line.
	private void onChatMessage(String text) {
		if (screenColorUntilMillis > 0 && LEAP_COMPLETED_PATTERN.matcher(text).matches()) {
			screenColorUntilMillis = 0L;
		}
	}

	@Override
	protected void onDisable() { resetState(); }

	private void resetState() {
		insideLastTick.clear();
		alreadyAlerted.clear();
		coreWaitingForSection2.clear();
		lastSection = DungeonState.TerminalSection.NONE;
		pendingSoundPlays = 0;
		screenColorUntilMillis = 0L;
		highlightedEarlyEnterUsername = null;
		highlightedEarlyEnterUntilMillis = 0L;
	}

	@Override
	public void onTick(Minecraft client) {
		if (!DungeonState.isInDungeon() || !DungeonState.isInBoss() || DungeonState.getFloorNumber() != 7) {
			resetState();
			return;
		}
		int runId = DungeonState.getRunId();
		if (runId != lastSeenRunId) {
			resetState();
			lastSeenRunId = runId;
		}

		DungeonState.TerminalSection section = DungeonState.getTerminalSection();
		boolean justReachedS2OrLater = section.ordinal() >= DungeonState.TerminalSection.S2.ordinal()
			&& lastSection.ordinal() < DungeonState.TerminalSection.S2.ordinal();
		lastSection = section;

		// See coreWaitingForSection2's own doc comment — the moment section 2 actually starts, release every
		// alert that was being withheld for a player who reached Core early.
		if (justReachedS2OrLater && !coreWaitingForSection2.isEmpty()) {
			for (Map.Entry<String, DungeonClass> entry : coreWaitingForSection2.entrySet()) {
				fireAlert(Zone.CORE, entry.getKey(), entry.getValue());
			}
			coreWaitingForSection2.clear();
		}

		if (client.player != null) {
			for (Zone zone : Zone.values()) {
				// EE2/EE3 simply aren't relevant outside their one real section — see class doc comment. Core
				// has no such gate (it needs to detect early arrivals too), so it always scans.
				if (zone != Zone.CORE && section != zone.activeSection) {
					insideLastTick.put(zone, Set.of());
					continue;
				}
				Set<String> previously = insideLastTick.getOrDefault(zone, Set.of());
				Set<String> currentlyInside = new HashSet<>();
				for (DungeonPlayer teammate : DungeonState.getTeammatesNoSelf()) {
					if (zone.requiredClass != null && teammate.clazz != zone.requiredClass) continue;
					Player entity = teammate.entity;
					if (entity == null) continue;
					if (!zone.box.contains(entity.getX(), entity.getY(), entity.getZ())) continue;
					currentlyInside.add(teammate.name);
					if (!previously.contains(teammate.name)) onZoneEntered(zone, teammate, section);
				}
				insideLastTick.put(zone, currentlyInside);
			}
		}

		if (pendingSoundPlays > 0 && System.currentTimeMillis() >= nextSoundPlayAtMillis) {
			sound.play();
			pendingSoundPlays--;
			nextSoundPlayAtMillis = System.currentTimeMillis() + SOUND_REPEAT_INTERVAL_MILLIS;
		}
	}

	/** A teammate just transitioned from outside to inside {@code zone}'s box this tick. */
	private void onZoneEntered(Zone zone, DungeonPlayer teammate, DungeonState.TerminalSection section) {
		if (zone == Zone.CORE && section.ordinal() < DungeonState.TerminalSection.S2.ordinal()) {
			// See coreWaitingForSection2's own doc comment — held back, not fired yet.
			coreWaitingForSection2.put(teammate.name, teammate.clazz);
			return;
		}
		fireAlert(zone, teammate.name, teammate.clazz);
	}

	private void fireAlert(Zone zone, String username, DungeonClass clazz) {
		Set<String> alerted = alreadyAlerted.computeIfAbsent(zone, z -> new HashSet<>());
		if (!alerted.add(username)) return;
		Component alertText = buildAlertText(zone, username, clazz);
		sendAlert(alertText);
		sendTitle(alertText);
		if (screenColorEnabled) screenColorUntilMillis = System.currentTimeMillis() + SCREEN_COLOR_DURATION_MILLIS;
		if (coordinateWithLeapMenu) {
			highlightedEarlyEnterUsername = username;
			highlightedEarlyEnterUntilMillis = System.currentTimeMillis() + SCREEN_COLOR_DURATION_MILLIS;
		}
		pendingSoundPlays = Math.max(1, soundRepeatCount);
		sound.play();
		pendingSoundPlays--;
		nextSoundPlayAtMillis = System.currentTimeMillis() + SOUND_REPEAT_INTERVAL_MILLIS;
	}

	// Per user request ("Make sure to color code everything. All text should be yellow except the username
	// and class, make the class bold and both username and class the same color as the class color"): the
	// root component carries the yellow style, so every unstyled child (the parentheses, "is at X!") inherits
	// it for free — only the username/class children override color, and only the class child adds bold.
	private Component buildAlertText(Zone zone, String username, DungeonClass clazz) {
		Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
		Style classColor = Style.EMPTY.withColor(TextColor.fromRgb(clazz.color & 0xFFFFFF));
		return Component.literal("").withStyle(yellow)
			.append(Component.literal(username).withStyle(classColor))
			.append(Component.literal(" ("))
			.append(Component.literal(classDisplayName(clazz)).withStyle(classColor.withBold(true)))
			.append(Component.literal(") is at " + zone.label + "!"));
	}

	private void sendAlert(Component alertText) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.gui.hud.getChat().addClientSystemMessage(alertText);
	}

	// Per user request ("It should be a title that takes priority always, basically if a title shows during
	// or before the early enter notification should always take priority"): vanilla's own title system has no
	// real priority queue anywhere in this codebase — every feature that shows one (Kill Mobs, terminal
	// countdowns, etc.) just calls setTitle directly, and whichever call happens LAST simply replaces
	// whatever was showing. Calling it here, right as the alert fires, is what actually gives it priority in
	// practice: it immediately overwrites any other title already on screen, and — since this alert only ever
	// fires once per player per zone per run — nothing about it can be pre-empted by an earlier stale call.
	private void sendTitle(Component alertText) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.gui.hud.setTimes(5, 40, 10);
		mc.gui.hud.setTitle(alertText);
	}

	private static String classDisplayName(DungeonClass clazz) {
		if (clazz == DungeonClass.BERSERK) return "Berserker";
		String name = clazz.name();
		return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
	}

	// Real bug found (per user report — "The early enter thing has issues. It genuinely did nothing it
	// seems, and it renders no boxes"): this used to require isInDungeon()+isInBoss()+floor 7 before drawing
	// anything at all — correct for the REAL detection logic (so it can't false-positive outside a real F7
	// boss fight), but wrong for a TESTING aid whose entire point is letting the user verify box placement
	// without needing to fight all the way to a live terminals phase first. Dropped ALL of that gating at
	// the time, which overcorrected into a second real bug (per later user report — "they render
	// unconditionally in other worlds aswell"): these are fixed F7-world coordinates, meaningless (and
	// visually confusing) rendered at that same position in literally any other world/island/floor. Now
	// requires actually being in the F7 dungeon instance specifically — real coordinates in the right world
	// — but still not isInBoss(), so a tester can preview box placement anywhere in F7 (including before
	// the boss fight even starts) without needing to reach a live terminals phase first.
	private static void renderBoxesStatic() {
		if (instance == null || !instance.isEnabled() || !instance.renderBoxes) return;
		if (!DungeonState.isInDungeon() || DungeonState.getFloorNumber() != 7) return;
		try {
			instance.renderBoxesInner();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.error("Early Enter Detection box render failed, skipping this frame", e);
		}
	}

	private void renderBoxesInner() {
		int fillAlpha = Math.round(boxOpacityPercent / 100f * 255f);
		DungeonState.TerminalSection section = DungeonState.getTerminalSection();
		for (Zone zone : Zone.values()) {
			// Core stays relevant for the rest of the fight once section 2 starts (no upper bound, unlike
			// EE2/EE3 which are each only relevant during their own one section) — see the class doc comment.
			boolean zoneRelevant = zone == Zone.CORE
				? section.ordinal() >= zone.activeSection.ordinal()
				: zone.activeSection == section;
			if (!renderAllBoxesRegardlessOfSection && !zoneRelevant) continue;
			int fillArgb = (fillAlpha << 24) | (zone.renderColor & 0xFFFFFF);
			World3DRenderer.drawFilledBoxThroughWalls(zone.box, fillArgb);
			World3DRenderer.drawWireBoxThroughWalls(zone.box, zone.renderColor, 2f);
		}
	}

	public boolean isRenderBoxes() { return renderBoxes; }
	public void setRenderBoxes(boolean value) { renderBoxes = value; }
	public float getBoxOpacityPercent() { return boxOpacityPercent; }
	public void setBoxOpacityPercent(float value) { boxOpacityPercent = Math.max(0f, Math.min(100f, value)); }
	public boolean isRenderAllBoxesRegardlessOfSection() { return renderAllBoxesRegardlessOfSection; }
	public void setRenderAllBoxesRegardlessOfSection(boolean value) { renderAllBoxesRegardlessOfSection = value; }
	public int getSoundRepeatCount() { return soundRepeatCount; }
	public void setSoundRepeatCount(int value) { soundRepeatCount = Math.max(1, Math.min(10, value)); }
	public CustomSoundOption getSound() { return sound; }
	public boolean isScreenColorEnabled() { return screenColorEnabled; }
	public void setScreenColorEnabled(boolean value) { screenColorEnabled = value; }
	public int getScreenColor() { return screenColor; }
	public void setScreenColor(int value) { screenColor = value; }
	public float getScreenColorOpacityPercent() { return screenColorOpacityPercent; }
	public void setScreenColorOpacityPercent(float value) { screenColorOpacityPercent = Math.max(0f, Math.min(100f, value)); }
	public boolean isCoordinateWithLeapMenu() { return coordinateWithLeapMenu; }
	public void setCoordinateWithLeapMenu(boolean value) { coordinateWithLeapMenu = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("renderBoxes", renderBoxes);
		obj.addProperty("boxOpacityPercent", boxOpacityPercent);
		obj.addProperty("renderAllBoxesRegardlessOfSection", renderAllBoxesRegardlessOfSection);
		obj.addProperty("soundRepeatCount", soundRepeatCount);
		obj.add("sound", sound.toJson());
		obj.addProperty("screenColorEnabled", screenColorEnabled);
		obj.addProperty("screenColor", screenColor);
		obj.addProperty("screenColorOpacityPercent", screenColorOpacityPercent);
		obj.addProperty("coordinateWithLeapMenu", coordinateWithLeapMenu);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("renderBoxes")) renderBoxes = obj.get("renderBoxes").getAsBoolean();
		if (obj.has("boxOpacityPercent")) boxOpacityPercent = obj.get("boxOpacityPercent").getAsFloat();
		if (obj.has("renderAllBoxesRegardlessOfSection")) renderAllBoxesRegardlessOfSection = obj.get("renderAllBoxesRegardlessOfSection").getAsBoolean();
		if (obj.has("soundRepeatCount")) soundRepeatCount = obj.get("soundRepeatCount").getAsInt();
		if (obj.has("sound")) sound.fromJson(obj.get("sound"));
		if (obj.has("screenColorEnabled")) screenColorEnabled = obj.get("screenColorEnabled").getAsBoolean();
		if (obj.has("screenColor")) screenColor = obj.get("screenColor").getAsInt();
		if (obj.has("screenColorOpacityPercent")) screenColorOpacityPercent = obj.get("screenColorOpacityPercent").getAsFloat();
		if (obj.has("coordinateWithLeapMenu")) coordinateWithLeapMenu = obj.get("coordinateWithLeapMenu").getAsBoolean();
	}

	@Override
	public String getDescription() {
		return "Alerts you in chat (with a repeating sound) when a party member reaches the next terminal section's early-enter spot, or reaches Core early.";
	}
}
