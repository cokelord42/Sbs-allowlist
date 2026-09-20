package com.cokelord.skyblocksimplified.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A HUD element the player can drag/resize in the "Edit gui locations" screen. */
public interface MoveableWidget {
	String getId();

	String getDisplayName();

	HudPosition getPosition();

	void resetPosition();

	/** Whether this widget should render at all right now (feature enabled, not hidden, etc.),
	 *  independent of edit mode — the edit screen still shows disabled widgets so they can be
	 *  repositioned in advance, but normal gameplay rendering should respect this. */
	boolean isVisible();

	/** Whether this widget makes sense to preview/edit given the player's CURRENT island — default true
	 *  (no restriction). Per user request: the edit screen shouldn't clutter itself with widgets tied to a
	 *  specific island (e.g. a Garden-only Pest Timer) while standing somewhere that widget could never
	 *  actually appear. Deliberately separate from {@link #isVisible()} (feature-enabled state, which the
	 *  edit screen intentionally does NOT gate on, so a disabled widget can still be positioned in advance)
	 *  — location context doesn't get that same "prepare in advance" exemption, since unlike a toggle you
	 *  can't flip on demand, the player has to actually travel there to see it anyway. */
	default boolean isRelevantToCurrentIsland() { return true; }

	/** Whether this widget's own render() logic right-aligns its content (grows leftward from its anchor)
	 *  once dragged past horizontal screen-center, instead of always drawing left-to-right from the anchor.
	 *  Default false — most widgets always draw left-to-right regardless of screen position. The edit screen
	 *  uses this to decide which side of the anchor to draw the hit-box/outline on, so it must match whatever
	 *  the widget's own render() actually does. */
	default boolean rightAlignsPastCenter() { return false; }

	/** Whether this widget's content is always horizontally centered ON its anchor, regardless of screen
	 *  position or content length — per user report (Dungeon Notifications: "gui element text should always
	 *  center regardless of length, currently drifts off-center on longer text"): a plain left-anchored
	 *  widget's box only ever grows rightward as its text gets longer, so the anchor point (where the user
	 *  actually dragged it, e.g. under the crosshair like vanilla's own title HUD) stops matching the text's
	 *  visual center once it's longer than whatever it was when placed. Distinct from
	 *  {@link #rightAlignsPastCenter()} (a binary "flip sides past screen-center" rule for edge-of-screen
	 *  overflow prevention) — this centers unconditionally, on both sides, at every screen position. Default
	 *  false (left-anchored, the existing behavior every other widget already relies on). */
	default boolean alwaysCentersOnAnchor() { return false; }

	/** Whether this widget is currently rendering real, non-placeholder content (true) or is blank/showing
	 *  only a "nothing detected yet" placeholder (false). The edit screen uses this to only draw a widget's
	 *  floating name label when it's otherwise empty — a widget already showing real content doesn't need
	 *  the redundant label cluttering it. Default true (assume real content) since most widgets are only
	 *  even listed in the edit screen while relevant to the player's current context. */
	default boolean hasVisibleContent() { return true; }

	/** Renders at the given top-left pixel position/scale and returns the rendered size in unscaled
	 *  pixels, so the edit screen can draw an accurate outline/hit box around it.
	 *
	 *  <p>Per user report ("The gui display elements doesnt match the length of them. Honestly, remove this
	 *  whole fake number thing and just make them fully pause when in the edit gui panel"): {@code
	 *  HudEditScreen} used to call a separate {@code renderExample()} method here instead of this real
	 *  {@link #render}, showing a hardcoded example string (e.g. "Simon Says: 3/5") so the widget always had
	 *  something to show and to avoid every registered widget's real, potentially expensive render running
	 *  simultaneously every frame — but a fixed example's length rarely matches the real content's own
	 *  length, making the hit box/outline this screen draws around it inaccurate for positioning against.
	 *  Removed entirely: this interface no longer has a separate example-rendering method at all, and the
	 *  edit screen now calls this real method directly. The frame-drop concern renderExample() used to solve
	 *  is covered a different way now — {@code FeatureRegistry.tickAll()} already skips every feature's
	 *  onTick() outright while the edit screen is open (see its own doc comment), and any widget whose real
	 *  render() computes a duration directly off wall-clock time (rather than tick-driven state) should read
	 *  {@code HudEditScreen.previewNowMillis()} instead of {@code System.currentTimeMillis()} so that value
	 *  freezes too instead of visibly continuing to count down/up while being positioned. */
	Size render(GuiGraphicsExtractor graphics, int x, int y, float scale);

	record Size(int width, int height) {}
}
