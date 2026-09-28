package com.cokelord.skyblocksimplified.diana;

import com.cokelord.skyblocksimplified.api.HypixelElectionApi;
import com.cokelord.skyblocksimplified.diana.pf.SboPartyFinder;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.impl.diana.ArrowGuessFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.CloseBurrowDetectionFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.CocoonNotifierFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaColorsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaLootTrackerFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaMobTrackerFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaStatsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaWarpFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.DianaWaypointsFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.MythosMobHpFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.NoShurikenFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobHighlightFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobReceiveFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobScanFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.RareMobShareFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.SboPartyFinderFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.SpadeGuessFeature;
import com.cokelord.skyblocksimplified.feature.impl.diana.SphinxSolverFeature;
import com.cokelord.skyblocksimplified.particle.ParticlePacketObserverRegistry;
import com.cokelord.skyblocksimplified.util.ChatText;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * Events > Diana: registers the modules (menu order) and the shared listeners that feed the Diana classes.
 * Ported from SBO (SkyblockOverhaul/SBO, Apache-2.0); the arrow guess and ray math originate from SkyHanni
 * (credited in {@link ArrowGuess}).
 */
public final class DianaModule {
	private static int tickCount = 0;

	private DianaModule() {}

	public static void register() {
		FeatureRegistry.register(new SpadeGuessFeature());
		FeatureRegistry.register(new ArrowGuessFeature());
		FeatureRegistry.register(new CloseBurrowDetectionFeature());
		FeatureRegistry.register(new DianaWaypointsFeature());
		FeatureRegistry.register(new DianaWarpFeature());
		FeatureRegistry.register(new DianaLootTrackerFeature());
		FeatureRegistry.register(new DianaMobTrackerFeature());
		FeatureRegistry.register(new DianaStatsFeature());
		FeatureRegistry.register(new RareMobShareFeature());
		FeatureRegistry.register(new RareMobReceiveFeature());
		FeatureRegistry.register(new RareMobScanFeature());
		FeatureRegistry.register(new RareMobHighlightFeature());
		FeatureRegistry.register(new CocoonNotifierFeature());
		FeatureRegistry.register(new NoShurikenFeature());
		FeatureRegistry.register(new MythosMobHpFeature());
		FeatureRegistry.register(new SphinxSolverFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.diana.DianaRollFeature());
		FeatureRegistry.register(new SboPartyFinderFeature());
		FeatureRegistry.register(new DianaColorsFeature());

		HypixelElectionApi.start();
		DianaData.load();
		DianaWaypoints.register();
		DianaEvents.register();
		DianaMobs.register();
		DianaRoll.register();
		SboPartyFinder.register();

		ParticlePacketObserverRegistry.register(event -> {
			SpadeGuess.onParticle(event);
			ArrowGuess.onParticle(event);
			BurrowDetector.onParticle(event);
		});

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay) return true;
			String legacy = ChatText.legacy(message);
			try {
				DianaEvents.onChat(legacy);
				BurrowDetector.onChat(legacy);
				DianaTracker.onChat(legacy);
				DianaMobs.onChat(legacy);
				RareMobs.onChat(legacy);
				if (ChatText.strip(legacy).startsWith("[Sacks]")) DianaTracker.onSacksMessage(message);
				SboPartyFinder.onChat(legacy);
				if (DianaRoll.onChat(message, legacy)) return false;
				return SphinxSolver.onChat(message, legacy);
			} catch (Exception e) {
				com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Diana chat handler failed", e);
				return true;
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(DianaModule::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			DianaData.save();
			SpadeGuess.reset();
			DianaTracker.resetInventorySnapshot();
			SboPartyFinder.onDisconnect();
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var rollCmd = ClientCommands.literal("dianaroll");
			for (RareMobs.Mob mob : RareMobs.Mob.values()) {
				String arg = switch (mob) { case INQ -> "inquisitor"; case KING -> "king"; case MANTI -> "manticore"; case SPHINX -> "sphinx"; };
				rollCmd.then(ClientCommands.literal(arg)
					.executes(ctx -> { DianaRoll.test(mob, null); return 1; })
					.then(ClientCommands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
						.executes(ctx -> {
							String q = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item");
							DianaDropTables.Drop drop = DianaDropTables.byName(mob, q);
							if (drop == null) {
								ChatText.clientMessage("§b[SBS] §cNo drop matching \"" + q + "\" for " + mob.displayName + ".");
								return 0;
							}
							DianaRoll.test(mob, drop);
							return 1;
						})));
			}
			dispatcher.register(rollCmd);
			for (String name : new String[]{"sbsclearburrows", "sbscb"}) {
				dispatcher.register(ClientCommands.literal(name).executes(ctx -> {
					DianaWaypoints.clearAll();
					BurrowDetector.clear();
					ArrowGuess.clear();
					ChatText.clientMessage("§b[SBS] §cBurrow waypoints cleared!");
					return 1;
				}));
			}
		});
	}

	private static void tick(Minecraft mc) {
		DianaData.tick();
		DianaRoll.tick();
		if (mc.player == null || mc.level == null) return;
		tickCount++;
		try {
			DianaWaypoints.tick(mc);
			ArrowGuess.tick(mc);
			DianaMobs.tick(mc);
			RareMobs.tick(mc);
			if (tickCount % 5 == 0 && DianaState.inHub()) DianaTracker.inventoryTick(mc);
		} catch (Exception e) {
			com.cokelord.skyblocksimplified.SkyblockSimplified.LOGGER.error("Diana tick failed", e);
		}
	}
}
