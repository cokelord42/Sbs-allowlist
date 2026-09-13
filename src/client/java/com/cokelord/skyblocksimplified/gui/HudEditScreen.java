package com.cokelord.skyblocksimplified.gui;

import com.cokelord.skyblocksimplified.config.ConfigManager;
import com.cokelord.skyblocksimplified.feature.Feature;
import com.cokelord.skyblocksimplified.hud.HudPosition;
import com.cokelord.skyblocksimplified.hud.HudWidgetRegistry;
import com.cokelord.skyblocksimplified.hud.MoveableWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "Edit gui locations": shows every registered {@link MoveableWidget} over the real game view (not
 * our usual translucent panel — the whole point is seeing widgets in context). Drag the body to move,
 * drag the corner handle to resize, click the reset button to restore defaults. Escape exits normally.
 */
public class HudEditScreen extends Screen {
	private static final int HANDLE_SIZE = 8;
	private static final int RESET_BUTTON_SIZE = 12;
	private static final int SNAP_BUTTON_SIZE = 12;
	private static final int MIN_HIT_WIDTH = 40;
	private static final int MIN_HIT_HEIGHT = 14;
	// Per user report ("really buggy... snaps when its even close, I want it to only snap when its
	// actually there, and stay... unsnap if I drag it out of place"): two different thresholds now, not
	// one — a tight one to ENGAGE a snap (so it never pulls from a distance, only catches an already-close-
	// to-exact alignment) and a looser one to actually BREAK it once engaged (so small mouse jitter while
	// held there doesn't immediately let go). Y-axis (top/bottom) only — see mouseDragged's own comment for
	// why X snapping was dropped entirely.
	private static final int SNAP_ENGAGE_THRESHOLD_PX = 2;
	private static final int SNAP_RELEASE_THRESHOLD_PX = 6;
	private static final float SCROLL_SCALE_STEP = 0.1f;
	private static final float MIN_SCALE = 0.5f;
	private static final float MAX_SCALE = 3f;

	private final List<Bounds> bounds = new ArrayList<>();
	private MoveableWidget draggingWidget;
	private boolean resizing;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private float dragStartAnchorX;
	private float dragStartAnchorY;
	private float dragStartScale;
	private int draggingWidgetWidth;
	private int draggingWidgetHeight;
	private boolean ySnapped;
	private int snappedTop;
	private double snapEngageRawTop;

	// Per user request ("The gui elements page also seems to lag a LOT. Make sure stuff doesnt update or
	// anything when editing gui elements. Make sure they do update when leaving the editing however"): a
	// shared "is this screen currently open" flag, same pattern as DebugScreenGate.isOpen() — FeatureRegistry.
	// tickAll() checks this to skip every feature's per-tick work outright while this screen is open, since
	// this screen's own per-frame render-every-widget pass (see extractRenderState) is stacking directly on
	// top of the mod's usual full tick load rather than replacing any of it. Rendering itself is untouched —
	// widgets still redraw every frame so the preview stays live and draggable — only onTick() is skipped.
	private static volatile boolean open = false;

	public static boolean isOpen() {
		return open;
	}

	public HudEditScreen() {
		super(Component.literal("Edit GUI Locations"));
	}

	@Override
	protected void init() {
		super.init();
		open = true;
	}

