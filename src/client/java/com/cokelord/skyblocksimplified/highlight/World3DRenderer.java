package com.cokelord.skyblocksimplified.highlight;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Real, depth-tested 3D world rendering — draws through the same modern submit/GPU-command-encoder
 * pipeline vanilla's own level renderer uses, so boxes actually sit on/around the real block (occluded by
 * terrain in front of them) instead of {@link WorldRenderUtil}'s screen-space projection (which stays the
 * default for dungeon ESP — always-visible-through-walls is the deliberately-kept behavior there per user
 * request; this class is for features that specifically want a true occluded highlight, currently just
 * Etherwarp).
 *
 * <p>Ported from Skyblocker's {@code utils.render.Renderer}/{@code RenderHelper} (confirmed real against
 * this exact MC version — every class/method referenced here was checked directly against this project's
 * own {@code minecraft-client-only.jar} and {@code fabric-rendering-v1} jar before writing this, not
 * assumed from Skyblocker's compiled code alone), trimmed of its {@code LevelExtractionEvents}/frustum-
 * culling "primitive collector" layer: Skyblocker built that to let many independent call sites register
 * culling-aware draw callbacks through one indirection layer, which isn't needed here since {@link
 * #addRenderCallback} already gives every feature its own simple per-frame draw callback, the same shape
 * every other per-feature hook in this codebase already uses (e.g. {@code HudElementRegistry.addLast}).
 * {@code LevelRenderContext} (available directly in {@code LevelRenderEvents.END_MAIN}) already exposes
 * the same {@code cameraRenderState.pos} Skyblocker's separate extraction phase existed partly to reach,
 * so the whole extraction split turned out unnecessary for this simpler use case.
 *
 * <p>{@link #drawFilledBoxThroughWalls}/{@link #drawWireBoxThroughWalls} are the one exception to "this
 * class means real occlusion" — same real 3D box geometry (proper perspective/rotation, unlike {@link
 * WorldRenderUtil}'s flat AABB-corner screen projection), but drawn through a depth state that always
 * passes ({@code CompareOp.ALWAYS_PASS}, no depth write) instead of the normal one, per user request (Door
 * Highlight: "the door needs to be a block HIGHLIGHT and not a 2d render... it needs to be visible through
 * walls, this is allowed since it pulls off the map"). */
public final class World3DRenderer {
	private World3DRenderer() {}

	private static final Minecraft CLIENT = Minecraft.getInstance();
	// Real bug found (per user report — "a lot of features using it unoccluded don't seem to work, like
	// boss guide"): decompiled RenderPipelines.java shows DEBUG_FILLED_BOX/LINES are built from a Snippet
	// (RenderPipeline.builder(Snippet...)), not the no-arg builder + individually-chained calls this class
	// used to rebuild them with — and that Snippet chain includes `.withBindGroupLayout(...)` calls
	// (GLOBALS + MATRICES_PROJECTION for DEBUG_FILLED_BOX; whatever LINES' own MATRICES_FOG_SNIPPET chain
	// declares) that were never copied here at all. Without the real bind group layouts, the GPU pipeline
	// has nowhere to actually receive the camera/projection matrices the draw calls rely on — it doesn't
	// crash, it just silently never renders anything, which is exactly "the box drawn every frame per the
	// debug log but nothing visible" for every single feature using these NO_DEPTH variants (Door
	// Highlight, Boss Guide's block/waypoint markers, Inactive Waypoints' box), while every feature using
	// the STOCK RenderPipelines.DEBUG_FILLED_BOX/LINES directly (BoulderSolverFeature, Quiz Solver) was
	// never affected, since those go through the real, fully-specified pipeline object, not a hand copy of
	// it. Fixed via {@link #withNoDepth}, which now also copies {@code getBindGroupLayouts()}. A fresh
	// Identifier location is still required (pipelines are registered/looked-up by it).
	private static RenderPipeline.Builder withNoDepth(RenderPipeline source, Identifier location) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			// Real crash found (per user-reported startup crash — IdentifierException: "Non [a-z0-9/._-]
			// character in path"): the String overload of withLocation() treats its whole argument as a bare
			// PATH and prepends the default "minecraft" namespace onto it (Identifier.withDefaultNamespace),
			// rather than parsing a "namespace:path" pair — passing "skyblocksimplified:pipeline/..." here
			// produced the literal, invalid path "minecraft:skyblocksimplified:pipeline/..." (a colon inside a
			// path, which Identifier's own path-character validator rejects) instead of a real
			// "skyblocksimplified:pipeline/..." identifier. Uses the Identifier overload instead, which takes
			// an already-constructed namespace/path pair directly with no re-parsing.
			.withLocation(location)
			.withVertexShader(source.getVertexShader())
			.withFragmentShader(source.getFragmentShader())
			.withColorTargetState(source.getColorTargetState())
			.withVertexBinding(0, source.getVertexFormatBinding(0))
			.withPrimitiveTopology(source.getPrimitiveTopology())
			.withCull(source.isCull())
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false));
		for (com.mojang.blaze3d.pipeline.BindGroupLayout layout : source.getBindGroupLayouts()) {
			builder = builder.withBindGroupLayout(layout);
		}
		return builder;
	}

	private static final RenderPipeline DEBUG_FILLED_BOX_NO_DEPTH = withNoDepth(RenderPipelines.DEBUG_FILLED_BOX,
		Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "pipeline/debug_filled_box_no_depth")).build();
	private static final RenderPipeline LINES_NO_DEPTH = withNoDepth(RenderPipelines.LINES,
		Identifier.fromNamespaceAndPath(SkyblockSimplified.MOD_ID, "pipeline/lines_no_depth")).build();
	private static final StagedVertexBuffer VERTEX_BUFFER = new StagedVertexBuffer(() -> "SkyblockSimplified World3DRenderer Vertex Buffer", RenderType.SMALL_BUFFER_SIZE);
	private static final List<Draw> DRAWS = new ArrayList<>();
	private static final List<Runnable> renderCallbacks = new ArrayList<>();

	private static RenderPipeline previousPipeline;
	private static float previousAlpha = 1f;
	private static StagedVertexBuffer.Draw previousDraw;
	private static Matrix4f positionMatrix;
	private static boolean registered = false;

	/** Registers a callback invoked once per frame during the level-render pass — call {@link #drawWireBox}/
	 *  {@link #drawFilledBox} from inside it, same as registering a HudElementRegistry render callback but
	 *  for real world-space geometry instead of the HUD layer. Not un-registerable, matching every other
	 *  always-registered-once feature hook in this codebase — features gate their own drawing internally
	 *  (isEnabled() etc.), same pattern as HudElementRegistry.addLast consumers. */
	public static void addRenderCallback(Runnable callback) {
		ensureRegistered();
		renderCallbacks.add(callback);
	}

	private static synchronized void ensureRegistered() {
		if (registered) return;
		registered = true;
		LevelRenderEvents.END_MAIN.register(World3DRenderer::onEndMain);
	}

	private static void onEndMain(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
		prepare();
		Vec3 camPos = context.levelState().cameraRenderState.pos;
		positionMatrix = new Matrix4f().translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
		for (Runnable callback : renderCallbacks) {
			try {
				callback.run();
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("World3DRenderer render callback threw, skipping it this frame", e);
			}
		}
		executeDraws();
	}

	/** ARGB int color, real depth-tested outline box. */
	public static void drawWireBox(AABB box, int argb, float lineWidth) {
		drawWireBox(box, argb, lineWidth, RenderPipelines.LINES);
	}

	/** Same real 3D box geometry as {@link #drawWireBox}, but never occluded by walls/terrain — see this
	 *  class's own doc comment. */
	public static void drawWireBoxThroughWalls(AABB box, int argb, float lineWidth) {
		drawWireBox(box, argb, lineWidth, LINES_NO_DEPTH);
	}

	private static void drawWireBox(AABB box, int argb, float lineWidth, RenderPipeline pipeline) {
		VertexConsumer buffer = getBuffer(pipeline);
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f, a = ((argb >>> 24) & 0xFF) / 255f;
		float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
		float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

		vertex(buffer, minX, minY, minZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, maxX, minY, minZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, minX, minY, minZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, minX, maxY, minZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, minX, minY, minZ, r, g, b, a, 0, 0, 1, lineWidth);
		vertex(buffer, minX, minY, maxZ, r, g, b, a, 0, 0, 1, lineWidth);
		vertex(buffer, maxX, minY, minZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, maxX, maxY, minZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, maxX, maxY, minZ, r, g, b, a, -1, 0, 0, lineWidth);
		vertex(buffer, minX, maxY, minZ, r, g, b, a, -1, 0, 0, lineWidth);
		vertex(buffer, minX, maxY, minZ, r, g, b, a, 0, 0, 1, lineWidth);
		vertex(buffer, minX, maxY, maxZ, r, g, b, a, 0, 0, 1, lineWidth);
		vertex(buffer, minX, maxY, maxZ, r, g, b, a, 0, -1, 0, lineWidth);
		vertex(buffer, minX, minY, maxZ, r, g, b, a, 0, -1, 0, lineWidth);
		vertex(buffer, minX, minY, maxZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, maxX, minY, maxZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, maxX, minY, maxZ, r, g, b, a, 0, 0, -1, lineWidth);
		vertex(buffer, maxX, minY, minZ, r, g, b, a, 0, 0, -1, lineWidth);
		vertex(buffer, minX, maxY, maxZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, maxX, maxY, maxZ, r, g, b, a, 1, 0, 0, lineWidth);
		vertex(buffer, maxX, minY, maxZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, maxX, maxY, maxZ, r, g, b, a, 0, 1, 0, lineWidth);
		vertex(buffer, maxX, maxY, minZ, r, g, b, a, 0, 0, 1, lineWidth);
		vertex(buffer, maxX, maxY, maxZ, r, g, b, a, 0, 0, 1, lineWidth);
	}

	private static void vertex(VertexConsumer buffer, float x, float y, float z, float r, float g, float b, float a, float nx, float ny, float nz, float lineWidth) {
		buffer.addVertex(positionMatrix, x, y, z).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(lineWidth);
	}

	/** ARGB int color, real depth-tested filled box (all 6 faces). */
	public static void drawFilledBox(AABB box, int argb) {
		drawFilledBox(box, argb, RenderPipelines.DEBUG_FILLED_BOX);
	}

	/** Same real 3D box geometry as {@link #drawFilledBox}, but never occluded by walls/terrain — see this
	 *  class's own doc comment. */
	public static void drawFilledBoxThroughWalls(AABB box, int argb) {
		drawFilledBox(box, argb, DEBUG_FILLED_BOX_NO_DEPTH);
	}

	private static void drawFilledBox(AABB box, int argb, RenderPipeline pipeline) {
		VertexConsumer buffer = getBuffer(pipeline);
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f, a = ((argb >>> 24) & 0xFF) / 255f;
		float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
		float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

		// Front
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
		// Back
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
		// Left
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);
		// Right
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
		// Top
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);
		// Bottom
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
	}

	/** ARGB int color, real depth-tested horizontal ring (Gyro Helper's sucking-range indicator). */
	public static void drawCircle(Vec3 center, double radius, int argb, float lineWidth) {
		drawCircle(center, radius, argb, lineWidth, RenderPipelines.LINES);
	}

	/** Same real ring geometry as {@link #drawCircle}, but never occluded by walls/terrain. */
	public static void drawCircleThroughWalls(Vec3 center, double radius, int argb, float lineWidth) {
		drawCircle(center, radius, argb, lineWidth, LINES_NO_DEPTH);
	}

	/** Real, ported geometry from Odin's own confirmed {@code RenderUtils.drawCylinder} (Gyro Wand's own AoE
	 *  indicator, the same primitive Positional Messages uses there too — matching the user's own comparison,
	 *  "a circle like the positional messages but thicker"): a bottom ring at {@code center}'s Y, a top ring
	 *  {@code height} blocks above it, and one vertical edge per segment connecting them — a short, wide-lined
	 *  wireframe cylinder that reads as a "thick ring" rather than a flat 2D circle. {@code depth} matches
	 *  Odin's own optional depth-test parameter (Gyro Wand defaults it off — the wand's own range indicator
	 *  is meant to stay visible through the ground/mobs). */
	public static void drawCylinder(Vec3 center, double radius, double height, int argb, int segments, float lineWidth, boolean depth) {
		double cx = center.x, cy = center.y, cz = center.z;
		for (int i = 0; i < segments; i++) {
			double a1 = 2 * Math.PI * i / segments;
			double a2 = 2 * Math.PI * (i + 1) / segments;
			Vec3 p1Bottom = new Vec3(cx + radius * Math.cos(a1), cy, cz + radius * Math.sin(a1));
			Vec3 p2Bottom = new Vec3(cx + radius * Math.cos(a2), cy, cz + radius * Math.sin(a2));
			Vec3 p1Top = p1Bottom.add(0, height, 0);
			Vec3 p2Top = p2Bottom.add(0, height, 0);
			if (depth) {
				drawLine(p1Top, p2Top, argb, lineWidth);
				drawLine(p1Bottom, p2Bottom, argb, lineWidth);
				drawLine(p1Bottom, p1Top, argb, lineWidth);
			} else {
				drawLineThroughWalls(p1Top, p2Top, argb, lineWidth);
				drawLineThroughWalls(p1Bottom, p2Bottom, argb, lineWidth);
				drawLineThroughWalls(p1Bottom, p1Top, argb, lineWidth);
			}
		}
	}

	private static final int CIRCLE_SEGMENTS = 48;

	private static void drawCircle(Vec3 center, double radius, int argb, float lineWidth, RenderPipeline pipeline) {
		VertexConsumer buffer = getBuffer(pipeline);
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f, a = ((argb >>> 24) & 0xFF) / 255f;
		float cy = (float) center.y;
		for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
			double a1 = 2 * Math.PI * i / CIRCLE_SEGMENTS;
			double a2 = 2 * Math.PI * (i + 1) / CIRCLE_SEGMENTS;
			float x1 = (float) (center.x + radius * Math.cos(a1));
			float z1 = (float) (center.z + radius * Math.sin(a1));
			float x2 = (float) (center.x + radius * Math.cos(a2));
			float z2 = (float) (center.z + radius * Math.sin(a2));
			vertex(buffer, x1, cy, z1, r, g, b, a, x2 - x1, 0, z2 - z1, lineWidth);
			vertex(buffer, x2, cy, z2, r, g, b, a, x2 - x1, 0, z2 - z1, lineWidth);
		}
	}

	/** A flat square sitting on the ground at {@code center}'s Y level, {@code halfSize} out from center in
	 *  every horizontal direction — real depth-tested equivalent of {@code WorldRenderUtil.drawGroundSquare},
	 *  used by Positional Messages' range indicator (task #602: migrating world-space overlays off the old
	 *  screen-space projection path onto this real 3D renderer). Each edge is its own {@link #drawLine} call,
	 *  same as {@link #drawCircle}'s per-segment approach. */
	public static void drawGroundSquare(Vec3 center, double halfSize, int argb, float lineWidth) {
		drawGroundSquare(center, halfSize, argb, lineWidth, false);
	}

	/** Same real square geometry as {@link #drawGroundSquare}, but never occluded by walls/terrain. */
	public static void drawGroundSquareThroughWalls(Vec3 center, double halfSize, int argb, float lineWidth) {
		drawGroundSquare(center, halfSize, argb, lineWidth, true);
	}

	private static void drawGroundSquare(Vec3 center, double halfSize, int argb, float lineWidth, boolean throughWalls) {
		Vec3 a = center.add(-halfSize, 0, -halfSize);
		Vec3 b = center.add(halfSize, 0, -halfSize);
		Vec3 c = center.add(halfSize, 0, halfSize);
		Vec3 d = center.add(-halfSize, 0, halfSize);
		if (throughWalls) {
			drawLineThroughWalls(a, b, argb, lineWidth);
			drawLineThroughWalls(b, c, argb, lineWidth);
			drawLineThroughWalls(c, d, argb, lineWidth);
			drawLineThroughWalls(d, a, argb, lineWidth);
		} else {
			drawLine(a, b, argb, lineWidth);
			drawLine(b, c, argb, lineWidth);
			drawLine(c, d, argb, lineWidth);
			drawLine(d, a, argb, lineWidth);
		}
	}

	/** ARGB int color, real depth-tested straight segment between two world positions. */
	public static void drawLine(Vec3 from, Vec3 to, int argb, float lineWidth) {
		drawLine(from, to, argb, lineWidth, RenderPipelines.LINES);
	}

	/** Same real 3D line geometry as {@link #drawLine}, but never occluded by walls/terrain — used by
	 *  Dungeon Routes' waypoint path line (per user request: "the waypoint beacon line doesnt render at
	 *  all... it should find the fastest way to the waypoint and render that with a line"), same
	 *  through-walls mechanism as {@link #drawWireBoxThroughWalls}. */
	public static void drawLineThroughWalls(Vec3 from, Vec3 to, int argb, float lineWidth) {
		drawLine(from, to, argb, lineWidth, LINES_NO_DEPTH);
	}

	private static void drawLine(Vec3 from, Vec3 to, int argb, float lineWidth, RenderPipeline pipeline) {
		VertexConsumer buffer = getBuffer(pipeline);
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f, a = ((argb >>> 24) & 0xFF) / 255f;
		float dx = (float) (to.x - from.x), dy = (float) (to.y - from.y), dz = (float) (to.z - from.z);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = len > 0f ? dx / len : 1f, ny = len > 0f ? dy / len : 0f, nz = len > 0f ? dz / len : 0f;
		vertex(buffer, (float) from.x, (float) from.y, (float) from.z, r, g, b, a, nx, ny, nz, lineWidth);
		vertex(buffer, (float) to.x, (float) to.y, (float) to.z, r, g, b, a, nx, ny, nz, lineWidth);
	}

	private static VertexConsumer getBuffer(RenderPipeline pipeline) {
		float alphaMultiplier = 1f;
		if (previousDraw == null || pipeline != previousPipeline || alphaMultiplier != previousAlpha) {
			previousDraw = VERTEX_BUFFER.appendDraw(pipeline.getVertexFormatBinding(0), pipeline.getPrimitiveTopology());
			DRAWS.add(new Draw(previousDraw, pipeline, alphaMultiplier));
			previousPipeline = pipeline;
			previousAlpha = alphaMultiplier;
		}
		return VERTEX_BUFFER.getVertexBuilder(previousDraw);
	}

	private static void prepare() {
		previousDraw = null;
		previousPipeline = null;
		previousAlpha = 1f;
	}

	private static void executeDraws() {
		VERTEX_BUFFER.upload();
		dispatchDraws();
		VERTEX_BUFFER.endDraw();
		VERTEX_BUFFER.endFrame();
		DRAWS.clear();
	}

	private static void dispatchDraws() {
		if (DRAWS.isEmpty()) return;
		Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
		modelViewStack.pushMatrix();
		RenderSystem.getProjectionType().applyLayeringTransform(modelViewStack, 1f);

		RenderTarget mainRenderTarget = CLIENT.gameRenderer.mainRenderTarget();
		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> "SkyblockSimplified World3DRenderer",
					mainRenderTarget.getColorTextureView(),
					Optional.empty(),
					mainRenderTarget.useDepth ? mainRenderTarget.getDepthTextureView() : null,
					OptionalDouble.empty()
				)) {
			RenderSystem.bindDefaultUniforms(renderPass);
			for (Draw draw : DRAWS) draw(draw, renderPass);
		}

		RenderSystem.getModelViewStack().popMatrix();
	}

	private static void draw(Draw draw, RenderPass renderPass) {
		StagedVertexBuffer.ExecuteInfo executeInfo = VERTEX_BUFFER.getExecuteInfo(draw.draw());
		if (executeInfo == null) return;

		renderPass.setPipeline(draw.pipeline());
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1f, 1f, 1f, draw.alphaMultiplier()));
		renderPass.setUniform("DynamicTransforms", dynamicTransforms);

		renderPass.setVertexBuffer(0, executeInfo.vertexBuffer().slice());
		renderPass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType());
		renderPass.drawIndexed(executeInfo.indexCount(), 1, executeInfo.firstIndex(), executeInfo.baseVertex(), 0);
	}

	private record Draw(StagedVertexBuffer.Draw draw, RenderPipeline pipeline, float alphaMultiplier) {}
}
