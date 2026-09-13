package com.cokelord.skyblocksimplified.highlight;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Projects a world-space position to a screen-space pixel coordinate, for HUD-layer overlays that need
 * to track a 3D position (the highlight render-mode picker's 2D/2D-Full box modes).
 *
 * Previously read {@code Camera.getViewRotationProjectionMatrix()} live at HUD-render time, on the theory
 * that `Camera` is a persistent per-frame object safe to read whenever. That was wrong: `Camera` also owns
 * `setupOrtho`/`setupPerspective` and caches its projection+viewRotation product behind a dirty-flag/
 * version check tied to its `Projection` field's version counter — by the time our HUD element runs (after
 * the 3D world pass, alongside other 2D/orthographic HUD drawing), that cache is not guaranteed to still
 * hold the perspective matrix from the world render. That's what produced the "renders as a single point"
 * bug (a fill+outline box drawn at a degenerate 1-2px size looks like a tiny "+", not a dot, because the
 * four outline bars overlap almost exactly).
 *
 * Fixed by capturing `CameraRenderState.projectionMatrix`/`viewRotationMatrix` directly from
 * `LevelRenderEvents.END_MAIN` — the actual matrices Vanilla just used to render that frame's 3D world,
 * guaranteed fresh and guaranteed perspective — and reusing that snapshot for every projection until the
 * next frame's world render updates it.
 */
public final class WorldToScreen {
	private WorldToScreen() {}

	private static boolean captured = false;
	private static final Matrix4f capturedViewProjection = new Matrix4f();
	private static Vec3 capturedCameraPos = Vec3.ZERO;
	private static List<AABB> capturedPlayerBoxes = Collections.emptyList();

	private static boolean registered = false;

	public static void register() {
		if (registered) return;
		registered = true;
		LevelRenderEvents.END_MAIN.register(context -> {
			var cameraState = context.levelState().cameraRenderState;
			if (cameraState == null || !cameraState.initialized
				|| cameraState.projectionMatrix == null || cameraState.viewRotationMatrix == null) {
				return;
			}
			capturedViewProjection.set(cameraState.projectionMatrix).mul(cameraState.viewRotationMatrix);
			capturedCameraPos = cameraState.pos;
			captured = true;

			// Built from the SAME per-frame extracted render states vanilla just used to actually draw every
			// player model — exact interpolated position/size, not the raw tick-based Entity#getBoundingBox()
			// (which lags up to a tick behind a moving player and was letting occlusion checks miss). Also
			// naturally includes the local player's own body exactly when it's actually being drawn (third
			// person) and naturally excludes it when it isn't (first person, where you never render yourself)
			// — no separate "is this me" check needed.
			// Players have no EntityType constant of their own (they're not registry-spawned like other
			// entities), so AvatarRenderState — the player-specific EntityRenderState subclass, carrying
			// skin/cape/etc. — is what actually identifies "this render state is a player" here.
			List<AABB> boxes = new ArrayList<>();
			for (EntityRenderState state : context.levelState().entityRenderStates) {
				if (!(state instanceof AvatarRenderState avatar) || avatar.isSpectator) continue;
				float half = state.boundingBoxWidth / 2f;
				boxes.add(new AABB(state.x - half, state.y, state.z - half, state.x + half, state.y + state.boundingBoxHeight, state.z + half));
			}
			capturedPlayerBoxes = boxes;
		});
	}

	/** Player hitboxes as actually drawn this frame (see the capture site's own doc comment) — used by
	 *  {@link WorldRenderUtil}'s line occlusion to skip drawing through a teammate. */
	public static List<AABB> getPlayerBoxes() {
		return capturedPlayerBoxes;
	}

	/** The camera position captured alongside the projection matrix this frame — used by occlusion checks
	 *  (see WorldRenderUtil) so a wall/entity raycast starts from the exact same eye position the projection
	 *  itself is relative to, rather than a separately-read (and potentially one-frame-stale) player eye
	 *  position. Null until the first world render frame has completed. */
	public static Vec3 getCameraPos() {
		return captured ? capturedCameraPos : null;
	}

	public record ScreenPoint(float x, float y, float scale, boolean behindCamera) {}

	/** Projects a world position to screen pixel coordinates (origin top-left, matching GUI coordinate
	 *  space) plus a relative "scale" factor (1.0 at 4 blocks away, smaller further out) useful for
	 *  sizing an on-screen box so it roughly matches perspective without a full 3D box mesh. Returns
	 *  behindCamera=true (and callers should skip drawing) if the point is behind the camera. */
	public static ScreenPoint project(Vec3 worldPos) {
		if (!captured) return new ScreenPoint(0, 0, 0, true);
		Minecraft mc = Minecraft.getInstance();

		double dx = worldPos.x - capturedCameraPos.x;
		double dy = worldPos.y - capturedCameraPos.y;
		double dz = worldPos.z - capturedCameraPos.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		Vector4f clip = new Vector4f((float) dx, (float) dy, (float) dz, 1f);
		capturedViewProjection.transform(clip);

		// A point grazing near 90 degrees off the view axis still has a tiny positive w, so the perspective
		// divide (clip.x / clip.w) blows its NDC coordinate up to a huge-but-finite value just before it
		// would actually go behind the camera — drawLine's single rotated-rect fill() then draws that as one
		// very long line flung across the screen instead of the target cleanly vanishing off the edge.
		// Raising the cutoff excludes that whole grazing range too.
		if (clip.w <= 0.05f) return new ScreenPoint(0, 0, 0, true);
		float ndcX = clip.x / clip.w;
		float ndcY = clip.y / clip.w;

		int screenWidth = mc.getWindow().getGuiScaledWidth();
		int screenHeight = mc.getWindow().getGuiScaledHeight();
		float screenX = (ndcX * 0.5f + 0.5f) * screenWidth;
		float screenY = (1f - (ndcY * 0.5f + 0.5f)) * screenHeight;
		float scale = (float) Math.max(0.15, Math.min(2.5, 4.0 / Math.max(1.0, distance)));
		return new ScreenPoint(screenX, screenY, scale, false);
	}
}
