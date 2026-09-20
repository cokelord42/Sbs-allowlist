package com.cokelord.skyblocksimplified.client;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.cokelord.skyblocksimplified.api.BazaarApi;
import com.cokelord.skyblocksimplified.api.SkyblockItemRepo;
import com.cokelord.skyblocksimplified.config.ConfigManager;
import com.cokelord.skyblocksimplified.feature.FeatureRegistry;
import com.cokelord.skyblocksimplified.feature.FeatureCategory;
import com.cokelord.skyblocksimplified.combat.DamageSplashDetector;
import com.cokelord.skyblocksimplified.dungeon.DungeonChatFilter;
import com.cokelord.skyblocksimplified.dungeon.DungeonObjectPredicates;
import com.cokelord.skyblocksimplified.feature.impl.AbilityCooldownTimerFeature;
import com.cokelord.skyblocksimplified.feature.impl.ActiveHotfPerksHighlightFeature;
import com.cokelord.skyblocksimplified.feature.impl.ArmorStackDisplayFeature;
import com.cokelord.skyblocksimplified.feature.impl.AuctionHouseTotalFeature;
import com.cokelord.skyblocksimplified.feature.impl.AuctionTimersFeature;
import com.cokelord.skyblocksimplified.feature.impl.BonusPestChanceFeature;
import com.cokelord.skyblocksimplified.feature.impl.CroesusFeature;
import com.cokelord.skyblocksimplified.feature.impl.CustomEnchantParsingFeature;
import com.cokelord.skyblocksimplified.feature.impl.CustomLoadoutKeybindsFeature;
import com.cokelord.skyblocksimplified.feature.impl.DnaAnalyzerSolverFeature;
import com.cokelord.skyblocksimplified.feature.impl.DungeonClickedBlocksFeature;
import com.cokelord.skyblocksimplified.feature.impl.EntityHideFeature;
import com.cokelord.skyblocksimplified.feature.impl.EntityTransparencyFeature;
import com.cokelord.skyblocksimplified.feature.impl.ExperimentAddonsFeature;
import com.cokelord.skyblocksimplified.feature.impl.ExperimentationTimersFeature;
import com.cokelord.skyblocksimplified.feature.impl.HideLoadingScreenFeature;
import com.cokelord.skyblocksimplified.feature.impl.FarmingFortuneDisplayFeature;
import com.cokelord.skyblocksimplified.feature.impl.FarmingLaneDetectionFeature;
import com.cokelord.skyblocksimplified.feature.impl.FlareDisplayFeature;
import com.cokelord.skyblocksimplified.feature.impl.GuiAnimationsFeature;
import com.cokelord.skyblocksimplified.feature.impl.GuiColorFeature;
import com.cokelord.skyblocksimplified.feature.impl.PanelThemeFeature;
import com.cokelord.skyblocksimplified.feature.impl.HotfLevelStackFeature;
import com.cokelord.skyblocksimplified.feature.impl.InstanceChestProfitFeature;
import com.cokelord.skyblocksimplified.feature.impl.ItemPickupLogFeature;
import com.cokelord.skyblocksimplified.feature.impl.JacobsContestFeature;
import com.cokelord.skyblocksimplified.feature.impl.KeybindOverrideFeature;
import com.cokelord.skyblocksimplified.feature.impl.LividFinderFeature;
import com.cokelord.skyblocksimplified.feature.impl.MobHighlightFeature;
import com.cokelord.skyblocksimplified.feature.impl.MoneyPerHourFeature;
import com.cokelord.skyblocksimplified.feature.impl.MoongladeBeaconAlertFeature;
import com.cokelord.skyblocksimplified.feature.impl.NametagGlyphIndicatorFeature;
import com.cokelord.skyblocksimplified.feature.impl.OpenGuiFeature;
import com.cokelord.skyblocksimplified.feature.impl.PetDisplayFeature;
import com.cokelord.skyblocksimplified.feature.impl.PersonalCompactorOverlayFeature;
import com.cokelord.skyblocksimplified.feature.impl.PestCooldownFeature;
import com.cokelord.skyblocksimplified.feature.impl.PestSpawnAlertFeature;
import com.cokelord.skyblocksimplified.feature.impl.RareRewardWarningFeature;
import com.cokelord.skyblocksimplified.feature.impl.SimpleToggleFeature;
import com.cokelord.skyblocksimplified.feature.impl.SlayerCocoonAlertFeature;
import com.cokelord.skyblocksimplified.feature.impl.ParticleFilterFeature;
import com.cokelord.skyblocksimplified.feature.impl.SoundMuteFeature;
import com.cokelord.skyblocksimplified.feature.impl.TreeProgressDisplayFeature;
import com.cokelord.skyblocksimplified.feature.impl.VisitorShoppingListFeature;
import com.cokelord.skyblocksimplified.feature.impl.VisitorTimerFeature;
import com.cokelord.skyblocksimplified.keybind.CustomKeybindRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class SkyblockSimplifiedClient implements ClientModInitializer {
	private static final String[] OPEN_GUI_COMMANDS = {"sbs", "sbsimplified", "skyblocksimplified"};

	@Override
	public void onInitializeClient() {
		OpenGuiFeature openGuiFeature = new OpenGuiFeature();

		FeatureRegistry.register(openGuiFeature);
		FeatureRegistry.register(new GuiColorFeature());
		FeatureRegistry.register(new PanelThemeFeature());
		FeatureRegistry.register(new GuiAnimationsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.RememberLastPageFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.ConfigExportImportFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.UiSoundEffectsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.UpdateModuleFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.AutoUpdateOnCloseFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.ModInformationFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.NeuStyleButtonsFeature());
		com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature gyroHelper = new com.cokelord.skyblocksimplified.feature.impl.GyroHelperFeature();
		com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature melodyDisplay = new com.cokelord.skyblocksimplified.feature.impl.MelodyDisplayFeature();
		com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature lavaToWater = new com.cokelord.skyblocksimplified.feature.impl.LavaToWaterFeature();
		FeatureRegistry.register(gyroHelper);
		FeatureRegistry.register(melodyDisplay);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideLightningBoltsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideXpOrbsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideP5TentaclesFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideNametagsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HidePlayersDuringTerminalsFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HighlightPartiesFeature());
		FeatureRegistry.register(lavaToWater);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.ArrowHitSoundFeature());
		// Real vanilla sound events reused by Hypixel for the Bonzo Staff's launch/explosion (per user
		// request — the three exact ids the user provided).
		// Real bug found (per user report — "I cant find the disable bonzo staff sounds module"): a null
		// subcategory here means getSubcategory() returns null, but MainScreen's module-list filter requires
		// an EXACT match against whichever subcategory tab is selected (Objects.equals(f.getSubcategory(),
		// selectedSubcategory)) — for a category with real subcategory tabs (like COMBAT), null can never
		// match any of them, so the feature silently never renders under ANY tab. Explicit "Combat" here
		// matches ArrowHitSoundFeature's own explicit override, the working pattern already used for a
		// same-category sound module.
		FeatureRegistry.register(new SoundMuteFeature("mute_bonzo_staff", "Disable Bonzo Staff Sounds", FeatureCategory.COMBAT, "Combat",
			instance -> {
				String path = instance.getIdentifier().getPath();
				return path.equals("entity.firework_rocket.blast") || path.equals("entity.firework_rocket.twinkle") || path.equals("entity.ghast.ambient");
			}));
		// Per user request ("This should update everytime we release the mod to GitHub. So for example now
		// we should add all the new modules since update 1.0.22 since that's the latest release on GitHub"):
		// the New Modules subcategory (folded into ABOUT — see FeatureCategory's own doc comment) is
		// re-curated per actual git history each round; gyroHelper/melodyDisplay/lavaToWater above predate
		// 1.0.22 and no longer get a mirror here — see registerModuleFeatures() for the current picks.
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.ChatCopyFeature());
		registerHighlightFeatures();
		registerModuleFeatures();
		FeatureRegistry.applyDefaultStates();
		ConfigManager.load();

		// Always-on infra (like ConfigManager), not a toggleable Feature: background price/repo data
		// backing every current and future item-price/profit feature (Bazaar prices, item names/NPC
		// sell prices), fetched from Hypixel's own public, keyless API endpoints.
		BazaarApi.start();
		SkyblockItemRepo.start();
		com.cokelord.skyblocksimplified.api.AuctionApi.start();
		com.cokelord.skyblocksimplified.api.PetAuctionApi.start();
		com.cokelord.skyblocksimplified.api.UpdateApi.start();
		com.cokelord.skyblocksimplified.api.GitHubReleaseApi.start();
		com.cokelord.skyblocksimplified.api.JacobContestApi.start();
		// Real bug found: SkyHanniScoreboardStats already reads HypixelElectionApi.currentMayor() (and the
		// new Dungeon Score Calculator needs it too, for the Paul/EzPz +10 perk bonus), but nothing ever
		// called start() to begin the actual background fetch — currentMayor() was silently always null.
		com.cokelord.skyblocksimplified.api.HypixelElectionApi.start();
		// Also always-on infra: the shared dungeon chat-filter listener (reads its 5 toggle features by
		// id — see DungeonChatFilter's doc comment for why one listener owns this).
		DungeonChatFilter.register();
		// Always-on infra: watches chat to track current party membership (used by Highlight Party Members).
		com.cokelord.skyblocksimplified.party.PartyApi.register();
		// Always-on infra: shared tooltip listener for Hide Vanilla Enchants / Hide Enchant Description.
		com.cokelord.skyblocksimplified.item.EnchantTooltipFilter.register();
		// Always-on infra: shared tooltip listener for Evolving Items / Personal Compactor / Estimated Item Value.
		com.cokelord.skyblocksimplified.item.InventoryTooltipFilter.register();
		// Always-on infra: captures the real per-frame view/projection matrices from the 3D world render
		// pass, for HighlightBoxRenderer's screen-space projection (see WorldToScreen's doc comment).
		com.cokelord.skyblocksimplified.highlight.WorldToScreen.register();
		// Always-on infra: draws the screen-space box for any highlight feature set to a 2D render mode.
		com.cokelord.skyblocksimplified.highlight.HighlightBoxRenderer.register();
		// Always-on infra: the once-per-session bottom-right "update available" toast.
		com.cokelord.skyblocksimplified.gui.UpdateToastRenderer.register();
		// Always-on infra (Odin port): dungeon room/door detection from chunk scanning + the held map item,
		// and floor/boss/teammate/secrets run-state — nearly every ported dungeon feature below reads from
		// these. Order matters: WorldScan.register() internally drives DungeonState.tick()/MapScan.tick()
		// each client tick, so DungeonState.register() (chat-based tracking) just needs to run once too.
		com.cokelord.skyblocksimplified.dungeon.map.WorldScan.register();
		com.cokelord.skyblocksimplified.dungeon.DungeonState.register();
		com.cokelord.skyblocksimplified.dungeon.SelfClassCache.register();
		// Always-on infra: "which floor/mode is the party currently queued for" — extracted out of
		// AutoKickFeature so Party Finder Stats can also read it regardless of whether Auto-Kick is enabled.
		com.cokelord.skyblocksimplified.dungeon.DungeonQueueDetector.register();
		// Always-on infra: hard-resets IslandGate's sticky area cache on disconnect, so island-gated HUD
		// elements (dungeon/garden/kuudra/crimson overlays) can't stay stuck showing after the player leaves.
		com.cokelord.skyblocksimplified.util.IslandGate.register();
		com.cokelord.skyblocksimplified.util.TpsMonitor.register();
		com.cokelord.skyblocksimplified.util.RealPingMonitor.register();
		// Always-on infra: ticks the boss-proximity scan Hide Damage Splashes reads once per tick instead of
		// once per splash entity per render frame (see BossProximityDetector's own doc comment).
		com.cokelord.skyblocksimplified.combat.BossProximityDetector.register();
		// Always-on infra: registers "/termsim [ping]" (offline practice terminal) and the click-simulation
		// tick loop it needs — harmless to register even if the player never runs the command.
		com.cokelord.skyblocksimplified.dungeon.TermSimController.register();
		// Always hides vanilla's persistent top-right potion-effect icon HUD element, same unconditional
		// treatment as EffectsInInventoryMixin gives the inventory-screen effects column — the two are
		// separate vanilla systems (this one renders during normal gameplay, not just inside a screen).
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.removeElement(
			net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MOB_EFFECTS);
		// Always-on infra: hides the ambient particles a dropped blessing emits whenever "Hide Blessings" is on.
		com.cokelord.skyblocksimplified.dungeon.BlessingParticleFilter.register();
		com.cokelord.skyblocksimplified.dungeon.SuperboomTntParticleFilter.register();
		// Always-on infra: watches the player's own inventory for real Superboom TNT/Infiniboom usage,
		// independent of whether the server happens to spawn a detectable entity for it (see the class's own
		// doc comment for the real bug this covers).
		com.cokelord.skyblocksimplified.dungeon.SuperboomUseTracker.register();
		// Per user request: CTRL+F item/lore search in any container GUI, always on (not a toggleable module).
		com.cokelord.skyblocksimplified.gui.GuiSearchOverlay.register();

		ClientTickEvents.END_CLIENT_TICK.register(FeatureRegistry::tickAll);
		// Real bug found (per user report — Equipment/Pet Display caches "reset on restart"): see
		// ConfigManager.saveBlocking()'s own doc comment — the async save() used everywhere else runs on a
		// daemon thread the JVM won't wait for on exit, so a shutdown-only save needs the blocking variant.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ConfigManager.saveBlocking());
		// Per user report ("The sound doesnt seem to be fully resetting when exiting the game, i can hear
		// it for a split second when rejoining the game"): a custom-sound Clip still mid-playback at JVM
		// exit was never told to stop, only ever closed by its own natural-completion listener.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> com.cokelord.skyblocksimplified.sound.CustomSoundOption.stopAll());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			for (String name : OPEN_GUI_COMMANDS) {
				var builder = ClientCommands.literal(name).executes(ctx -> {
					SkyblockSimplified.LOGGER.info("/{} executed", name);
					net.minecraft.client.Minecraft client = ctx.getSource().getClient();
					openGuiFeature.open(client);
					return 1;
				});
				// Per user request: "/sbs ep|sl|sb" refill the player's held stacks of Ender Pearl/Spirit
				// Leap/Superboom TNT up to a full stack via "/gfs", ported from devonian's own real
				// RefillGFSCommands.kt (confirmed real skyblock ids, gfs item-name spelling, and per-slot
				// "top up every partial stack you're already holding" math — not just a single stack).
				builder.then(ClientCommands.literal("ep").executes(ctx -> { sendGfsRefill("ENDER_PEARL", "ender pearl", 16); return 1; }));
				builder.then(ClientCommands.literal("sl").executes(ctx -> { sendGfsRefill("SPIRIT_LEAP", "spirit leap", 16); return 1; }));
				builder.then(ClientCommands.literal("sb").executes(ctx -> { sendGfsRefill("SUPERBOOM_TNT", "superboom tnt", 64); return 1; }));
				dispatcher.register(builder);
			}
			SkyblockSimplified.LOGGER.info("Registered open-gui chat commands: {}", (Object) OPEN_GUI_COMMANDS);

			// Per user request: real personal /f1-/f7, /m1-/m7, /t1-/t5 slash commands (not just the
			// party-chat !f1 triggers ChatCommandsFeature already had) — gated on that same feature being
			// enabled and its own "f1-f7/m1-m7/t1-t5" sub-toggle, so turning Chat Commands off also turns
			// these off, exactly like every other sub-toggle already does for the chat-triggered versions.
			for (String cmd : com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature.INSTANCE_JOIN_COMMANDS) {
				dispatcher.register(ClientCommands.literal(cmd).executes(ctx -> {
					net.minecraft.client.Minecraft client = ctx.getSource().getClient();
					com.cokelord.skyblocksimplified.feature.Feature f = FeatureRegistry.get("chat_commands");
					if (!(f instanceof com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature ccf)
						|| !ccf.isEnabled() || !ccf.isQueInstance()) return 1;
					String id = com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature.instanceIdFor(cmd);
					if (id.isEmpty() || client.player == null || client.player.connection == null) return 1;
					client.player.connection.sendCommand("joininstance " + id);
					return 1;
				}));
			}

			// Per user request ("The command aliases module doesnt work... Its very alike the odin chat
			// commands we added") — see CommandAliasesFeature.singleWordTriggerSnapshot's own doc comment for
			// why single-word triggers get this same real-command treatment instead of relying solely on the
			// MODIFY_COMMAND rewrite. Read fresh here (registration only happens at login) rather than off
			// whatever local variable this method may have captured earlier, matching the chat_commands
			// lookup just above.
			com.cokelord.skyblocksimplified.feature.Feature aliasFeature = FeatureRegistry.get("command_aliases");
			if (aliasFeature instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature caf) {
				for (String trigger : caf.singleWordTriggerSnapshot()) {
					dispatcher.register(ClientCommands.literal(trigger).executes(ctx -> {
						net.minecraft.client.Minecraft client = ctx.getSource().getClient();
						com.cokelord.skyblocksimplified.feature.Feature f = FeatureRegistry.get("command_aliases");
						if (!(f instanceof com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature current) || !current.isEnabled()) return 1;
						String replacement = current.findReplacementForTrigger(trigger);
						if (replacement == null || client.player == null || client.player.connection == null) return 1;
						client.player.connection.sendCommand(replacement);
						return 1;
					}));
				}
			}
		});

		SkyblockSimplified.LOGGER.info("SkyblockSimplified client initialized");
	}

	/** Counts every inventory slot currently holding {@code skyblockId} (matched via
	 *  {@link com.cokelord.skyblocksimplified.util.SkyblockNbtUtils#getItemId}), then sends "/gfs
	 *  &lt;gfsName&gt; N" for exactly enough to top every one of those slots up to {@code stackSize} — ported
	 *  verbatim from devonian's real RefillGFSCommands.kt math (a slot with none at all still counts as one
	 *  slot to fill, not zero, so "/sbs sb" from an empty inventory fills exactly one stack). */
	private static void sendGfsRefill(String skyblockId, String gfsName, int stackSize) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.player == null) return;
		int stacks = 0;
		int amount = 0;
		for (net.minecraft.world.item.ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
			if (!skyblockId.equals(com.cokelord.skyblocksimplified.util.SkyblockNbtUtils.getItemId(stack))) continue;
			stacks++;
			amount += stack.getCount();
		}
		if (amount == 0) stacks = 1;
		int remaining = stacks * stackSize - amount;
		if (remaining > 0 && client.player.connection != null) {
			client.player.connection.sendCommand("gfs " + gfsName + " " + remaining);
		}
	}

	/**
	 * Mob highlighting, built on MobHighlightRegistry/EntityRendererMixin. Matching is done by
	 * substring against entity.getName() (falls back to the entity's default name if no custom name is
	 * set, unlike getCustomName() which the old version of this used and could return null). Boss and
	 * miniboss names are now confirmed from SkyHanni 7.35's SlayerType.kt (previous version had them
	 * backwards — "Highlight Minibosses" was matching boss names instead of actual miniboss names — and
	 * had a wrong name for Vampire, which Hypixel renamed from "Riftstalker Bloodfiend" to "Bloodfiend").
	 */
	private static void registerHighlightFeatures() {
		// subcategory was null here, but COMBAT already had non-empty subcategories (Slayers/Dungeons/
		// Kuudra/Crimson Isle) — the GUI only shows a feature when its subcategory exactly matches the
		// selected tab, so these were silently invisible in every tab. Moved into the new "Combat" tab.
		FeatureRegistry.register(new MobHighlightFeature("zealot_highlight", "Highlight Zealots",
			FeatureCategory.COMBAT, "Combat", name -> name.contains("Zealot"), 0xFFB833FF));
		FeatureRegistry.register(new MobHighlightFeature("arachne_highlight", "Highlight Arachne",
			FeatureCategory.COMBAT, "Combat", name -> name.contains("Arachne"), 0xFF8B00FF));

		// Real vanilla particle types for the big explosion cloud (ClientLevel.doAddParticle choke point).
		FeatureRegistry.register(new ParticleFilterFeature("explosions_hider", "Explosions Hider", FeatureCategory.COMBAT, "Combat",
			options -> options.getType() == net.minecraft.core.particles.ParticleTypes.EXPLOSION
				|| options.getType() == net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER));

		// Split from a single bundled "Highlight Minibosses" toggle (one shared color for all 19 names
		// across every slayer type) into one toggle per slayer type, each with its own default color and
		// slayerType tag — per user report ("the highlight on different mobs needs to be different
		// depending on the mob"), matching the pattern already used for the actual boss highlights below.
		FeatureRegistry.register(new MobHighlightFeature("revenant_miniboss_highlight", "Highlight Minibosses",
			FeatureCategory.COMBAT, "Slayers", "Revenant",
			name -> containsAny(name, new String[]{"Revenant Sycophant", "Revenant Champion", "Deformed Revenant", "Atoned Champion", "Atoned Revenant"}),
			0xFFAAFFAA));
		FeatureRegistry.register(new MobHighlightFeature("tarantula_miniboss_highlight", "Highlight Minibosses",
			FeatureCategory.COMBAT, "Slayers", "Tarantula",
			name -> containsAny(name, new String[]{"Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula", "Primordial Jockey", "Primordial Viscount"}),
			0xFFFFAA00));
		FeatureRegistry.register(new MobHighlightFeature("sven_miniboss_highlight", "Highlight Minibosses",
			FeatureCategory.COMBAT, "Slayers", "Sven",
			name -> containsAny(name, new String[]{"Pack Enforcer", "Sven Follower", "Sven Alpha"}),
			0xFFFFFFFF));
		FeatureRegistry.register(new MobHighlightFeature("voidgloom_miniboss_highlight", "Highlight Minibosses",
			FeatureCategory.COMBAT, "Slayers", "Voidgloom",
			name -> containsAny(name, new String[]{"Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac"}),
			0xFFAA55FF));
		FeatureRegistry.register(new MobHighlightFeature("blaze_miniboss_highlight", "Highlight Minibosses",
			FeatureCategory.COMBAT, "Slayers", "Blaze",
			name -> containsAny(name, new String[]{"Flare Demon", "Kindleheart Demon", "Burningsoul Demon"}),
			0xFFFFAA55));

		// Each of these carries a slayerType tag so it only shows under its own Combat > Slayers > <type> tab.
		FeatureRegistry.register(new MobHighlightFeature("tarantula_boss_highlight", "Highlight Tarantula boss",
			FeatureCategory.COMBAT, "Slayers", "Tarantula", name -> name.contains("Tarantula Broodfather"), 0xFF55FF55));
		FeatureRegistry.register(new MobHighlightFeature("voidgloom_boss_highlight", "Highlight Voidgloom boss",
			FeatureCategory.COMBAT, "Slayers", "Voidgloom", name -> name.contains("Voidgloom Seraph"), 0xFF55FFFF));
		FeatureRegistry.register(new MobHighlightFeature("blaze_boss_highlight", "Highlight Blaze boss",
			FeatureCategory.COMBAT, "Slayers", "Blaze", name -> name.contains("Inferno Demonlord"), 0xFFFF5555));
		FeatureRegistry.register(new MobHighlightFeature("vampire_boss_highlight", "Highlight Vampire boss",
			FeatureCategory.COMBAT, "Slayers", "Vampire", name -> name.contains("Bloodfiend"), 0xFFFF55FF));
		FeatureRegistry.register(new MobHighlightFeature("revenant_boss_highlight", "Highlight Revenant boss",
			FeatureCategory.COMBAT, "Slayers", "Revenant", name -> name.contains("Revenant Horror"), 0xFF55AA55));
		FeatureRegistry.register(new MobHighlightFeature("sven_boss_highlight", "Highlight Sven boss",
			FeatureCategory.COMBAT, "Slayers", "Sven", name -> name.contains("Sven Packmaster"), 0xFFCCCCCC));

		// Combat > Slayers > Voidgloom — real vanilla Enderman death-flop rotation + two sound sub-toggles,
		// see DisableEndermanDeathAnimationFeature/EndermanDeathFlopMixin's own doc comments for the
		// confirmed vanilla sound ids and render-state mechanism.
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.DisableEndermanDeathAnimationFeature());
	}

	private static boolean containsAny(String name, String[] candidates) {
		for (String candidate : candidates) {
			if (name.contains(candidate)) return true;
		}
		return false;
	}

	/**
	 * The rest of the requested module list. Nearly everything is real, fully-working logic ported
	 * from SkyHanni's Kotlin source (rewritten in Java against this project's own APIs, not copied) —
	 * tab-list widget text (via TabListReader), real chat/action-bar messages, confirmed vanilla sound
	 * paths and repo skull-texture data, entity nametag/damage-splash detection (via the new
	 * EntityHideRegistry), a full inventory-puzzle solver (DnaAnalyzerSolverFeature), real client-side
	 * crop-block-break tracking (CropBreakTracker) backing Money/Hour Display and Farming Lane
	 * Detection, and the NEU-style price/repo API (BazaarApi/SkyblockItemRepo) backing every
	 * value-estimate feature.
	 */
	private static void registerModuleFeatures() {
		// Farming > Events — Real: contest schedule from the same public API SkyHanni uses
		// (api.eliteskyblock.com/contests/at/now — see JacobContestApi), not the old dead tab-list read.
		FeatureRegistry.register(new JacobsContestFeature());

		// Inventory (top-level category) — Hide Loading Screen is real (closes the vanilla dirt/loading
		// screen the instant it opens), same toggle SkyblockAddons had on 1.8.9.
		FeatureRegistry.register(new HideLoadingScreenFeature());
		// "dont_reset_cursor_between_inventories" now registered by registerOdinSkyblockFeatures() below,
		// as NoCursorResetFeature — ported from Odin's NoCursorReset.kt, replacing the previous implementation.

		// Inventory > Enchantments — real: ItemTooltipCallback-based lore filtering (EnchantTooltipFilter),
		// see its own doc comment for the confirmed vanilla-enchant-line and description-line detection.
		FeatureRegistry.register(new SimpleToggleFeature("hide_vanilla_enchants", "Hide Vanilla Enchants Under Item Name", FeatureCategory.INVENTORY, "Enchantments"));
		FeatureRegistry.register(new SimpleToggleFeature("hide_enchant_description", "Hide Enchant Descriptions", FeatureCategory.INVENTORY, "Enchantments"));
		FeatureRegistry.register(new CustomEnchantParsingFeature());
		// Real: "Manage Auctions" title + "§7Status: §aSold!" lore line, ported from SkyHanni's AuctionsHighlighter.kt.
		FeatureRegistry.register(new AuctionHouseTotalFeature());
		// Inventory > Misc — per user request ("I need it to display at the bottom of an auction what day and
		// time it ends... Make it a module aswell"): see AuctionTimersFeature's own doc comment.
		FeatureRegistry.register(new AuctionTimersFeature());

		// Inventory > Tooltips — real: InventoryTooltipFilter (shared ItemTooltipCallback listener, same
		// pattern as EnchantTooltipFilter).
		// Real: "baseStatBoostPercentage"/"item_tier" ExtraAttributes, confirmed via both Devonian's
		// DungeonItemStats.kt (which labels item_tier "Floor" in its own tooltip line — matched verbatim
		// here) and SkyOcean's DungeonQualityLoreModifier.kt (same 0-50 quality scale, cross-confirming it
		// independently). See InventoryTooltipFilter's own doc comment for why this lives there.
		FeatureRegistry.register(new SimpleToggleFeature("show_item_quality", "Show Item Quality (dungeon floor + quality on drops)", FeatureCategory.INVENTORY, "Inventory"));
		FeatureRegistry.register(new PersonalCompactorOverlayFeature());


		// Inventory > Cooldowns — real (scoped): action-bar mana-cost ability detection, ported from
		// SkyHanni's confirmed abilityuse RepoPattern; see AbilityCooldownTimerFeature's doc comment for
		// exactly which items' cooldown durations are in the (deliberately small, high-confidence) table.
		FeatureRegistry.register(new AbilityCooldownTimerFeature());

		// Inventory > Storage — real (scoped): per-tick own-inventory diffing, ported from SkyHanni's
		// ItemPickupLog.kt core mechanism (see its own doc comment for what's deliberately left out).
		FeatureRegistry.register(new ItemPickupLogFeature());

		// Inventory > Misc — per user request ("Add a 'Show currently selected pet' module"): reads
		// Hypixel's own "Pet: [Lvl N] Name" tab-list line, same tab-widget data source
		// FarmingFortuneDisplayFeature already reads its own lines from. Real recent addition per git
		// history since the 1.0.22 GitHub release (see FeatureCategory's doc comment on the New Modules
		// subcategory move), so it also gets a mirror there.
		PetDisplayFeature petDisplay = new PetDisplayFeature();
		FeatureRegistry.register(petDisplay);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(petDisplay, FeatureCategory.ABOUT, "New Modules"));

		// Inventory > Loadouts — real: confirmed against SkyHanni's LoadoutApi.kt/CustomLoadoutKeybinds.kt
		// (the new 2026 Hypixel Loadouts menu), see CustomLoadoutKeybindsFeature's doc comment.
		FeatureRegistry.register(new CustomLoadoutKeybindsFeature());

		// Inventory > Pets — per user request ("Add Pet Keybinds. It should detect the pets menu like the
		// pet display is currently doing, add keybinds for 1-8"): same real-keydown architecture as
		// CustomLoadoutKeybindsFeature above, see PetKeybindsFeature's own doc comment.
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.PetKeybindsFeature());

		// Enchanting — Experimentation Table modules, all built on ExperimentationTableApi (GUI-title
		// detection, completed-run/ultra-rare-uncover callbacks — see its own doc comment for the
		// confirmed regexes and the hash+delay dedup trick ported from SkyHanni's own Api object). Prevent
		// Misclicks and Max Clicks Alert are plain toggles ExperimentAddonsFeature reads by id, same
		// pattern as Croesus Chest Overlay's sub-toggles.
		FeatureRegistry.register(new ExperimentAddonsFeature());
		FeatureRegistry.register(new SimpleToggleFeature("experiment_addons_prevent_misclicks", "Prevent Misclicks", FeatureCategory.ENCHANTING, null));
		FeatureRegistry.register(new SimpleToggleFeature("experiment_addons_max_clicks_alert", "Max Clicks Alert", FeatureCategory.ENCHANTING, null));
		// Per user request ("Can actually also make one for experiments... notify them that they have an
		// experiment charge"): see ExperimentationTimersFeature's own doc comment.
		FeatureRegistry.register(new ExperimentationTimersFeature());

		// Farming > Farming — Farming Lane Detection and Money/Hour Display are both real now, built on
		// two new pieces of infra: CropBreakTracker (real client-side crop-block-break events via
		// Fabric's PlayerBlockBreakEvents) and a "/sbarlane" detection state machine ported from
		// SkyHanni's FarmingLaneCreator.kt. Both are scoped approximations — see their own doc comments
		// for exactly what's simplified (no Farming Fortune yield multiplier, no dedicated speed tracker).
		FeatureRegistry.register(new FarmingLaneDetectionFeature());
		FeatureRegistry.register(new MoneyPerHourFeature());
		// Real: tab-list universal Farming Fortune stat, ported from SkyHanni's FarmingFortuneDisplay.kt.
		FeatureRegistry.register(new FarmingFortuneDisplayFeature());
		// "Remove Farming Particles" was removed per user request: it never fully covered every case,
		// and Sodium Extra's own particle-reduction options already cover this same ground more reliably
		// (something already loaded for most players), making this a pure waste of relatively expensive
		// per-particle predicate checks for functionality that's redundant with it.

		// Real Hypixel pest entity display names (PestType.kt) — reuses the same MobHighlightFeature
		// infra (2D/2D Full/3D/3D Full render modes + color picker) every other mob highlight already
		// has, applied to pest mobs specifically per user request.
		String[] pestNames = {
			"Beetle", "Cricket", "Earthworm", "Field Mouse", "Fly", "Locust", "Lunar Moth",
			"Mite", "Mosquito", "Moth", "Rat", "Slug", "Praying Mantis", "Firefly", "Dragonfly",
		};
		// Garden-gated: pest-named mobs like "Rat" also exist as ordinary Hub/other-island vermin, so a
		// name-only predicate was highlighting them everywhere, not just real Garden pests.
		MobHighlightFeature pestHighlight = new MobHighlightFeature("pest_highlight", "Highlight Pests",
			FeatureCategory.FARMING, "Pest farming",
			name -> com.cokelord.skyblocksimplified.util.IslandGate.isInGarden() && containsAny(name, pestNames), 0xFFFF5555);
		FeatureRegistry.register(pestHighlight);
		com.cokelord.skyblocksimplified.feature.impl.TrackVacuumParticlesFeature trackVacuumParticles =
			new com.cokelord.skyblocksimplified.feature.impl.TrackVacuumParticlesFeature();
		FeatureRegistry.register(trackVacuumParticles);

		// Farming > Custom Keybinds — remaps the 8 core movement/action keys while active, ported from
		// SkyHanni's GardenCustomKeybinds.kt (scoped down: without island/held-tool detection built yet,
		// it's active whenever the master toggle is on, not just "in Garden holding a farming tool").
		// Force Exclude Barn uses the same confirmed real barn-plot bounding box SkyHanni does.
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsMasterFeature());
		FeatureRegistry.register(new SimpleToggleFeature("force_exclude_barn", "Force Exclude Barn", FeatureCategory.FARMING, "Custom Keybinds"));
		// "These items" = hoes (HoeItem covers every Hypixel farming-tool reskin) and vacuums (Hypixel
		// reskins the Pest Vacuum as a minecart) — see CustomKeybindRegistry.isHoldingRelevantItem().
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.CustomKeybindsHoldingOnlyFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.PestPlotTeleportFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.SetSpawnKeybindFeature());
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_attack", "Attack Key", CustomKeybindRegistry.Slot.ATTACK));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_use", "Use Key", CustomKeybindRegistry.Slot.USE));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_left", "Left Key", CustomKeybindRegistry.Slot.LEFT));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_right", "Right Key", CustomKeybindRegistry.Slot.RIGHT));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_forward", "Forward Key", CustomKeybindRegistry.Slot.FORWARD));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_back", "Back Key", CustomKeybindRegistry.Slot.BACK));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_jump", "Jump Key", CustomKeybindRegistry.Slot.JUMP));
		FeatureRegistry.register(new KeybindOverrideFeature("keybind_sneak", "Sneak Key", CustomKeybindRegistry.Slot.SNEAK));

		// Greenhouse — full column-permutation DP solver ported from SkyHanni's DnaAnalyzerSolver.kt.
		FeatureRegistry.register(new DnaAnalyzerSolverFeature());

		// Farming > Pest farming — Mute Vacuum is real: confirmed vanilla sound path "entity.wither.shoot"
		// (reused by Hypixel for the vacuum), from SkyHanni's PestFinder.kt.
		FeatureRegistry.register(new SoundMuteFeature("mute_pest_vacuum", "Mute Vacuum", FeatureCategory.FARMING, "Pest farming",
			instance -> instance.getIdentifier().getPath().contains("wither.shoot")));
		// Real: chat regex ported verbatim from SkyHanni's PestSpawn.kt.
		FeatureRegistry.register(new PestSpawnAlertFeature());
		// Real: tab-list "Pests" widget cooldown, ported from SkyHanni's PestSpawnTimer.kt.
		FeatureRegistry.register(new PestCooldownFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HidePestDropsFeature());
		FeatureRegistry.register(new SimpleToggleFeature("pest_spray_reminder", "Spray Reminder", FeatureCategory.FARMING, "Pest farming"));
		// Real: tab-list "Stats" widget percentage, ported from SkyHanni's BonusPestChanceDisplay.kt.
		FeatureRegistry.register(new BonusPestChanceFeature());

		// Farming > Visitors — all real now. Tab-list "Next Visitor" line (GardenVisitorTimer.kt); Rare
		// Reward Warning and Visitor Shopping List use the confirmed slot layout/lore format from
		// GardenVisitorChat.kt/VisitorRewardWarning.kt/GardenVisitorTooltip.kt. Hide Hypixel New Visitor
		// Message owns its own arrival-message listener now (the SBAR-branded replacement message this
		// project sent alongside it was removed per user request).
		FeatureRegistry.register(new VisitorTimerFeature());
		FeatureRegistry.register(new VisitorShoppingListFeature());
		FeatureRegistry.register(new RareRewardWarningFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideHypixelVisitorMessageFeature());

		// Real bug found (per user report on a different SoundMuteFeature — "I cant find the disable bonzo
		// staff sounds module"): this comment used to say "Foraging (no subcategories)", but FORAGING has
		// carried real subcategory tabs ("Foraging"/"HOTF") since HOTF was added — a null getSubcategory()
		// can never exact-match MainScreen's currently-selected tab once a category has any subcategories at
		// all, so these two have silently never rendered in the module list since HOTF was added. Mute
		// Phantoms and Mute Tree Breaking are both real: confirmed vanilla sound paths "phantom" and
		// "entity.creaking.death" (the latter reused by Hypixel for tree-breaking, from SkyHanni's
		// MuteTreeSounds.kt).
		FeatureRegistry.register(new SoundMuteFeature("mute_phantoms", "Mute Phantoms", FeatureCategory.FORAGING, "Foraging",
			instance -> instance.getIdentifier().getPath().contains("phantom")));
		FeatureRegistry.register(new SoundMuteFeature("mute_tree_breaking", "Mute Tree Breaking", FeatureCategory.FORAGING, "Foraging",
			instance -> instance.getIdentifier().getPath().contains("creaking.death")));
		// Real: nametag regex on the tree's progress ArmorStand, ported from SkyHanni's TreeProgressDisplay.kt.
		FeatureRegistry.register(new TreeProgressDisplayFeature());
		// Real: tab-list "Cooldown: AVAILABLE" line, ported from SkyHanni's MoongladeBeaconWarning.kt.
		FeatureRegistry.register(new MoongladeBeaconAlertFeature());

		// HOTF = the Foraging skill tree, not Heart of the Mountain. Both are real now: ported from
		// SkyHanni 7.35's HotxFeatures.kt/HotfData.kt (the beta clone read earlier in this session
		// predated SkyHanni's own HOTF coverage — the user supplied a newer source drop with it).
		FeatureRegistry.register(new ActiveHotfPerksHighlightFeature());
		FeatureRegistry.register(new HotfLevelStackFeature());

		// Combat (no subcategory) — all real now. Armor Stack Display (action-bar regex) and Hide Damage
		// Splash (confirmed ArmorStand damage-splash pattern via EntityHideRegistry) as before. Flare
		// uses the real skull-texture values from SkyHanni's own public repo (hannibal002/SkyHanni-REPO,
		// see FlareDetector's doc comment) instead of the in-world sphere/wireframe rendering, which
		// needs render infra this project doesn't have. Shuriken/Twilight show a simple "active nearby"
		// HUD line from the same confirmed nametag glyphs SkyHanni uses, instead of a floating per-boss
		// world-space label (needs a world-to-screen projection this project doesn't have generically).
		FeatureRegistry.register(new ArmorStackDisplayFeature());
		FeatureRegistry.register(new FlareDisplayFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideDamageSplashesFeature());
		// Per user request: truncates real damage-indicator numbers (1,400,000 -> 1.4M).
		com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature damageTruncator =
			new com.cokelord.skyblocksimplified.feature.impl.DamageTruncatorFeature();
		FeatureRegistry.register(damageTruncator);
		com.cokelord.skyblocksimplified.feature.impl.HideFireFeature hideFire = new com.cokelord.skyblocksimplified.feature.impl.HideFireFeature();
		FeatureRegistry.register(hideFire);
		FeatureRegistry.register(new NametagGlyphIndicatorFeature("shuriken_indicator", "Shuriken Indicator", "§b✯", "§bShuriken Active", 0.66f));
		FeatureRegistry.register(new NametagGlyphIndicatorFeature("twilight_indicator", "Twilight Indicator", "§5ᛤ", "§5Twilight Active", 0.70f));

		// Combat > Dungeons / Kuudra — Instance Chest Profit stays as before (scoped to a total-value
		// readout, see InstanceChestProfitFeature's own doc comment for why the full profit/cost breakdown
		// isn't ported there). Croesus Chest Overlay was replaced outright by CroesusFeature, ported from
		// Odin's fuller Croesus.kt (top-2 profit highlighting, hide instead of dim, per-chest breakdown
		// HUD, chest counter) per user request. Both features are mirrored into the other's subcategory
		// (linked — one master state, two rows): Instance Chest Profit into Kuudra, Croesus into Dungeons.
		InstanceChestProfitFeature instanceChestProfit = new InstanceChestProfitFeature();
		CroesusFeature croesus = new CroesusFeature();
		FeatureRegistry.register(instanceChestProfit);
		FeatureRegistry.register(croesus);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(instanceChestProfit, "Kuudra"));
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(croesus, "Dungeons"));

		com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature kismetFeatherBlock =
			new com.cokelord.skyblocksimplified.feature.impl.KismetFeatherBlockFeature();
		FeatureRegistry.register(kismetFeatherBlock);

		// Combat > Dungeons — new this round, ported from SkyHanni's dungeon feature set (see each
		// class's own doc comment for exact scoping/simplifications). Object Hider's 9 sub-toggles and
		// Message Filter's 5 sub-toggles are plain toggles read by shared predicates/listeners, same
		// pattern as the essence/hide-opened toggles above.
		FeatureRegistry.register(new DungeonClickedBlocksFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.SecretChestAnyKeyCloseFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.DungeonNotificationsFeature());
		// Per user request (see FeatureCategory's doc comment on the New Modules subcategory move): this is
		// one of the real most-recently-added modules per git history since the 1.0.22 GitHub release, so it
		// keeps a mirror here — see registerModuleFeatures() for the other current picks.
		com.cokelord.skyblocksimplified.feature.impl.BossBarFeature bossBar =
			new com.cokelord.skyblocksimplified.feature.impl.BossBarFeature();
		FeatureRegistry.register(bossBar);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(bossBar, FeatureCategory.ABOUT, "New Modules"));

		// Per user request ("Move all 'Hide' modules into one big 'Dungeon de-clutter' module"): these used to
		// be 9 separate top-level EntityHideFeature rows (see DungeonDeclutterFeature's own doc comment for
		// why) — now one module with 9 internal subtoggles.
		com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature dungeonDeclutter =
			new com.cokelord.skyblocksimplified.feature.impl.DungeonDeclutterFeature();
		FeatureRegistry.register(dungeonDeclutter);

		// Real: "§c/§d/§4[BOSS] " prefix + known boss-name matching (ported from SkyHanni's
		// DungeonBossMessages.kt), plus Rare Drops/Keys and Doors/Solo Class/Solo Class Stats/Fairy
		// Dialogue/Blessing Messages — all 7 categories live as toggles inside this one module now.
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.ChatDeclutterFeature());

		// Ultimate Warning's title alert moved into Dungeon Notifications (ULTIMATE_READY NotificationType)
		// per user request — the standalone module is gone; its old "Hide Ultimate Ready Message" toggle
		// still lives in Chat De-clutter (declutter.isHideUltimateReady/setHideUltimateReady), unaffected.

		com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature itemAnimations =
			new com.cokelord.skyblocksimplified.feature.impl.ItemAnimationsFeature();
		FeatureRegistry.register(itemAnimations);

		FeatureRegistry.register(new LividFinderFeature());

		// Inventory > Storage — third attempt at this exact feature (twice shipped and removed before, see
		// StorageOverlayFeature's own doc comment for the full history and this round's real Round 4 fix).
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.StorageOverlayFeature());
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.LoadoutOverlayFeature());

		// Enchanting > Misc — restored per task tracker #598, "Keep Superpairs Items Visible" half only
		// (the old Ultra-Rare Book Alert half of this same module is not part of this round's restoration).
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.SuperpairsModuleFeature());

		// Combat > Dungeons — per user request (task #600/Party Finder, this session's "Yes, build both
		// now" round): real Party Finder join detection + Catacombs stats via a keyless public proxy, and
		// a purely local (no API needed) equipment display for nearby players. See each class's own doc
		// comment for the exact mechanism and reliability caveats.
		com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature partyFinder =
			new com.cokelord.skyblocksimplified.feature.impl.PartyFinderFeature();
		FeatureRegistry.register(partyFinder);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(partyFinder, FeatureCategory.ABOUT, "New Modules"));
		// Per user request (large autokick spec, per-floor "advanced mode" explicitly retracted): kicks a
		// Party Finder joiner below a configured minimum personal-best time for the currently-queued floor
		// and/or missing a configured set of items/pets — see AutoKickFeature's own doc comment for the real
		// Group Builder slot layout and API fields this relies on.
		com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature autoKick =
			new com.cokelord.skyblocksimplified.feature.impl.AutoKickFeature();
		FeatureRegistry.register(autoKick);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(autoKick, FeatureCategory.ABOUT, "New Modules"));

		com.cokelord.skyblocksimplified.feature.impl.EquipmentDisplayFeature equipmentDisplay =
			new com.cokelord.skyblocksimplified.feature.impl.EquipmentDisplayFeature();
		FeatureRegistry.register(equipmentDisplay);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(equipmentDisplay, FeatureCategory.ABOUT, "New Modules"));

		// Combat > Dungeons > Floor 7 — task #597, real user-supplied coordinates only (see the class's own
		// doc comment for exactly what was/wasn't buildable this round: just the F7 Mage "Core" early-enter
		// headcount, not the SS-holder/Maxor/EE2-EE3 counters, which were never specced with real data).
		com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature leapCounter =
			new com.cokelord.skyblocksimplified.feature.impl.LeapCounterFeature();
		FeatureRegistry.register(leapCounter);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(leapCounter, FeatureCategory.ABOUT, "New Modules"));

		// Per user request: a separate module (not folded into Dungeon Notifications) that alerts the whole
		// party in chat + a repeating sound when a teammate reaches the next terminal section's early-enter
		// box, or a Mage reaches Core — see the class's own doc comment for how this differs from
		// PositionalMessagesFeature's self-only presets and LeapCounterFeature's headcount-only zones above.
		com.cokelord.skyblocksimplified.feature.impl.EarlyEnterDetectionFeature earlyEnterDetection =
			new com.cokelord.skyblocksimplified.feature.impl.EarlyEnterDetectionFeature();
		FeatureRegistry.register(earlyEnterDetection);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(earlyEnterDetection, FeatureCategory.ABOUT, "New Modules"));

		// ---- Odin port (Phase 1): Dungeons-tab features. Each is mirrored into New Modules so they're
		// easy to find after this update — see each class's own doc comment for scoping/adaptation notes. ----
		registerOdinDungeonFeatures();

		// ---- Odin port (Phase 2): Boss-tab (F7 device phase) features, new "Boss" subcategory under
		// Combat. Same New Modules mirroring as Phase 1. ----
		registerOdinBossFeatures();

		// ---- Odin port (Phase 3): Etherwarp, Inventory > Misc. Same New Modules mirroring. ----
		registerOdinRenderFeatures();

		// ---- Odin port (Phase 4): remaining Skyblock-tab features, Inventory > Misc. Same New Modules
		// mirroring. Built incrementally; more features are added to this method as they're ported. ----
		registerOdinSkyblockFeatures();

		// Combat > Slayers (general, not tied to one slayer type) — Cocoon Alert (chat regex) and Hide
		// Damage Splashes (same EntityHideRegistry rule/detection as the combat-wide toggle above, just
		// a second on/off switch for it) are real.
		FeatureRegistry.register(new SlayerCocoonAlertFeature());

		// Combat > Slayers > Tarantula — Hide Irrelevant Mobs (was its own "Crimson Isle" subcategory, now
		// deleted per user request; moved here per the same request). Real vanilla mob TYPES rather than
		// name-text matching: these are ordinary un-renamed ambient mobs that just happen to be standing in
		// the way during a Blaze slayer fight, not Hypixel-specific reskins. Low-opacity render
		// (EntityTransparencyFeature), not a full hide — these mobs still need to be a little visible.
		FeatureRegistry.register(new EntityTransparencyFeature("slayer_hide_irrelevant_mobs", "Hide Irrelevant Mobs", FeatureCategory.COMBAT, "Slayers", "Tarantula",
			entity -> com.cokelord.skyblocksimplified.util.IslandGate.isInBurningDesert()
				&& (entity.getType() == net.minecraft.world.entity.EntityTypes.MAGMA_CUBE
					|| entity.getType() == net.minecraft.world.entity.EntityTypes.ZOMBIFIED_PIGLIN
					|| entity.getType() == net.minecraft.world.entity.EntityTypes.CAVE_SPIDER)));

		registerPerformanceFeatures();
	}

	// All Phase 1 Odin features (ported into COMBAT > "Dungeons", except Door Highlight/Blood Camp/Mage
	// Beam/Room Clear/Leap Menu/Mimic/the 8 puzzle solvers/Positional Messages/Invincibility Timer/Secrets
	// Counter/Dungeon Queue/the Highlight merge, which all also live here — same subcategory as the rest
	// of the existing dungeon feature set above).
	//
	// None of this batch is recent enough (per real git history, verified again against the 1.0.22 GitHub
	// release baseline — see FeatureCategory's own doc comment on the New Modules subcategory move) to keep
	// a New Modules mirror any more; every one of these predates 1.0.22. Registered normally only.
	private static void registerOdinDungeonFeatures() {
		var positionalMessages = new com.cokelord.skyblocksimplified.feature.impl.PositionalMessagesFeature();
		var invincibilityTimer = new com.cokelord.skyblocksimplified.feature.impl.InvincibilityTimerFeature();
		var secretsCounter = new com.cokelord.skyblocksimplified.feature.impl.SecretsCounterFeature();
		var dungeonQueue = new com.cokelord.skyblocksimplified.feature.impl.DungeonQueueFeature();
		var waterSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.WaterSolverFeature();
		var tpMazeSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.TPMazeSolverFeature();
		var iceFillSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.IceFillSolverFeature();
		var blazeSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.BlazeSolverFeature();
		var beamsSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.BeamsSolverFeature();
		var weirdosSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.WeirdosSolverFeature();
		var quizSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.QuizSolverFeature();
		var boulderSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.BoulderSolverFeature();
		// Devonian port (per user request/memory of an earlier ask that turned out to have been missed).
		var ticTacToeSolver = new com.cokelord.skyblocksimplified.feature.impl.puzzle.TicTacToeSolverFeature();
		var doorHighlight = new com.cokelord.skyblocksimplified.feature.impl.DoorHighlightFeature();
		var keyHighlight = new com.cokelord.skyblocksimplified.feature.impl.KeyHighlightFeature();
		var bloodCamp = new com.cokelord.skyblocksimplified.feature.impl.BloodCampFeature();
		var mageBeam = new com.cokelord.skyblocksimplified.feature.impl.MageBeamFeature();
		var leapMenu = new com.cokelord.skyblocksimplified.feature.impl.LeapMenuFeature();
		var mimic = new com.cokelord.skyblocksimplified.feature.impl.MimicFeature();
		var starredMobHighlight = new com.cokelord.skyblocksimplified.feature.impl.StarredMobHighlightFeature();
		var playerGlow = new com.cokelord.skyblocksimplified.feature.impl.PlayerGlowFeature();
		var highlightPartyMembers = new com.cokelord.skyblocksimplified.feature.impl.HighlightPartyMembersFeature();
		var dungeonMap = new com.cokelord.skyblocksimplified.feature.impl.DungeonMapFeature();
		var dungeonScoreCalculator = new com.cokelord.skyblocksimplified.feature.impl.DungeonScoreCalculatorFeature();
		var catacombsExpCalculator = new com.cokelord.skyblocksimplified.feature.impl.CatacombsExpCalculatorFeature();
		var dungeonTimers = new com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature();
		var dungeonsCopilot = new com.cokelord.skyblocksimplified.feature.impl.DungeonsCopilotFeature();
		var chestRolling = new com.cokelord.skyblocksimplified.feature.impl.ChestRollingFeature();
		var dungeonRoutes = new com.cokelord.skyblocksimplified.feature.impl.DungeonRoutesFeature();

		java.util.List.<com.cokelord.skyblocksimplified.feature.Feature>of(
			positionalMessages, invincibilityTimer, secretsCounter, dungeonQueue,
			waterSolver, tpMazeSolver, iceFillSolver, blazeSolver, beamsSolver, weirdosSolver, quizSolver, boulderSolver, ticTacToeSolver,
			doorHighlight, keyHighlight, bloodCamp, mageBeam, leapMenu, mimic, starredMobHighlight, playerGlow, highlightPartyMembers, dungeonMap, dungeonScoreCalculator,
			catacombsExpCalculator, dungeonTimers, dungeonsCopilot, chestRolling, dungeonRoutes
		).forEach(FeatureRegistry::register);
	}

	// All Phase 2 Odin features (F7 boss-fight device solvers, ported into COMBAT > "Boss", a new
	// subcategory alongside the existing Dungeons/Kuudra/Crimson Isle ones).
	//
	// witherHighlight predates 1.0.22 (see FeatureCategory's doc comment on the New Modules subcategory
	// move) — no longer mirrored.
	private static void registerOdinBossFeatures() {
		var inactiveWaypoints = new com.cokelord.skyblocksimplified.feature.impl.InactiveWaypointsFeature();
		var melodyMessage = new com.cokelord.skyblocksimplified.feature.impl.MelodyMessageFeature();
		var terminalSounds = new com.cokelord.skyblocksimplified.feature.impl.TerminalSoundsFeature();
		var terminalSolver = new com.cokelord.skyblocksimplified.feature.impl.TerminalSolverFeature();
		var arrowsDevice = new com.cokelord.skyblocksimplified.feature.impl.ArrowsDeviceFeature();
		var arrowAlign = new com.cokelord.skyblocksimplified.feature.impl.ArrowAlignFeature();
		var simonSays = new com.cokelord.skyblocksimplified.feature.impl.SimonSaysFeature();
		var witherHighlight = new com.cokelord.skyblocksimplified.feature.impl.WitherHighlightFeature();

		java.util.List.<com.cokelord.skyblocksimplified.feature.Feature>of(
			inactiveWaypoints, melodyMessage, terminalSounds, terminalSolver, arrowsDevice, arrowAlign, simonSays, witherHighlight
		).forEach(FeatureRegistry::register);
	}

	// Phase 3 Odin port: Etherwarp, ported into INVENTORY > "Misc" (used across islands, not dungeon-specific).
	private static void registerOdinRenderFeatures() {
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.EtherwarpFeature());
	}

	// Phase 4 Odin port: remaining Skyblock-tab features, ported into INVENTORY > "Misc". Built up
	// incrementally as each feature is finished. commandAliases/commandShortcuts/autoArchitectDraft also
	// predate 1.0.22 now — see registerModuleFeatures() for the current New Modules picks instead.
	private static void registerOdinSkyblockFeatures() {
		var playerDisplay = new com.cokelord.skyblocksimplified.feature.impl.PlayerDisplayFeature();
		var noCursorReset = new com.cokelord.skyblocksimplified.feature.impl.NoCursorResetFeature();
		var autoSprint = new com.cokelord.skyblocksimplified.feature.impl.AutoSprintFeature();
		var slotBinds = new com.cokelord.skyblocksimplified.feature.impl.SlotBindsFeature();
		var splits = new com.cokelord.skyblocksimplified.feature.impl.SplitsFeature();
		var chatCommands = new com.cokelord.skyblocksimplified.feature.impl.ChatCommandsFeature();
		// Inventory > Misc — per user request: lock a hovered slot via a configurable keybind so it can't
		// be dropped/moved out of the inventory by accident. See SlotLockingFeature's own doc comment for
		// exactly which interactions are blocked vs. still allowed.
		var slotLocking = new com.cokelord.skyblocksimplified.feature.impl.SlotLockingFeature();
		// Inventory > Misc — per user request: a "+" list of find/replace text pairs applied to visible
		// chat/system text. See VisualWordsFeature's own doc comment for exactly what it covers (GAME
		// messages only, not real signed player chat) and its per-styled-run matching scope.
		var visualWords = new com.cokelord.skyblocksimplified.feature.impl.VisualWordsFeature();
		// Inventory > Misc — per user request: "Chat Commands" (renamed Command Aliases in code to avoid
		// colliding with the existing ChatCommandsFeature above, a completely different emote/party-shortcut
		// feature) and "Command Shortcuts", a keybind-triggered command sender. See each feature's own doc
		// comment.
		var commandAliases = new com.cokelord.skyblocksimplified.feature.impl.CommandAliasesFeature();
		var commandShortcuts = new com.cokelord.skyblocksimplified.feature.impl.CommandShortcutsFeature();
		// Devonian port (per user request/memory of an earlier ask): auto-sends the real /gfs draft command
		// the instant the local player personally fails a Catacombs puzzle.
		var autoArchitectDraft = new com.cokelord.skyblocksimplified.feature.impl.AutoArchitectDraftFeature();
		// Inventory > Misc — per user request: strips the drop-shadow off all rendered text, vanilla and
		// this mod's own alike. See RemoveFontShadowFeature's own doc comment.
		var removeFontShadow = new com.cokelord.skyblocksimplified.feature.impl.RemoveFontShadowFeature();
		// Inventory > Misc — per user request: fills each inventory/hotbar slot with the held item's real
		// rarity color, square or circle. See ItemRarityBackgroundFeature's own doc comment.
		var itemRarityBackground = new com.cokelord.skyblocksimplified.feature.impl.ItemRarityBackgroundFeature();
		// Inventory > Misc — per user request: mouse-wheel scroll support for any tooltip that's taller than
		// its own built-in visible cap. See ScrollableTooltipsFeature's own doc comment.
		var scrollableTooltips = new com.cokelord.skyblocksimplified.feature.impl.ScrollableTooltipsFeature();

		java.util.List.<com.cokelord.skyblocksimplified.feature.Feature>of(
			playerDisplay, noCursorReset, autoSprint, slotBinds, splits, chatCommands, slotLocking, visualWords,
			commandAliases, commandShortcuts, autoArchitectDraft, removeFontShadow, itemRarityBackground,
			scrollableTooltips
		).forEach(FeatureRegistry::register);

		// removeFontShadow/itemRarityBackground/scrollableTooltips are real recent additions per git history
		// since the 1.0.22 GitHub release (see FeatureCategory's doc comment on the New Modules subcategory
		// move); the rest of this batch, including commandAliases/commandShortcuts/autoArchitectDraft, now
		// predate it.
		java.util.function.Function<com.cokelord.skyblocksimplified.feature.Feature, com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror> mirror =
			f -> new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(f, FeatureCategory.ABOUT, "New Modules");
		java.util.List.<com.cokelord.skyblocksimplified.feature.Feature>of(removeFontShadow, itemRarityBackground, scrollableTooltips)
			.forEach(feature -> FeatureRegistry.register(mirror.apply(feature)));
	}

	// Performance category — client-side render-skip toggles only (EntityHideRegistry/ParticleFilterRegistry,
	// the same choke points every other Hide*/particle-filter module in this project already goes through),
	// never anything that touches actual game state. Per user request: things in the spirit of the old
	// 1.8.9 "Patcher" mod's optimization toggles, for the common lag sources on a crowded Hypixel island —
	// dozens of decorative armor stands/item frames left over from builds, arrow/falling-block spam in
	// combat and farms, and general off-screen entity/particle overdraw.
	private static void registerPerformanceFeatures() {
		com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature entityRenderDistance =
			new com.cokelord.skyblocksimplified.feature.impl.EntityRenderDistanceFeature();
		FeatureRegistry.register(entityRenderDistance);

		com.cokelord.skyblocksimplified.feature.impl.FullbrightFeature fullbright =
			new com.cokelord.skyblocksimplified.feature.impl.FullbrightFeature();
		FeatureRegistry.register(fullbright);

		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.HideArrowsFeature());

		// Real recent additions per git history since the 1.0.22 GitHub release (see FeatureCategory's doc
		// comment on the New Modules subcategory move) — both get a mirror there.
		com.cokelord.skyblocksimplified.feature.impl.CameraFeature camera =
			new com.cokelord.skyblocksimplified.feature.impl.CameraFeature();
		FeatureRegistry.register(camera);
		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.LinkedFeatureMirror(camera, FeatureCategory.ABOUT, "New Modules"));

		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.NetworkDisplayFeature());

		EntityHideFeature hideFallingBlocks = new EntityHideFeature("performance_hide_falling_blocks", "Hide Falling Blocks", FeatureCategory.PERFORMANCE, null,
			entity -> entity instanceof net.minecraft.world.entity.item.FallingBlockEntity);
		FeatureRegistry.register(hideFallingBlocks);

		FeatureRegistry.register(new com.cokelord.skyblocksimplified.feature.impl.PerformanceTogglesFeature());
	}
}
