package com.cokelord.skyblocksimplified.feature.impl.m7;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.dungeon.m7.M7Dragons;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.SettingRow;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaHudFeature;
import com.cokelord.skyblocksimplified.highlight.World3DRenderer;
import com.cokelord.skyblocksimplified.highlight.WorldRenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.List;

/**
 * M7 "Dragons" module — Odin's Wither Dragons (timer with timer style, dragon boxes, target tracer, spawn
 * title, ice-spray/spawn/time-alive/"counts" messages, dragon health). Logic lives in {@link M7Dragons};
 * the priority settings are the separate {@link DragonPriorityFeature}.
 */
public class DragonsFeature extends DianaHudFeature implements M7Dragons.Settings {
	private static final String[] TIMER_STYLES = {"Milliseconds", "Seconds", "Ticks"};
	private static DragonsFeature instance;
	private boolean worldHooksRegistered = false;

	public DragonsFeature() {
		super("m7_dragons", "Dragons", FeatureCategory.COMBAT,
			"M7 dragon tools ported from Odin: spawn timer, dragon boxes, a tracer to your priority dragon, spawn titles, "
				+ "ice spray / spawn / time-alive messages and dragon health.", 0.5f, 0.35f);
		defBool("timer", true);
		defInt("timerStyle", 0);
		defBool("timerSymbol", true);
		defBool("boxes", true);
		defBool("tracer", true);
		defBool("title", true);
		defBool("sendSpawned", true);
		defBool("sendTime", true);
		defBool("sendSpray", true);
		defBool("sendCounts", true);
		defBool("health", true);
		instance = this;
		M7Dragons.setSettings(this);
		M7Dragons.register();
	}

	@Override
	public String getSubcategory() { return "Floor 7"; }

	@Override
	protected void onEnable() {
		super.onEnable();
		if (worldHooksRegistered) return;
		worldHooksRegistered = true;
		World3DRenderer.addRenderCallback(() -> {
			if (instance == null || !instance.isEnabled() || !instance.bool("boxes") || !M7Dragons.inP5()) return;
			try {
				for (M7Dragons.Dragon d : M7Dragons.Dragon.values()) {
					if (d.state != M7Dragons.State.DEAD) World3DRenderer.drawWireBox(d.box, d.color, 2f);
				}
			} catch (Exception e) {
				SkyblockSimplified.LOGGER.error("Dragons box render failed", e);
			}
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("skyblocksimplified", "m7_dragons_world"),
			(graphics, tracker) -> {
				if (instance == null || !instance.isEnabled() || !M7Dragons.inP5()) return;
				try {
					instance.renderWorldText(graphics);
				} catch (Exception e) {
					SkyblockSimplified.LOGGER.error("Dragons text render failed", e);
				}
			});
	}

	private void renderWorldText(GuiGraphicsExtractor graphics) {
		if (bool("health")) {
			for (EnderDragon dragon : M7Dragons.dragons.values()) {
				if (dragon.getHealth() > 0) WorldRenderUtil.drawTextNoOcclusion(graphics, M7Dragons.healthText(dragon.getHealth()), dragon.position(), 0xFFFFFFFF);
			}
		}
		for (M7Dragons.Dragon d : M7Dragons.Dragon.values()) {
			if (bool("timer") && d.timeToSpawn > 0) {
				WorldRenderUtil.drawTextNoOcclusion(graphics, "§" + d.colorCode + d.name().charAt(0) + ": "
					+ M7Dragons.timerText(d.timeToSpawn, integer("timerStyle"), bool("timerSymbol")), M7Dragons.center(d.spawnPos), 0xFFFFFFFF);
			}
		}
		M7Dragons.Dragon priority = M7Dragons.priorityDragon;
		if (bool("tracer") && priority != null && priority.state == M7Dragons.State.SPAWNING) {
			WorldRenderUtil.drawTracer(graphics, M7Dragons.center(priority.spawnPos), priority.color, 2);
		}
	}

	// ---- HUD timer (priority dragon) ----
	@Override
	protected List<String> lines() {
		if (!bool("timer")) return List.of();
		M7Dragons.Dragon d = M7Dragons.priorityDragon;
		if (d == null || d.timeToSpawn <= 0) return List.of();
		return List.of("§" + d.colorCode + d.name().charAt(0) + " " + M7Dragons.timerText(d.timeToSpawn, integer("timerStyle"), bool("timerSymbol")));
	}

	@Override
	protected List<String> exampleLines() { return List.of("§5P §a4.5s"); }

	@Override
	protected boolean shouldShow() { return M7Dragons.inP5(); }

	@Override
	public List<SettingRow> getSettingRows() {
		return List.of(
			toggle("Dragon Timer", "timer", "Timer over each spawning dragon's spot, plus the movable HUD timer for your priority dragon."),
			new SettingRow.Cycle("Timer Style", () -> TIMER_STYLES[Math.floorMod(integer("timerStyle"), 3)],
				() -> setInt("timerStyle", (integer("timerStyle") + 1) % 3), "Milliseconds, seconds or ticks."),
			toggle("Timer Symbol", "timerSymbol", "Adds ms / s / t after the number."),
			toggle("Dragon Boxes", "boxes", "Outlines where each dragon spawns (until it dies)."),
			toggle("Target Tracer", "tracer", "Draws a line to your priority dragon while it's spawning."),
			toggle("Dragon Title", "title", "Title when a dragon starts spawning (your priority dragon)."),
			toggle("Send Dragon Spawned", "sendSpawned", "Chat message when a dragon spawns."),
			toggle("Send Dragon Time Alive", "sendTime", "Chat message with how long a dragon was alive when it dies."),
			toggle("Send Ice Sprayed", "sendSpray", "Chat message when a dragon gets ice sprayed, with the ticks since it spawned."),
			toggle("Send Dragon Confirmation", "sendCounts", "Chat message when the Wither King confirms a dragon counted."),
			toggle("Dragon Health", "health", "Shows each dragon's health."));
	}

	// ---- M7Dragons.Settings ----
	@Override public boolean enabled() { return isEnabled(); }
	@Override public boolean dragonTitle() { return bool("title"); }
	@Override public boolean sendSpawned() { return bool("sendSpawned"); }
	@Override public boolean sendTime() { return bool("sendTime"); }
	@Override public boolean sendSpray() { return bool("sendSpray"); }
	@Override public boolean sendCounts() { return bool("sendCounts"); }

	private static DragonPriorityFeature priority() {
		return FeatureRegistry.get("m7_dragon_priority") instanceof DragonPriorityFeature f ? f : null;
	}
	@Override public boolean priorityEnabled() { DragonPriorityFeature p = priority(); return p != null && p.isEnabled(); }
	@Override public int normalPower() { DragonPriorityFeature p = priority(); return p != null ? p.integer("normalPower") : 0; }
	@Override public int easyPower() { DragonPriorityFeature p = priority(); return p != null ? p.integer("easyPower") : 0; }
	@Override public boolean healerSoloDebuff() { DragonPriorityFeature p = priority(); return p != null && p.integer("soloDebuff") == 1; }
	@Override public boolean soloDebuffOnAll() { DragonPriorityFeature p = priority(); return p != null && p.bool("soloDebuffOnAll"); }
}