	// removed() (not just onClose()) so this clears even if something else swaps the screen out from under
	// this one without going through the normal Escape/close path.
	@Override
	public void removed() {
		open = false;
		super.removed();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Deliberately no dim overlay: the point of this screen is seeing widgets over the real game.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		bounds.clear();

		int accent = Theme.accent();
		for (MoveableWidget widget : HudWidgetRegistry.all()) {
			// Only show widgets whose feature is actually turned on — editing the position of something
			// that isn't showing in-game right now is confusing busywork, not useful.
			if (widget instanceof Feature feature && !feature.isEnabled()) continue;
			// Per user request: an island-locked widget (e.g. a Garden-only Pest Timer) shouldn't clutter
			// this screen while standing somewhere it could never actually appear.
			if (!widget.isRelevantToCurrentIsland()) continue;
			HudPosition pos = widget.getPosition();
			int anchorPx = Math.round(pos.anchorX * this.width);
			int anchorY = Math.round(pos.anchorY * this.height);
			// Real crash found (user-reported): this screen calls every widget's render unconditionally,
			// including ones whose isVisible() is currently false (deliberately — see MoveableWidget's own
			// doc comment, a not-currently-showing widget still needs to be positionable in advance). Neither
			// render() nor renderExample() is guaranteed to handle that "called outside its normal visible
			// state" case safely (PurplePadTimerWidget didn't, and crashed the whole game over it) —
			// isolating each widget's render call the same way FeatureRegistry.tickAll() already isolates
			// onTick() means one broken widget skips itself for this frame instead of taking the whole edit
			// screen down.
			//
			// Per user requests ("All the gui elements in the edit gui panel should give an example of their
			// uses so the user can move and resize without actually having to see them in action" / "Im still
			// frame dropping a LOT in the edit gui panel"): calls renderExample() now instead of the real
			// render() — see MoveableWidget's own doc comment on renderExample for why this fixes both at
			// once. A hardcoded example needs no live game-state lookups, so every widget showing one instead
			// of its real content removes the per-frame cost this screen used to pay for calling every
			// registered widget's REAL render simultaneously (something normal gameplay never does, since it
			// only ever renders whichever one or two widgets are actually active).
			MoveableWidget.Size size;
			try {
				size = widget.renderExample(graphics, anchorPx, anchorY, pos.scale);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error(
					"HudEditScreen: {} widget's renderExample() threw, skipping it this frame", widget.getId(), e);
				continue;
			}

			// Only widgets that opt in via rightAlignsPastCenter() (TabWidgetOverlayFeature subclasses,
			// EstimatedItemValueFeature) actually flip their own render() to draw leftward from the anchor
			// once dragged past screen center — every other widget (e.g. DungeonMapFeature, whose own default
			// anchor of 0.85 already sits past center) always draws left-to-right from its anchor regardless
			// of screen position. Blanket-applying the right-align flip to every widget used to compute a box
			// on the wrong side of the anchor for those, which is exactly why their outline ended up not
			// matching where the content actually rendered.
			boolean rightAligned = widget.rightAlignsPastCenter() && anchorPx > this.width / 2;
			// alwaysCentersOnAnchor() widgets (Dungeon Notifications) draw centered on the anchor at every
			// screen position, not just past center — see that flag's own doc comment.
			boolean alwaysCentered = widget.alwaysCentersOnAnchor();
			// MoveableWidget.render's own contract draws flush against its top-left (or top-right, once
			// right-aligned) corner — the content itself already sat exactly there by the time this line
			// runs. Per user report ("STILL not rendering in the middle of their box... rendering top left"):
			// padding the hit/outline box out to MIN_HIT_WIDTH/HEIGHT only ever grew it to one side (right
			// and down from the anchor), so any widget smaller than that minimum visually looked pinned into
			// the box's top-left corner instead of centered. Width padding is still split evenly on both
			// sides (that's where the original report's visible mismatch actually was — a short number
			// stranded at the left edge of a much wider box). Height stays anchored flush at anchorY,
			// unshifted, rather than also being centered: per a FOLLOW-UP user report ("the mana doesnt
			// align with health, is a little higher up"), centering vertically ties the box's Y position to
			// that widget's OWN content height — which for independently-scaled sibling widgets (health/
			// mana/defense, meant to sit in the same row at the same anchorY) isn't guaranteed to match, so
			// two widgets with the same configured anchorY could end up with visibly different box tops.
			// Keeping the top flush at anchorY guarantees same-anchorY widgets always align with each other.
			int padW = Math.max(0, MIN_HIT_WIDTH - size.width());
			int h = Math.max(size.height(), MIN_HIT_HEIGHT);
			int w = size.width() + padW;
			int contentLeft = alwaysCentered ? anchorPx - size.width() / 2 : (rightAligned ? anchorPx - size.width() : anchorPx);
			int x = contentLeft - padW / 2;
			int y = anchorY;

			int color = widget == draggingWidget ? 0xFFFFFFFF : accent;
			drawOutline(graphics, x, y, w, h, color);
			// Every widget now always shows its own example content (see renderExample above), so the name
			// label is drawn unconditionally too — it's still useful context for telling widgets apart even
			// while their example content is visible, not just a fallback for otherwise-blank ones.
			SmoothTextRenderer.draw(graphics, this.font, widget.getDisplayName(), x, y - 10, 0xFFFFFFFF);

			int handleX = rightAligned ? x - HANDLE_SIZE : x + w - HANDLE_SIZE;
			int handleY = y + h - HANDLE_SIZE;
			com.cokelord.skyblocksimplified.gui.RenderUtil.fillRounded(graphics, handleX, handleY, handleX + HANDLE_SIZE, handleY + HANDLE_SIZE, 2, color);

			int resetX = rightAligned ? x - RESET_BUTTON_SIZE - 4 : x + w + 4;
			int resetY = y;
			com.cokelord.skyblocksimplified.gui.RenderUtil.fillRounded(graphics, resetX, resetY, resetX + RESET_BUTTON_SIZE, resetY + RESET_BUTTON_SIZE, 2, 0xFF444444);
			graphics.text(this.font, "R", resetX + 2, resetY + 2, 0xFFFFFFFF);

			// Per user request ("Add per-widget snap toggle (S button) to Player Display HUD elements in edit
			// GUI"), later broadened ("All gui elements should be able to unsnap"): every widget gets this
			// button now, not just Player Display's 4 stat widgets — snapEnabled already exists as a plain
			// per-HudPosition field (defaults true, same always-on behavior every widget already had), so
			// this is purely dropping the instanceof gate that used to hide the button for everything else.
			int snapX = rightAligned ? resetX - SNAP_BUTTON_SIZE - 4 : resetX + RESET_BUTTON_SIZE + 4;
			int snapY = y;
			boolean snapOn = pos.snapEnabled;
			com.cokelord.skyblocksimplified.gui.RenderUtil.fillRounded(graphics, snapX, snapY, snapX + SNAP_BUTTON_SIZE, snapY + SNAP_BUTTON_SIZE, 2, snapOn ? accent : 0xFF444444);
			graphics.text(this.font, "S", snapX + 2, snapY + 2, 0xFFFFFFFF);

			bounds.add(new Bounds(widget, x, y, w, h, handleX, handleY, resetX, resetY, snapX, snapY));
		}
	}

