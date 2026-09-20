package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature;
import com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature;
import com.cokelord.skyblocksimplified.highlight.EntityRenderStateAlphaAccessor;
import com.cokelord.skyblocksimplified.highlight.EntityTransparencyRegistry;
import com.cokelord.skyblocksimplified.highlight.MobHighlightRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the outline color vanilla computes for glowing entities (state.outlineColor, normally
 * Entity.getTeamColor() when appearsGlowing) so any feature can highlight any entity in any color via
 * MobHighlightRegistry. Setting outlineColor alone previously did nothing visible: vanilla only routes an
 * entity through the outline render pass at all when Entity#isCurrentlyGlowing() is true, and this mixin
 * wasn't forcing that — so a highlighted-but-not-actually-glowing mob (the common case) never entered the
 * pass that would have consulted outlineColor in the first place. Fixed by also forcing
 * isCurrentlyGlowing() true whenever a highlight rule matches.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void skyblocksimplified$applyHighlight(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		// Real bug found: whatever specific highlight rule matched (name-based rules are the likely
		// culprit — a coincidental substring/casing match against the local player's own name), nothing
		// here ever stopped a rule from targeting the player THEMSELVES, producing a self-glow outline
		// (and, since the local player's body is normally culled entirely in first-person, a garbled
		// duplicate silhouette from the vanilla invisible-but-glowing render bypass). No highlight rule
		// should ever be able to match the local player, regardless of which one fired — enforced once
		// here instead of trusting every current and future rule's predicate to remember it.
		boolean isSelf = entity == net.minecraft.client.Minecraft.getInstance().player;
		String matchedRuleId = isSelf ? null : MobHighlightRegistry.getMatchedRuleId(entity);
		int highlightColor = matchedRuleId != null ? MobHighlightRegistry.getColorFor(entity) : 0;
		// Real bug found (per user report — "The glow is STILL rendering on starred mobs"): the real render
		// pass this feeds (EntityRenderState.appearsGlowing()) checks state.outlineColor != 0 directly, NOT
		// entity.isCurrentlyGlowing() again — so EntityGlowingMixin's own Hide Glow suppression (below, which
		// only overrides isCurrentlyGlowing()) never touched this completely separate code path at all. This
		// line unconditionally set outlineColor to the highlight color for ANY matched MobHighlightRegistry
		// rule (Starred Mob Highlight, Wither Highlight, etc.), so an entity Hide Glow was actively trying to
		// suppress kept showing its outline anyway, sourced from here instead of real vanilla glow. Same
		// suppression check as EntityGlowingMixin now applies here too, before outlineColor is ever set.
		boolean hideGlow = !isSelf && com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature.shouldHideGlow(entity);
		if (!hideGlow && highlightColor != 0 && com.cokelord.skyblocksimplified.highlight.HighlightRenderModeUtil.wants3dOutline(entity)) {
			state.outlineColor = highlightColor;
		} else if (hideGlow) {
			state.outlineColor = 0;
		}

		int alpha = EntityTransparencyRegistry.getAlphaFor(entity);
		((EntityRenderStateAlphaAccessor) (Object) state).skyblocksimplified$setAlpha(alpha);

		// Damage Truncator: overrides THIS frame's already-extracted nameTag (see the doc comment on
		// DamageTruncatorFeature.computeOverrideName for why it has to happen exactly here, per-frame,
		// instead of on a tick — that's what previously let the raw untruncated number flash for a frame
		// or more before this mod's own tick-based rewrite caught up to it).
		Component truncatedName = DamageTruncatorFeature.computeOverrideName(entity);
		if (truncatedName != null) state.nameTag = truncatedName;

		// Quiz Solver: recolors the correct answer NPC's real overhead nametag red+bold, same per-frame
		// override contract as Damage Truncator above (see QuizSolverFeature.computeOverrideName's own doc
		// comment for why this whole feature was rebuilt around real entity nametags this round).
		Component quizName = QuizSolverFeature.computeOverrideName(entity);
		if (quizName != null) state.nameTag = quizName;
	}
}

@Mixin(Entity.class)
class EntityGlowingMixin {
	@Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
	private void skyblocksimplified$forceGlowForHighlight(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		boolean isSelf = self == net.minecraft.client.Minecraft.getInstance().player;
		// Highlight-forcing stays self-excluded (see the real bug this guarded against in
		// EntityRendererMixin's own doc comment above — a highlight rule matching the local player produces
		// a garbled duplicate silhouette). Glow-HIDING, below, is a separate concern with no such hazard —
		// per explicit user request ("hide player glow should hide your own glow as well"), it applies to
		// self too.
		if (!isSelf) {
			String matchedRuleId = MobHighlightRegistry.getMatchedRuleId(self);
			// Per user request: a dedicated module to hide the real (vanilla/Hypixel-driven) glow on other
			// players/entities. Real bug found (per user report — "Glow seems to apply on a LOT of dungeon
			// mobs now for some reason. Stop the client from ever rendering glow if that module is
			// activated"): the FORCE-glow branch below (which makes an entity targeted by some OTHER
			// highlight feature — Wither Highlight, Starred Mob Highlight, etc. — actually enter the outline
			// render pass) used to run and `return` BEFORE this feature's own suppression check ever got a
			// chance to run, for any entity that wasn't already really glowing. In a dungeon, where several
			// other highlight features are commonly active on many mobs at once, that meant "hide glow" only
			// ever suppressed REAL glow, never the force-glow every other highlight rule relies on — reading
			// as "glow shows on a lot of dungeon mobs" despite the module being on. No more "own rule"
			// exemption (per later user follow-up, "Hide player glow STILL isn't hiding glow on teammates" -
			// PlayerGlowFeature's party highlight no longer uses this 3D force-glow path at all, it's a 2D
			// box now, so every other rule's force-glow (and this one, moot since it never matches anymore)
			// is suppressed FIRST, before it can ever fire.
			if (com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature.shouldHideGlow(self)) {
				cir.setReturnValue(false);
				return;
			}
			int color = matchedRuleId != null ? MobHighlightRegistry.getColorFor(self) : 0;
			if (!cir.getReturnValueZ() && color != 0
				&& com.cokelord.skyblocksimplified.highlight.HighlightRenderModeUtil.wants3dOutline(self)) {
				cir.setReturnValue(true);
			}
			return;
		}
		if (cir.getReturnValueZ() && com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature.shouldHideGlow(self)) {
			cir.setReturnValue(false);
		}
	}
}
