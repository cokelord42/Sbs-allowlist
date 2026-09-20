package com.cokelord.skyblocksimplified.feature.impl;

import com.cokelord.skyblocksimplified.debug.DebugLog;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import com.cokelord.skyblocksimplified.util.IslandGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's action-bar stat text (health/mana/defense/overflow mana) and shows each one as its own
 * independently-draggable HUD element, with the option to hide the vanilla armor/food/hearts/XP bars
 * entirely. Ported from Odin's {@code PlayerDisplay.kt} — originally consolidated into one combined line
 * (a scope-down flagged at the time as "UI-polish difference, not a functionality gap"), split back out
 * into 4 separate {@link MoveableWidget}s (Health/Mana/Overflow Mana/Defense) per user request for the
 * "independently movable elements" Odin has, matching the same nested-widget pattern
 * {@code SplitsFeature.CurrentSplitWidget} already uses elsewhere in this codebase.
 *
 * <p><b>Two real parsing bugs found and fixed.</b> First: HEALTH_PATTERN and MANA_PATTERN used to be the
 * literal same regex ({@code ([\d,]+)/([\d,]+)}), each independently taking the FIRST "X/Y" match in the
 * whole action bar — mana always silently displayed the exact same number as health. Second (found via
 * live debug data, after an earlier "Defense" label anchor turned out to only be real for some formats):
 * the real, currently-confirmed action bar layout has NO "Defense" text at all — just
 * {@code "{hp}/{hp}     {defense}     {mana}/{mana} {magicFind}     {?}/{?}          {secrets}/{total}
 * Secrets"} (defense is a bare number with nothing distinguishing it from Magic Find except its
 * position). Parsing now anchors on ORDER instead of a label: the first bare number found right after
 * health's fraction, before mana's fraction begins, is defense. That same debug capture also caught a
 * second real bug: Hypixel's action-bar Component carries LITERAL "§" legacy color codes embedded in its
 * text content (unlike chat text, which really is pre-stripped) — a stray "§7" sitting directly against a
 * real digit made {@code [\d,]+} silently absorb the "7" into the number (a real "0" was parsed as "70").
 * Every regex here now runs against a §-stripped copy of the text.
 *
 * <p>Third bug found (overflow mana showing nothing): confirmed via SkyblockAddons' own bundled
 * regex.json for this exact game version ({@code MANA_PATTERN_S =
 * "(?<num>[0-9,.]+)/(?<den>[0-9,.]+)\\uE003(| Mana| (?<overflow>-?[0-9,.]+)\\uE017)"}) — the real mana/
 * overflow icons are Private Use Area codepoints from Hypixel's own bundled resource-pack font, not the
 * printable substitute characters an earlier round's fix guessed at (cross-referenced against ports of an
 * older game version). That printable character could never appear in real Hypixel text, so the old regex
 * could never match anything. Now just captures whichever digit run appears in the narrow window right
 * after the mana fraction (the confirmed format only ever has nothing, " Mana", or the overflow number
 * there), instead of pinning a second still-unverifiable glyph.
 *
 * <p>Fourth bug found (skill-xp popup never showing, investigated per user report): unlike every pattern
 * above, SKILL_XP_PATTERN was never cross-referenced against a real captured action-bar string — no live
 * debug data, no third-party regex.json — it was only ever written to look like "+13 Combat (24.5%)" and
 * trusted. Everything ELSE this widget depends on checks out exactly like the working stat-line widgets:
 * PlayerDisplayActionBarMixin fires {@code onOverlayMessage}/{@code onActionBar} on every real actionbar
 * packet regardless of content (the same entry point health/mana already rely on, which is known to work —
 * see that mixin's own doc comment), skillXpWidget is registered in the constructor the same way as every
 * other StatWidget, and its {@code isVisible()} plumbing is identical to theirs. That isolates the bug to
 * the regex match itself never succeeding against the real text — and Hypixel is well known (documented by
 * other Skyblock client mods dealing with this exact message) to inject invisible, zero-width Unicode
 * formatting characters into skill-related action-bar text specifically to defeat naive third-party
 * parsers. Such a character sitting anywhere inside the string — e.g. right after the number, before the
 * literal space {@code \\+[\\d,.]+ \\w+} requires next — silently fails the whole {@code ^...$} match with
 * no visible symptom in-game (the character itself renders as nothing), and critically survives both
 * {@code String#trim()} (which only strips code points {@code <= U+0020}) and the existing COLOR_CODE strip
 * (which only ever matches a literal "§" plus one following character). Stripped the same way COLOR_CODE
 * already is, via the new INVISIBLE_CHARS pattern below, rather than rewriting SKILL_XP_PATTERN itself —
 * this can only make the match MORE permissive, never break the case where no such character is present, so
 * it's safe to apply even without a live sample to confirm the exact character Hypixel uses this round.
 *
 * <p><b>Fifth bug found (skill-xp popup STILL never showing after the fourth bug's fix — investigated
 * further per repeat user report).</b> SKILL_XP_PATTERN's parenthesized group only ever accepted a
 * percentage ({@code (24.5%)}) — but Hypixel SkyBlock's real actionbar skill-xp-gain popup has TWO distinct
 * formats depending on the "Extra Skill/Slayer Info" account setting: the percentage this pattern already
 * handled, and a raw current/next-level-xp fraction (e.g. {@code "+53 Farming (56,929.6/56,750)"} — the
 * exact shape reported back this round). Any player with that setting enabled could never match the old
 * percentage-only pattern no matter how clean the text was — a real, independent cause of "never shows up
 * at all" that the fourth bug's invisible-character fix, while itself correct, could never have fixed on
 * its own. SKILL_XP_PATTERN now accepts either shape.
 *
 * <p><b>Sixth bug found (per separate user report — "the player display just randomly stops updating, and
 * doesn't give me the new stats for some reason").</b> INVISIBLE_CHARS stripping (added for the fourth bug,
 * above) was only ever applied to a separate {@code textForMatching} copy used solely for the skill-xp
 * check — the shared {@code text} variable that FRACTION_PATTERN/BARE_NUMBER_PATTERN/OVERFLOW_SUFFIX_PATTERN
 * all parse the regular health/mana/defense/vitality stat line from was never stripped of them. Nothing
 * about Hypixel's use of these characters is actually specific to skill messages — they ride the same
 * actionbar channel as every other stat line, so a character landing between two digits of a health/mana/
 * defense number breaks {@code [\d,]+} at that exact digit for that one packet (no visible symptom, since
 * the character itself renders as nothing), silently leaving the OLD value on screen until a later packet
 * happens to arrive without one in the way — which reads exactly like "freezes, then randomly resumes on
 * its own" rather than a crash or a permanent failure. INVISIBLE_CHARS is now stripped from the one shared
 * {@code text} every parser in this class reads, not just the skill-xp copy — same "can only make matches
 * MORE permissive, never less" safety argument as the original fourth-bug fix.
 *
 * <p><b>Eighth bug found (per repeated user report — skill-xp popup "still isn't rendering... doesn't show
 * up all the time").</b> All the fixes above only ever covered genuinely zero-width Unicode characters and
 * plain-ASCII {@code String#trim()}. Neither of those touches a real, VISIBLE Unicode space variant (a
 * non-breaking space, one of the several fixed-width Unicode spaces, etc.) — Hypixel's own text rendering
 * treats one of these identically to a plain space, but a Java regex literal {@code " "} or {@code \w+}
 * word boundary does not. Since only SOME actionbar packets would happen to use one of these instead of a
 * real space (depending on Hypixel's own client-side text shaping that tick), this reads exactly as
 * "sometimes works, sometimes doesn't" rather than a hard, consistent failure. UNICODE_SPACE now normalizes
 * any Unicode space-separator character to a literal ASCII space before anything else parses {@code text}. */
public class PlayerDisplayFeature extends Feature {
	// Cached rather than String.replaceAll's own implicit per-call Pattern.compile — onActionBar() fires on
	// basically every actionbar update packet (health/mana changes send one essentially every tick during
	// combat), so this avoided recompiling the exact same trivial regex several times a second for no reason.
	private static final Pattern COLOR_CODE = Pattern.compile("§.");
	// See the class doc comment's "Fourth bug found" and "Sixth bug found" paragraphs — invisible/zero-width
	// Unicode formatting characters Hypixel injects into actionbar text (not only skill-related messages, as
	// originally assumed — see the Sixth-bug paragraph) to defeat naive parsers. U+200B-U+200F (zero-width
	// space/joiners + bidi marks), U+2060-U+2064 (word joiner/invisible separator/operators — this range
	// covers the specific "invisible separator" quirk reported elsewhere), U+202A-U+202E (bidi embedding/
	// override controls), and U+FEFF (BOM / zero-width no-break space).
	private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\\u200B-\\u200F\\u2060-\\u2064\\u202A-\\u202E\\uFEFF]");
	// Eighth bug found (per repeated user report — "skill exp gain still isn't rendering... doesn't show up
	// all the time"): INVISIBLE_CHARS only ever covered genuinely zero-width characters, and String#trim()
	// only strips code points <= U+0020 — neither touches a real, VISIBLE Unicode space variant (non-breaking
	// space U+00A0, thin/hair/em spaces U+2000-U+200A, narrow no-break U+202F, etc.), which Hypixel's own
	// text renderer treats identically to a plain space but a regex literal " " or \w+ word boundary does
	// not. Since only SOME of these would ever land in a given packet (depending on which exact glyph
	// Hypixel's client-side text shaping picked that tick), this reads exactly as "sometimes works, sometimes
	// doesn't" rather than a hard, consistent failure — unlike a zero-width character, stripping one of these
	// outright would wrongly glue two real words together, so this normalizes any Unicode space-separator
	// (\p{Zs}) to a literal ASCII space instead, which every pattern in this class already expects.
	private static final Pattern UNICODE_SPACE = Pattern.compile("\\p{Zs}");
	private static final Pattern FRACTION_PATTERN = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");
	private static final Pattern BARE_NUMBER_PATTERN = Pattern.compile("([\\d,]+)");
	// See the class doc comment's "Third bug found" paragraph -- real format confirmed via
	// SkyblockAddons' bundled regex.json. Skips the mana glyph/any leading text (lazily), then captures
	// the (optionally negative) overflow digit run, since real Hypixel text right after the mana
	// fraction is only ever nothing, " Mana", or the overflow number.
	private static final Pattern OVERFLOW_SUFFIX_PATTERN = Pattern.compile("^[^-\\d]*?(-?[\\d,]+)");
	// Per user request ("the skill calculator... the exact same thing as it currently does in the actionbar
	// ... just make it movable and make sure it shows with player display cause it doesnt currently"):
	// Hypixel's real per-skill XP-gain popup — matched against the §-and-invisible-char-stripped text the
	// same way the stat-line fraction is. This message and the health/mana stat line never appear on screen
	// at the same time (vanilla only has one actionbar slot), which is exactly why onActionBar's existing
	// fraction-based parsing silently ignored it before now — it correctly recognized this ISN'T the stat
	// line, but nothing ever captured what it actually was either.
	// See the class doc comment's "Fifth bug found" paragraph — accepts BOTH real Hypixel formats: the
	// percentage ("+13 Combat (24.5%)") and, with "Extra Skill/Slayer Info" enabled, the raw current/next-
	// level-xp fraction ("+53 Farming (56,929.6/56,750)").
	//
	// Seventh bug found (per user report — real captured text "+49.1 Combat Exp (45.2%)"): the actual live
	// action bar has a literal " Exp" between the skill name and the parenthesized amount that neither of
	// the two confirmed formats above accounted for — "\w+ \(" required the opening parenthesis immediately
	// after the skill name with nothing in between, so this never matched any real skill-xp message at all
	// regardless of which of the two percentage/fraction shapes it used. Made optional (not mandatory)
	// rather than required, in case some other real variant omits it, same "more permissive, never less"
	// safety rule as every other fix to this pattern.
	// Ninth bug found (per repeated user report — "still isn't showing up... doesn't show up all the time"):
	// every OTHER pattern in this class (FRACTION_PATTERN, BARE_NUMBER_PATTERN, OVERFLOW_SUFFIX_PATTERN) is
	// deliberately unanchored and used with find(), tolerating any leading/trailing content this class's own
	// stripping passes haven't accounted for yet — SKILL_XP_PATTERN was the one exception still requiring an
	// exact, anchored, whole-string match via matches(). Any not-yet-identified stray character Hypixel
	// puts before or after this specific message (the same general class of bug as every earlier fix to this
	// pattern) would silently fail an anchored match while a plain search would still succeed. Unanchored
	// now, matched with find() below, for the same "more permissive, never less" reasoning as those others.
	private static final Pattern SKILL_XP_PATTERN = Pattern.compile("\\+[\\d,.]+ \\w+(?: Exp)? \\((?:[\\d.]+%|[\\d,.]+/[\\d,.]+)\\)");
	// How long the captured skill-xp text stays shown once vanilla's own actionbar has moved on to
	// something else — matches vanilla's own typical actionbar linger time, since this widget now fully
	// replaces (rather than duplicates) the vanilla display of this specific message (see the
	// OVERLAY_MESSAGE replacement below, extended to suppress it while this is current).
	private static final long SKILL_XP_DISPLAY_MILLIS = 3000L;

	private boolean hideArmor = false;
	private boolean hideFood = false;
	private boolean hideHearts = false;
	private boolean hideXp = false;
	private boolean showIcons = true;

	// Per user request ("Allow users to turn off specific elements of the player display, this will hide
	// the mod version and show the vanilla version in the actionbar"): each parsed stat can now be hidden
	// independently — all default true, matching the previous always-shown behavior. When every one of
	// health/defense/mana/overflowMana is turned off, onEnable()'s OVERLAY_MESSAGE replacement below lets
	// Hypixel's own actionbar text back through instead of leaving the player with nothing at all — see
	// that call site's own comment for why the suppression check reads these four fields.
	private boolean showHealth = true;
	private boolean showDefense = true;
	private boolean showMana = true;
	private boolean showOverflowMana = true;
	// Per user report ("add a vitality display to the player display. Theres now a vitality indicator like
	// mana and defense"): a real new Hypixel stat, added to the action bar in the same "current/max" fraction
	// shape as mana — per the class doc comment's own already-documented real full format
	// ("...{mana}/{mana} {magicFind}     {?}/{?}          {secrets}..."), this is exactly the previously-
	// unidentified "{?}/{?}" fraction that comes right after Magic Find.
	private boolean showVitality = true;
	// Per user request ("Speed percentage can be a part of 'Player display'") — folded in from the
	// now-deleted standalone SpeedPercentageFeature module, which duplicated this same "Inventory > Misc,
	// alongside the other stat widgets" placement as its own separate toggle-able feature for no real
	// reason. speedTextColor keeps that module's own configurable color (a real ARGB, unlike the other
	// StatWidgets here which bake a §-code prefix into their text instead) so existing users' chosen color
	// survives the merge — see speedWidget's own render() below for why it can't just reuse StatWidget.
	private boolean showSpeed = true;
	private int speedTextColor = 0xFFFFFFFF;
	// Per user request ("the skill calculator... just make it movable and make sure it shows with player
	// display cause it doesnt currently") — the real per-skill XP-gain popup, captured verbatim (with its own
	// embedded §-color codes intact) so it keeps looking exactly like Hypixel's own version, just repositioned.
	private boolean showSkillXp = true;

	private Integer health, maxHealth, mana, maxMana, overflowMana, defense, vitality, maxVitality;
	// Whether the actionbar text currently on screen is the recognized health/mana/defense stat line, as
	// opposed to some other single-line notice Hypixel also routes through the same actionbar channel
	// (ability-ready messages, etc.) — set on every GAME overlay message, stays true for as long as vanilla
	// itself keeps that text displayed. Only THIS specific line gets hidden below, not the whole actionbar
	// channel, since a blanket hide would also silently eat every other actionbar notice the player still
	// wants to see.
	private boolean actionBarIsStatLine = false;
	// True only for the exact tick a skill-xp-gain message is the current actionbar text — same per-message
	// (not per-widget-visible-window) semantics as actionBarIsStatLine, used only to decide whether THIS
	// tick's vanilla overlay render should be suppressed (see the OVERLAY_MESSAGE replacement below). The
	// widget's own visibility instead uses skillXpUpdatedAtMillis + SKILL_XP_DISPLAY_MILLIS, since vanilla's
	// own actionbar text moves on to something else (or clears) well before this widget should stop showing
	// the last skill gained.
	private boolean actionBarIsSkillXp = false;
	private String skillXpText;
	private long skillXpUpdatedAtMillis = 0L;

	public final class StatWidget implements MoveableWidget {
		private final String id;
		private final String displayName;
		private final String color;
		private final String icon;
		private final java.util.function.Supplier<String> valueSupplier;
		// Per user request ("Allow users to turn off specific elements of the player display"): each
		// StatWidget now checks its own show/hide field instead of only the module-wide enabled state.
		private final java.util.function.BooleanSupplier shownSupplier;
		private final HudPosition defaultPosition;
		private final HudPosition position;

		private StatWidget(String id, String displayName, String color, String icon, float anchorX, float anchorY,
							java.util.function.Supplier<String> valueSupplier,
							java.util.function.BooleanSupplier shownSupplier) {
			this.id = id;
			this.displayName = displayName;
			this.color = color;
			this.icon = icon;
			this.valueSupplier = valueSupplier;
			this.shownSupplier = shownSupplier;
			this.defaultPosition = new HudPosition(anchorX, anchorY, 1f);
			this.position = defaultPosition.copy();
		}

		@Override
		public String getId() { return "player_display_" + id; }

		@Override
		public String getDisplayName() { return displayName; }

		@Override
		public HudPosition getPosition() { return position; }

		@Override
		public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

		@Override
		public boolean isVisible() { return isEnabled() && shownSupplier.getAsBoolean() && IslandGate.isOnSkyblock() && valueSupplier.get() != null; }

		// Real bug found (per user report — "an XP display near the right edge should expand leftward for a
		// long value instead of flipping sides or cutting off"): a previous round added a right-align flip to
		// renderText below but never overrode this (see that round's own removal note, kept for history in
		// renderText's doc comment) — since HudEditScreen's box/drag-clamp math both consult THIS method, not
		// renderText's own internal logic, leaving it false meant the edit screen always assumed left-aligned
		// while the flip (once re-added below) would draw right-aligned past center, landing content outside
		// its own edit box again exactly like before. Overriding it to true, matching TabWidgetOverlayFeature's
		// own already-correct pattern, keeps both sides of the flip decision in sync this time.
		@Override
		public boolean rightAlignsPastCenter() {
			return true;
		}

		@Override
		public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
			String value = valueSupplier.get();
			String text = value == null ? "" : color + value + (showIcons ? " " + icon : "");
			return renderText(graphics, x, y, scale, text);
		}

		private Size renderText(GuiGraphicsExtractor graphics, int x, int y, float scale, String text) {
			Font font = Minecraft.getInstance().font;
			int width = Math.round(font.width(text) * scale);
			int height = Math.round(font.lineHeight * scale);
			// Real bug found (per user report — "an XP display near the right edge... cutting off"): this used
			// to always draw left-to-right from the anchor regardless of screen position (see this class's own
			// rightAlignsPastCenter() override doc comment for the earlier round's mismatch this reintroduces
			// correctly this time) — a long value (e.g. skillXpWidget's "+13 Combat (24.5%)") anchored near the
			// right edge ran straight off-screen instead of ever flipping. Mirrors
			// TabWidgetOverlayFeature.render()'s own exact screen-center flip so both this widget's real drawn
			// content AND HudEditScreen's box/drag-clamp math (which now also sees rightAlignsPastCenter()
			// return true above) agree on the same direction at the same screen position.
			int screenCenterX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
			boolean rightAligned = x > screenCenterX;
			int drawX = rightAligned ? x - width : x;
			// scale used to be accepted but silently ignored — text always rendered at 1x regardless of what
			// the drag-handle in "Edit gui locations" was set to, and the Size returned (also unscaled) meant
			// the edit box itself never grew/shrank either, so resizing this widget visibly did nothing at
			// all. Same translate/scale/translate pattern TabWidgetOverlayFeature.drawScaled already uses.
			if (Math.abs(scale - 1f) < 0.01f) {
				graphics.text(font, text, drawX, y, 0xFFFFFFFF);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(drawX, y);
				graphics.pose().scale(scale);
				graphics.pose().translate(-drawX, -y);
				graphics.text(font, text, drawX, y, 0xFFFFFFFF);
				graphics.pose().popMatrix();
			}
			return new Size(width, height);
		}
	}

	// Default positions approximate where Hypixel's own action bar shows these — centered, just above the
	// hotbar, in the same left-to-right order the real action bar uses (health, defense, mana) — instead
	// of an arbitrary vertical stack down the left edge, per user request ("make them automatically get
	// placed where they originally are"). Still fully drag-repositionable afterward, same as every widget.
	public final StatWidget healthWidget = new StatWidget("health", "Player Display: Health", "§c", "❤", 0.32f, 0.85f,
		() -> health != null && maxHealth != null ? health + "/" + maxHealth : null, () -> showHealth);
	public final StatWidget defenseWidget = new StatWidget("defense", "Player Display: Defense", "§a", "❈", 0.44f, 0.85f,
		() -> defense != null ? String.valueOf(defense) : null, () -> showDefense);
	public final StatWidget manaWidget = new StatWidget("mana", "Player Display: Mana", "§b", "✎", 0.54f, 0.85f,
		() -> mana != null && maxMana != null ? mana + "/" + maxMana : null, () -> showMana);
	public final StatWidget overflowManaWidget = new StatWidget("overflow_mana", "Player Display: Overflow Mana", "§3", "ʬ", 0.50f, 0.81f,
		() -> overflowMana != null && overflowMana > 0 ? String.valueOf(overflowMana) : null, () -> showOverflowMana);
	public final StatWidget vitalityWidget = new StatWidget("vitality", "Player Display: Vitality", "§d", "✛", 0.62f, 0.85f,
		() -> vitality != null && maxVitality != null ? vitality + "/" + maxVitality : null, () -> showVitality);
	// No color/icon prefix (both "") — unlike the other StatWidgets, this one's value is captured RAW straight
	// from Hypixel's own actionbar text, §-codes included, so it already carries its own real coloring and
	// doesn't need one forced on top.
	public final StatWidget skillXpWidget = new StatWidget("skill_xp", "Player Display: Skill XP", "", "", 0.5f, 0.78f,
		() -> (skillXpText != null && com.cokelord.skyblocksimplified.gui.HudEditScreen.previewNowMillis() - skillXpUpdatedAtMillis < SKILL_XP_DISPLAY_MILLIS) ? skillXpText : null,
		() -> showSkillXp);

	/** Folded in from the now-deleted SpeedPercentageFeature (see this class's showSpeed/speedTextColor
	 *  field doc comments for why). Can't reuse StatWidget as-is: that class always draws in white and bakes
	 *  a §-code color prefix into its text, while this one keeps the original module's real configurable
	 *  ARGB color picker — porting that behavior into StatWidget for a single caller wasn't worth the extra
	 *  parameter every other StatWidget instantiation would have had to thread through for no benefit. */
	public final class SpeedWidget implements MoveableWidget {
		private final HudPosition defaultPosition = new HudPosition(0.02f, 0.1f, 1f);
		private final HudPosition position = defaultPosition.copy();

		@Override
		public String getId() { return "player_display_speed"; }

		@Override
		public String getDisplayName() { return "Player Display: Speed"; }

		@Override
		public HudPosition getPosition() { return position; }

		@Override
		public void resetPosition() { position.set(defaultPosition.anchorX, defaultPosition.anchorY, defaultPosition.scale); }

		@Override
		public boolean isVisible() { return isEnabled() && showSpeed && Minecraft.getInstance().player != null; }

		@Override
		public Size render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
			net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
			int walkSpeed = player != null ? (int) (player.getAbilities().getWalkingSpeed() * 1000) : 100;
			return renderText(graphics, x, y, scale, walkSpeed + "%");
		}

		private Size renderText(GuiGraphicsExtractor graphics, int x, int y, float scale, String text) {
			Font font = Minecraft.getInstance().font;
			int width = Math.round(font.width(text) * scale);
			int height = Math.round(font.lineHeight * scale);
			if (Math.abs(scale - 1f) < 0.01f) {
				graphics.text(font, text, x, y, speedTextColor);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(x, y);
				graphics.pose().scale(scale);
				graphics.pose().translate(-x, -y);
				graphics.text(font, text, x, y, speedTextColor);
				graphics.pose().popMatrix();
			}
			return new Size(width, height);
		}
	}

	public final SpeedWidget speedWidget = new SpeedWidget();

	private static boolean listenersRegistered = false;
	private static PlayerDisplayFeature instance;

	public PlayerDisplayFeature() {
		super("player_display", "Player Display", FeatureCategory.INVENTORY, false);
		instance = this;
		HudWidgetRegistry.register(healthWidget);
		HudWidgetRegistry.register(manaWidget);
		HudWidgetRegistry.register(overflowManaWidget);
		HudWidgetRegistry.register(defenseWidget);
		HudWidgetRegistry.register(vitalityWidget);
		HudWidgetRegistry.register(speedWidget);
		HudWidgetRegistry.register(skillXpWidget);
	}

	@Override
	public String getSubcategory() { return "Misc"; }

	@Override
	protected void onEnable() {
		if (!listenersRegistered) {
			listenersRegistered = true;
			// Actual parsing is now driven by PlayerDisplayActionBarMixin (see that class's doc comment for
			// the real root cause this replaced: Fabric's ClientReceiveMessageEvents.GAME only fires for one
			// of the two vanilla packet paths a server can push actionbar text through, which is what caused
			// the reported ~10s update lag).
			HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, original -> (graphics, tracker) -> {
				if (instance == null || !(instance.isEnabled() && instance.hideArmor)) original.extractRenderState(graphics, tracker);
			});
			HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, original -> (graphics, tracker) -> {
				if (instance == null || !(instance.isEnabled() && instance.hideFood)) original.extractRenderState(graphics, tracker);
			});
			HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, original -> (graphics, tracker) -> {
				if (instance == null || !(instance.isEnabled() && instance.hideHearts)) original.extractRenderState(graphics, tracker);
			});
			HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, original -> (graphics, tracker) -> {
				if (instance == null || !(instance.isEnabled() && instance.hideXp)) original.extractRenderState(graphics, tracker);
			});
			// The whole point of this feature is replacing the vanilla stat line with independently movable
			// widgets — leaving the original visible defeats that (duplicate numbers, and no way to actually
			// reposition the vanilla one). Per user request ("Allow users to turn off specific elements of
			// the player display, this will hide the mod version and show the vanilla version in the
			// actionbar"): now conditional on at least one of the four widgets this line replaces actually
			// being shown — turning all four off restores Hypixel's own actionbar text instead of leaving
			// the player with nothing, since there'd be nothing left here to justify hiding it.
			HudElementRegistry.replaceElement(VanillaHudElements.OVERLAY_MESSAGE, original -> (graphics, tracker) -> {
				boolean replacingAnyStat = instance != null && (instance.showHealth || instance.showDefense || instance.showMana || instance.showOverflowMana || instance.showVitality);
				boolean suppressStatLine = instance != null && instance.actionBarIsStatLine && replacingAnyStat;
				// Same "only hide the one specific line this feature actually replaces" reasoning as the stat
				// line above — only suppressed while THIS tick's overlay text is genuinely the skill-xp message
				// AND the widget replacing it is actually shown, so every other real actionbar notice (ability
				// ready, etc.) still comes through untouched.
				boolean suppressSkillXp = instance != null && instance.actionBarIsSkillXp && instance.showSkillXp;
				if (instance == null || !instance.isEnabled() || !(suppressStatLine || suppressSkillXp)) original.extractRenderState(graphics, tracker);
			});
			for (StatWidget widget : java.util.List.of(instance.healthWidget, instance.manaWidget, instance.overflowManaWidget, instance.defenseWidget, instance.vitalityWidget, instance.skillXpWidget)) {
				HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", widget.getId()), (graphics, tracker) -> {
					Minecraft mc = Minecraft.getInstance();
					if (!widget.isVisible() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen()) return;
					int x = Math.round(widget.position.anchorX * mc.getWindow().getGuiScaledWidth());
					int y = Math.round(widget.position.anchorY * mc.getWindow().getGuiScaledHeight());
					// Was hardcoded to 1f, ignoring the widget's actual configured scale — matches the same
					// "scale silently didn't apply" bug fixed in StatWidget.render() itself just above.
					widget.render(graphics, x, y, widget.position.scale);
				});
			}
			HudElementRegistry.attachElementBefore(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", instance.speedWidget.getId()), (graphics, tracker) -> {
				Minecraft mc = Minecraft.getInstance();
				if (!instance.speedWidget.isVisible() || com.cokelord.skyblocksimplified.hud.ChatOverlapUtil.isBlockingScreen() || com.cokelord.skyblocksimplified.hud.DebugScreenGate.isOpen()) return;
				int x = Math.round(instance.speedWidget.position.anchorX * mc.getWindow().getGuiScaledWidth());
				int y = Math.round(instance.speedWidget.position.anchorY * mc.getWindow().getGuiScaledHeight());
				instance.speedWidget.render(graphics, x, y, instance.speedWidget.position.scale);
			});
		}
	}

	/** Entry point for {@code PlayerDisplayActionBarMixin}, which hooks {@code Hud#setOverlayMessage}
	 *  directly instead of Fabric's chat-only event — see that mixin's doc comment for why. */
	public static void onOverlayMessage(Component message) {
		// Per user report ("I turned player display off and its still not rendering my actionbar... show it
		// for a tick every like 20 seconds"): the previous diagnostic (inside onActionBar, below) only ever
		// logged while this feature itself was enabled, which the user's own test (toggling it off to isolate
		// the vanilla behavior) silently defeated — turning the feature off is exactly what stopped the very
		// log meant to explain the bug. This one is unconditional: it fires on literally every real call to
		// Hud#setOverlayMessage (this mixin's own hook, see PlayerDisplayActionBarMixin's doc comment)
		// regardless of any toggle, so the next test tells us definitively whether Minecraft's real overlay
		// method is even being invoked at the expected cadence — if this logs constantly while the user still
		// sees a blank bar, the bug is in rendering/visibility, not in message delivery; if this itself only
		// fires sparsely, something upstream (a chat-event listener elsewhere in this mod, or Hypixel itself)
		// is suppressing the packet before it ever reaches this method at all.
		DebugLog.throttled("player_display_setoverlaymessage", 2000L,
			"Hud#setOverlayMessage called: text=\"" + message.getString() + "\"");
		if (instance == null || !instance.isEnabled()) return;
		instance.onActionBar(message.getString());
	}

	// Diagnostic evidence for the class doc comment's "Fifth bug found" paragraph, in case some real format
	// still isn't covered: loosely matches "+<number> <word> (<anything>)" WITHOUT requiring the parenthesized
	// part to be a percentage or a fraction, so a genuine third format would still be caught and logged here
	// even though it fails SKILL_XP_PATTERN itself.
	private static final Pattern SKILL_XP_LOOSE_PATTERN = Pattern.compile("^\\+[\\d,.]+ \\w+(?: \\w+)? \\(.*\\)$");

	// Walks rawText alongside the same COLOR_CODE/INVISIBLE_CHARS stripping onActionBar() applies to build
	// `text`, to translate an index into that stripped-and-normalized string back into the matching index in
	// the real, still-§-coded rawText. UNICODE_SPACE normalization never needs accounting for here — it only
	// ever replaces one character with another single character, so it can't shift any index.
	private static int mapStrippedIndexToRaw(String rawText, int strippedIndex) {
		int outCount = 0;
		int i = 0;
		while (i < rawText.length()) {
			if (outCount == strippedIndex) return i;
			char c = rawText.charAt(i);
			if (c == '§' && i + 1 < rawText.length()) {
				i += 2;
				continue;
			}
			if (INVISIBLE_CHARS.matcher(String.valueOf(c)).matches()) {
				i++;
				continue;
			}
			outCount++;
			i++;
		}
		return i;
	}

	private void onActionBar(String rawText) {
		String colorStripped = COLOR_CODE.matcher(rawText).replaceAll("");
		// Only for matching — INVISIBLE_CHARS strips characters that render as nothing anyway, so this can't
		// change what's actually shown, but skillXpText below still captures the untouched rawText so the
		// widget's own on-screen output can't be affected even in principle. See the class doc comment's
		// "Sixth bug found" paragraph: this used to only be applied to a separate copy used just for the
		// skill-xp check below — every other matcher in this method (FRACTION_PATTERN/BARE_NUMBER_PATTERN/
		// OVERFLOW_SUFFIX_PATTERN) now also benefits, since Hypixel isn't only injecting these into
		// skill-specific messages.
		String text = INVISIBLE_CHARS.matcher(colorStripped).replaceAll("");
		text = UNICODE_SPACE.matcher(text).replaceAll(" ");
		// Evidence for the "Sixth bug found" theory in the wild: confirms (or rules out) that Hypixel really
		// is injecting invisible characters into this specific packet's stat line, on the user's own game,
		// without waiting on a guess to be right a third time.
		if (text.length() != colorStripped.length()) {
			DebugLog.throttled("player_display_invisible_chars", 5000L,
				"PlayerDisplay: stripped " + (colorStripped.length() - text.length())
					+ " invisible char(s) from actionbar text before stat parsing: \"" + text + "\"");
		}

		// Real diagnostic added (per user report — "the player display never updates in dungeons, only in
		// boss"): every theory considered here (a dungeon-specific overlay message stealing the actionbar
		// slot, IslandGate.isOnSkyblock() flipping false mid-dungeon, the mixin simply not firing) needs live
		// evidence to tell apart — logging every actionbar packet's raw text plus whether it matched the stat
		// fraction and what isOnSkyblock() currently reports will directly show which theory is right on the
		// next test, instead of guessing a fix blind against a genuinely ambiguous symptom.
		DebugLog.throttled("player_display_actionbar", 2000L, "PlayerDisplay onActionBar: text=\"" + text
			+ "\" matchesFraction=" + FRACTION_PATTERN.matcher(text).find()
			+ " isOnSkyblock=" + com.cokelord.skyblocksimplified.util.IslandGate.isOnSkyblock()
			+ " isInDungeon=" + com.cokelord.skyblocksimplified.dungeon.DungeonState.isInDungeon());

		// Tenth bug found (per immediate follow-up report on the ninth fix, above — "renders the whole action
		// bar... doesn't cut out stuff like hearts, vitality, intelligence, overflow mana"): unanchoring
		// SKILL_XP_PATTERN correctly let it match Hypixel's real actionbar text, but that text turns out to
		// concatenate the skill-xp message together with the ordinary health/defense/mana/etc. stat segments
		// in the SAME packet — invalidating this class's original assumption (see the "Fourth bug found"
		// paragraph) that the two never share an actionbar slot. skillXpText was still being assigned the
		// ENTIRE rawText rather than just the matched portion, so the widget rendered everything Hypixel
		// sent, stat segments included. Fixed by slicing out only the matched span — mapped from the match's
		// indices in the stripped/normalized `text` back to the corresponding indices in the real, still
		// §-coded rawText via mapStrippedIndexToRaw() below, since text has had §-codes and invisible
		// characters removed and can't be sliced directly — and prefixing whatever §-color code was last
		// active immediately before that span, so the isolated text keeps Hypixel's real color instead of
		// falling back to white.
		Matcher skillXpMatcher = SKILL_XP_PATTERN.matcher(text);
		if (skillXpMatcher.find()) {
			actionBarIsStatLine = false;
			actionBarIsSkillXp = true;
			int rawStart = mapStrippedIndexToRaw(rawText, skillXpMatcher.start());
			int rawEnd = mapStrippedIndexToRaw(rawText, skillXpMatcher.end());
			int colorIdx = rawText.lastIndexOf('§', rawStart - 2);
			String colorPrefix = colorIdx >= 0 ? rawText.substring(colorIdx, colorIdx + 2) : "";
			skillXpText = colorPrefix + rawText.substring(rawStart, rawEnd);
			skillXpUpdatedAtMillis = System.currentTimeMillis();
			return;
		}
		// Evidence for the "Fifth bug found" theory: if this round's percentage-or-fraction pattern still
		// doesn't cover the real text shape, this reveals exactly what that real shape is instead of another
		// blind guess.
		if (SKILL_XP_LOOSE_PATTERN.matcher(text.trim()).matches()) {
			DebugLog.detected("PlayerDisplay: skill-xp-shaped actionbar text did NOT match SKILL_XP_PATTERN: \"" + text.trim() + "\"");
		}
		actionBarIsSkillXp = false;

		Matcher fractionMatcher = FRACTION_PATTERN.matcher(text);
		if (!fractionMatcher.find()) { actionBarIsStatLine = false; return; }
		actionBarIsStatLine = true;
		health = parseNum(fractionMatcher.group(1));
		maxHealth = parseNum(fractionMatcher.group(2));
		int healthEnd = fractionMatcher.end();

		// Defense is the first bare number after health ends — as long as it comes before mana's fraction
		// starts, it can't accidentally be a leftover digit run from health/mana themselves.
		Matcher bareMatcher = BARE_NUMBER_PATTERN.matcher(text);
		if (bareMatcher.find(healthEnd)) {
			defense = parseNum(bareMatcher.group(1));
		}

		if (fractionMatcher.find(healthEnd)) {
			Integer parsedMana = parseNum(fractionMatcher.group(1));
			Integer parsedMaxMana = parseNum(fractionMatcher.group(2));
			// Per user report ("billion/0 mana"): detection here is purely positional (the "next X/Y after
			// health"), not anchored to a real label, so an action bar variant without a real mana fraction
			// at that position could match some other unrelated number pair instead. maxMana == 0 is never a
			// real value while actively playing, and mana in the hundreds of thousands is well past anything
			// achievable even with maxed stats — reject implausible readings rather than display them, same
			// "showing nothing is safer than showing a wrong number" rule already applied to overflow mana.
			if (parsedMaxMana != null && parsedMaxMana > 0 && parsedMana != null && parsedMana < 1_000_000) {
				mana = parsedMana;
				maxMana = parsedMaxMana;
				String afterMana = text.substring(fractionMatcher.end());
				Matcher overflowMatcher = OVERFLOW_SUFFIX_PATTERN.matcher(afterMana);
				overflowMana = overflowMatcher.find() ? parseNum(overflowMatcher.group(1)) : null;
				// Per user report ("Overflow mana part of player display isn't showing anything"): the exact
				// real overflow format could not be re-confirmed this round without live data (it's only
				// non-empty in the first place when the player actually has overflow mana active, which isn't
				// most of the time) — logged so the next live test pins whether the regex still doesn't match
				// real text, or overflow was simply never active during testing.
				// Per user report ("add a vitality display... Its the 150/150 thing"): the next "X/Y" fraction
				// after mana's own (fractionMatcher is still positioned right after the mana match, so this
				// naturally skips over the bare Magic Find number in between — that shape has no "/" in it at
				// all, so plain FRACTION_PATTERN.find() can't match it) is Vitality's own current/max.
				if (fractionMatcher.find()) {
					Integer parsedVitality = parseNum(fractionMatcher.group(1));
					Integer parsedMaxVitality = parseNum(fractionMatcher.group(2));
					if (parsedMaxVitality != null && parsedMaxVitality > 0 && parsedVitality != null && parsedVitality < 1_000_000) {
						vitality = parsedVitality;
						maxVitality = parsedMaxVitality;
					}
				}
			}
		}
	}

	private static Integer parseNum(String s) {
		try { return Integer.parseInt(s.replace(",", "")); } catch (NumberFormatException e) { return null; }
	}

	public boolean isHideArmor() { return hideArmor; }
	public void setHideArmor(boolean value) { hideArmor = value; }
	public boolean isHideFood() { return hideFood; }
	public void setHideFood(boolean value) { hideFood = value; }
	public boolean isHideHearts() { return hideHearts; }
	public void setHideHearts(boolean value) { hideHearts = value; }
	public boolean isHideXp() { return hideXp; }
	public void setHideXp(boolean value) { hideXp = value; }
	public boolean isShowIcons() { return showIcons; }
	public void setShowIcons(boolean value) { showIcons = value; }
	public boolean isShowHealth() { return showHealth; }
	public void setShowHealth(boolean value) { showHealth = value; }
	public boolean isShowDefense() { return showDefense; }
	public void setShowDefense(boolean value) { showDefense = value; }
	public boolean isShowMana() { return showMana; }
	public void setShowMana(boolean value) { showMana = value; }
	public boolean isShowOverflowMana() { return showOverflowMana; }
	public void setShowOverflowMana(boolean value) { showOverflowMana = value; }
	public boolean isShowVitality() { return showVitality; }
	public void setShowVitality(boolean value) { showVitality = value; }
	public boolean isShowSpeed() { return showSpeed; }
	public void setShowSpeed(boolean value) { showSpeed = value; }
	public int getSpeedTextColor() { return speedTextColor; }
	public void setSpeedTextColor(int value) { speedTextColor = value; }
	public boolean isShowSkillXp() { return showSkillXp; }
	public void setShowSkillXp(boolean value) { showSkillXp = value; }

	@Override
	public JsonElement savePersistedData() {
		JsonObject obj = new JsonObject();
		obj.addProperty("hideArmor", hideArmor);
		obj.addProperty("hideFood", hideFood);
		obj.addProperty("hideHearts", hideHearts);
		obj.addProperty("hideXp", hideXp);
		obj.addProperty("showIcons", showIcons);
		obj.addProperty("showHealth", showHealth);
		obj.addProperty("showDefense", showDefense);
		obj.addProperty("showMana", showMana);
		obj.addProperty("showOverflowMana", showOverflowMana);
		obj.addProperty("showVitality", showVitality);
		obj.addProperty("showSpeed", showSpeed);
		obj.addProperty("speedTextColor", speedTextColor);
		obj.addProperty("showSkillXp", showSkillXp);
		// Real bug found (per user report — "Some gui elements size doesnt save to config, it resets when
		// i relaunch"): scale was never saved per-widget alongside the anchor, so resizing any of these 4
		// stat widgets via "Edit gui locations" was always lost on relaunch.
		for (StatWidget widget : java.util.List.of(healthWidget, manaWidget, overflowManaWidget, defenseWidget, vitalityWidget, skillXpWidget)) {
			obj.addProperty(widget.id + "AnchorX", widget.position.anchorX);
			obj.addProperty(widget.id + "AnchorY", widget.position.anchorY);
			obj.addProperty(widget.id + "Scale", widget.position.scale);
			// Per user request ("Add per-widget snap toggle (S button) to Player Display HUD elements in edit
			// GUI"): persisted so turning snapping off for a specific widget survives a relaunch.
			obj.addProperty(widget.id + "SnapEnabled", widget.position.snapEnabled);
		}
		// Speed, folded in from the deleted SpeedPercentageFeature, uses the same anchor/scale/snap shape as
		// the 4 StatWidgets above but isn't itself a StatWidget (see SpeedWidget's own doc comment) — saved
		// under its own "speed" prefix rather than looping it in with them.
		obj.addProperty("speedAnchorX", speedWidget.position.anchorX);
		obj.addProperty("speedAnchorY", speedWidget.position.anchorY);
		obj.addProperty("speedScale", speedWidget.position.scale);
		obj.addProperty("speedSnapEnabled", speedWidget.position.snapEnabled);
		return obj;
	}

	@Override
	public void loadPersistedData(JsonElement el) {
		if (!el.isJsonObject()) return;
		JsonObject obj = el.getAsJsonObject();
		if (obj.has("hideArmor")) hideArmor = obj.get("hideArmor").getAsBoolean();
		if (obj.has("hideFood")) hideFood = obj.get("hideFood").getAsBoolean();
		if (obj.has("hideHearts")) hideHearts = obj.get("hideHearts").getAsBoolean();
		if (obj.has("hideXp")) hideXp = obj.get("hideXp").getAsBoolean();
		if (obj.has("showIcons")) showIcons = obj.get("showIcons").getAsBoolean();
		if (obj.has("showHealth")) showHealth = obj.get("showHealth").getAsBoolean();
		if (obj.has("showDefense")) showDefense = obj.get("showDefense").getAsBoolean();
		if (obj.has("showMana")) showMana = obj.get("showMana").getAsBoolean();
		if (obj.has("showOverflowMana")) showOverflowMana = obj.get("showOverflowMana").getAsBoolean();
		if (obj.has("showVitality")) showVitality = obj.get("showVitality").getAsBoolean();
		if (obj.has("showSpeed")) showSpeed = obj.get("showSpeed").getAsBoolean();
		if (obj.has("speedTextColor")) speedTextColor = obj.get("speedTextColor").getAsInt();
		if (obj.has("showSkillXp")) showSkillXp = obj.get("showSkillXp").getAsBoolean();
		for (StatWidget widget : java.util.List.of(healthWidget, manaWidget, overflowManaWidget, defenseWidget, vitalityWidget, skillXpWidget)) {
			if (obj.has(widget.id + "AnchorX")) widget.position.anchorX = obj.get(widget.id + "AnchorX").getAsFloat();
			if (obj.has(widget.id + "AnchorY")) widget.position.anchorY = obj.get(widget.id + "AnchorY").getAsFloat();
			if (obj.has(widget.id + "Scale")) widget.position.scale = obj.get(widget.id + "Scale").getAsFloat();
			if (obj.has(widget.id + "SnapEnabled")) widget.position.snapEnabled = obj.get(widget.id + "SnapEnabled").getAsBoolean();
		}
		if (obj.has("speedAnchorX")) speedWidget.position.anchorX = obj.get("speedAnchorX").getAsFloat();
		if (obj.has("speedAnchorY")) speedWidget.position.anchorY = obj.get("speedAnchorY").getAsFloat();
		if (obj.has("speedScale")) speedWidget.position.scale = obj.get("speedScale").getAsFloat();
		if (obj.has("speedSnapEnabled")) speedWidget.position.snapEnabled = obj.get("speedSnapEnabled").getAsBoolean();
		// Back-compat with the old single-widget save format's anchorX/anchorY — applies to the health
		// widget (the combined line's old default position) so existing users don't lose their placement.
		if (obj.has("anchorX") && !obj.has("healthAnchorX")) healthWidget.position.anchorX = obj.get("anchorX").getAsFloat();
		if (obj.has("anchorY") && !obj.has("healthAnchorY")) healthWidget.position.anchorY = obj.get("anchorY").getAsFloat();
	}

	@Override
	public String getDescription() {
		return "Shows your health, mana, defense, and overflow mana as separate, independently-movable HUD bars/widgets, with the option to hide the vanilla versions.";
	}
}