	// Rounded ring instead of 4 hard-edged hairlines — matches every other panel/box this mod draws
	// (all through RenderUtil's smoothed primitives) instead of looking like a stray raw rectangle.
	private static final int OUTLINE_RADIUS = 3;

	private void drawOutline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
		com.cokelord.skyblocksimplified.gui.RenderUtil.fillRoundedRing(graphics, x, y, x + w, y + h, OUTLINE_RADIUS, 1, color);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		for (Bounds b : bounds) {
			if (mx >= b.resetX && mx <= b.resetX + RESET_BUTTON_SIZE && my >= b.resetY && my <= b.resetY + RESET_BUTTON_SIZE) {
				b.widget.resetPosition();
				ConfigManager.save();
				return true;
			}
			if (b.snapX >= 0 && mx >= b.snapX && mx <= b.snapX + SNAP_BUTTON_SIZE && my >= b.snapY && my <= b.snapY + SNAP_BUTTON_SIZE) {
				b.widget.getPosition().snapEnabled = !b.widget.getPosition().snapEnabled;
				ConfigManager.save();
				return true;
			}
			if (mx >= b.handleX && mx <= b.handleX + HANDLE_SIZE && my >= b.handleY && my <= b.handleY + HANDLE_SIZE) {
				draggingWidget = b.widget;
				resizing = true;
				dragStartMouseX = mx;
				dragStartMouseY = my;
				dragStartScale = b.widget.getPosition().scale;
				return true;
			}
			if (mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h) {
				draggingWidget = b.widget;
				resizing = false;
				dragStartMouseX = mx;
				dragStartMouseY = my;
				dragStartAnchorX = b.widget.getPosition().anchorX;
				dragStartAnchorY = b.widget.getPosition().anchorY;
				draggingWidgetWidth = b.w;
				draggingWidgetHeight = b.h;
				ySnapped = false;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingWidget == null) return super.mouseDragged(event, dragX, dragY);
		HudPosition pos = draggingWidget.getPosition();
		if (resizing) {
			float delta = (float) ((event.x() - dragStartMouseX) / 100.0);
			pos.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, dragStartScale + delta));
		} else {
			float dx = (float) ((event.x() - dragStartMouseX) / this.width);
			float dy = (float) ((event.y() - dragStartMouseY) / this.height);
			float candidateAnchorX = dragStartAnchorX + dx;
			float candidateAnchorY = dragStartAnchorY + dy;
			// Clamp against the widget's own rendered size, not just its anchor point — otherwise the
			// anchor itself stays in [0,1] but the box (anchor + width/height) can still hang off the
			// right/bottom edge of the screen. Which direction to clamp in depends on which side of center
			// the anchor lands on: past center the widget right-aligns (anchor = box's right edge, so it's
			// the LEFT side that needs headroom not to run off-screen), same rule the render/preview side
			// uses — matching that exactly is what keeps a widget dragged all the way right actually
			// landing flush against the true edge instead of stopping short by one box-width.
			float widthFraction = (float) draggingWidgetWidth / this.width;
			boolean rightAligned = draggingWidget.rightAlignsPastCenter() && Math.round(candidateAnchorX * this.width) > this.width / 2;
			// alwaysCentersOnAnchor() widgets need headroom on BOTH sides (half the width each), not just one.
			boolean alwaysCentered = draggingWidget.alwaysCentersOnAnchor();
			float minAnchorX = alwaysCentered ? widthFraction / 2f : (rightAligned ? widthFraction : 0f);
			float maxAnchorX = alwaysCentered ? Math.max(0f, 1f - widthFraction / 2f) : (rightAligned ? 1f : Math.max(0f, 1f - widthFraction));
			float maxAnchorY = Math.max(0f, 1f - (float) draggingWidgetHeight / this.height);
			candidateAnchorX = clampRange(candidateAnchorX, minAnchorX, maxAnchorX);
			candidateAnchorY = clampRange(candidateAnchorY, 0f, maxAnchorY);

			// Snap-to-other-widgets, Y-axis (top/bottom) only — per user report, X-axis snapping ("sides of
			// things") looked bad and got in the way of placing widgets exactly where wanted, so it's
			// dropped entirely; X always follows the mouse literally now.
			//
			// Sticky, not magnetic: this used to recompute a snap fresh from the raw candidate every single
			// drag event, which reads as "snaps when its even close" — any frame within the threshold pulled
			// the widget toward the guide, even while actively dragging past it. Now there are two separate,
			// far-apart thresholds. Nothing is snapped yet: only engage once the raw (mouse-following)
			// position already lands within SNAP_ENGAGE_THRESHOLD_PX by itself — a real "it's already there"
			// catch, not a pull from a distance. Once engaged: hold the exact snapped position regardless of
			// small mouse jitter, and only let go once the raw position has drifted more than
			// SNAP_RELEASE_THRESHOLD_PX away from where it was at the moment it snapped — that's the "stay
			// for a split second... unsnap if I drag it out of place" behavior.
			int candAnchorPx = Math.round(candidateAnchorX * this.width);
			int candLeft = rightAligned ? candAnchorPx - draggingWidgetWidth : candAnchorPx;
			int rawTop = Math.round(candidateAnchorY * this.height);

			int newTop;
			// Per user request ("Add per-widget snap toggle (S button)..."): a widget with snapping turned
			// off via its own "S" button always follows the raw mouse position, same as X already does.
			if (!pos.snapEnabled) {
				ySnapped = false;
				newTop = rawTop;
			} else if (ySnapped) {
				if (Math.abs(rawTop - snapEngageRawTop) > SNAP_RELEASE_THRESHOLD_PX) {
					ySnapped = false;
					newTop = rawTop;
				} else {
					newTop = snappedTop;
				}
			} else {
				List<int[]> yRanges = new ArrayList<>();
				for (Bounds b : bounds) {
					if (b.widget == draggingWidget) continue;
					yRanges.add(new int[]{b.y, b.y + b.h});
				}
				Integer delta = snapDelta(rawTop, rawTop + draggingWidgetHeight, yRanges, SNAP_ENGAGE_THRESHOLD_PX);
				if (delta != null) {
					ySnapped = true;
					snappedTop = rawTop + delta;
					snapEngageRawTop = rawTop;
					newTop = snappedTop;
				} else {
					newTop = rawTop;
				}
			}

			int newAnchorPx = rightAligned ? candLeft + draggingWidgetWidth : candLeft;
			float newAnchorX = clampRange((float) newAnchorPx / this.width, minAnchorX, maxAnchorX);
			float newAnchorY = clampRange((float) newTop / this.height, 0f, maxAnchorY);
			pos.set(newAnchorX, newAnchorY);
		}
		return true;
	}

	/** Smallest-distance snap adjustment along one axis against every other widget's box on that axis:
	 *  matching edges (start-start / end-end — "line up with the top/bottom of another widget") or sitting
	 *  flush against one (start-end / end-start). Returns {@code null} (not zero — zero is itself a valid,
	 *  real "already exactly aligned" delta, so it can't double as the "nothing found" signal) once nothing
	 *  is within {@code threshold}. */
	private static Integer snapDelta(int candidateStart, int candidateEnd, List<int[]> otherRanges, int threshold) {
		Integer bestDelta = null;
		int bestDistance = threshold + 1;
		for (int[] range : otherRanges) {
			int[] candidateDeltas = {range[0] - candidateStart, range[1] - candidateEnd, range[1] - candidateStart, range[0] - candidateEnd};
			for (int delta : candidateDeltas) {
				int distance = Math.abs(delta);
				if (distance <= threshold && distance < bestDistance) {
					bestDistance = distance;
					bestDelta = delta;
				}
			}
		}
		return bestDelta;
	}

	// Per user request: scroll up/down over a widget to grow/shrink it, without needing to grab the resize
	// handle first — same scale field and [MIN_SCALE, MAX_SCALE] clamp the handle itself already uses.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
		for (Bounds b : bounds) {
			if (mouseX >= b.x && mouseX <= b.x + b.w && mouseY >= b.y && mouseY <= b.y + b.h) {
				HudPosition pos = b.widget.getPosition();
				pos.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, pos.scale + (float) scrollDeltaY * SCROLL_SCALE_STEP));
				ConfigManager.save();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollDeltaX, scrollDeltaY);
	}

	private static float clampRange(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingWidget != null) {
			ConfigManager.save();
		}
		draggingWidget = null;
		resizing = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// Per user report — Escape used to fall through to vanilla Screen's default onClose (this class never
	// overrode it), which just closes straight back to gameplay. This is reached FROM the mod menu (About >
	// Edit gui locations), so Escape here should return there instead of dropping the player out entirely.
	@Override
	public void onClose() {
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new MainScreen());
	}

	private record Bounds(MoveableWidget widget, int x, int y, int w, int h, int handleX, int handleY, int resetX, int resetY,
						   int snapX, int snapY) {}
}
