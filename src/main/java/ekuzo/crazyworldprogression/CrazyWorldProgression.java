package ekuzo.crazyworldprogression;

import ekuzo.crazyworldprogression.config.CwpModule;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import ekuzo.crazyworldprogression.admin.CwpAdminModule;
import ekuzo.crazyworldprogression.command.BalanceCommands;
import ekuzo.crazyworldprogression.command.CurrencyCommands;
import ekuzo.crazyworldprogression.command.CwpAdminCommands;
import ekuzo.crazyworldprogression.command.PortalCommands;
import ekuzo.crazyworldprogression.command.PostmasterCommands;
import ekuzo.crazyworldprogression.command.TitleCommands;
import ekuzo.crazyworldprogression.titles.TitleNetworking;
import ekuzo.crazyworldprogression.loot.PostmasterService;
import ekuzo.crazyworldprogression.command.SkillTreeCommands;
import ekuzo.crazyworldprogression.events.RandomEventManager;
import ekuzo.crazyworldprogression.events.RandomEventService;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalBuildProtection;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import ekuzo.crazyworldprogression.selection.AreaSelectionService;
import ekuzo.crazyworldprogression.selection.AreaSelectionNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.Identifier;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.BaselineStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeManager;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeNetworking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CrazyWorldProgression implements ModInitializer {
	public static final String MOD_ID = "crazy-world-progression";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Initialize every server-side system and command owned by the mod.
	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Initializing Crazy World Progression");

		// Items, menus, effects and saved-data types are registered whether or not their part is on, so worlds that
		// used a part that is now switched off still load (and get everything back when it is switched on again).
		CwpContent.register();

		// The framework itself: stats, the admin panel, area selection and the tick time budget.
		SkillStatRegistry.initialize();
		ekuzo.crazyworldprogression.cleanup.TickBudget.initialize();
		ekuzo.crazyworldprogression.cleanup.PerformanceMonitor.initialize();
		AreaSelectionNetworking.registerPayloadType();
		AreaSelectionNetworking.registerServerReceiver();
		AreaSelectionService.initialize();
		AdminPanelNetworking.registerPayloadTypes();
		AdminPanelNetworking.registerServerReceivers();
		CwpAdminModule.register();
		AreaSelectionService.registerMaintenanceTick();

		// Each part of config/crazy-world-progression.yml that is on (CwpModule).
		if (CwpModule.WORLD_BOUNDARY.enabled()) {
			ekuzo.crazyworldprogression.worldboundary.AdvancedWorldBoundary.initialize();
			ekuzo.crazyworldprogression.worldboundary.BoundaryRadiusNetworking.register();
			ekuzo.crazyworldprogression.worldboundary.restriction.RestrictionNetworking.registerPayloadType();
			ekuzo.crazyworldprogression.worldboundary.restriction.RestrictionService.initialize();
			ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas.initialize();
		}
		if (CwpModule.ACCESSORIES.enabled()) {
			ekuzo.crazyworldprogression.accessory.Accessories.initialize();
			ekuzo.crazyworldprogression.accessory.BuffFood.initialize();
			ekuzo.crazyworldprogression.creative.CreativeSlots.initialize();
		}
		if (CwpModule.COLLECTIBLES.enabled()) ekuzo.crazyworldprogression.collectibles.Collectibles.initialize();
		if (CwpModule.MOB_LOOT.enabled()) ekuzo.crazyworldprogression.loot.MobLoot.initialize();
		if (CwpModule.EQUIPMENT.enabled()) {
			ekuzo.crazyworldprogression.progression.equipment.EquipmentSetService.initialize();
			ekuzo.crazyworldprogression.progression.equipment.ProtectionStats.initialize();
			ekuzo.crazyworldprogression.progression.equipment.AllyHealing.initialize();
			ekuzo.crazyworldprogression.progression.equipment.Hubris.initialize();
			ekuzo.crazyworldprogression.progression.equipment.BloodPact.initialize();
			ekuzo.crazyworldprogression.progression.equipment.StaticCharge.initialize();
			ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.initialize();
		}
		if (CwpModule.FISHING.enabled()) ekuzo.crazyworldprogression.loot.FishingJournal.initialize();
		if (CwpModule.REFORGE.enabled()) {
			ekuzo.crazyworldprogression.reforge.ReforgeNetworking.initialize();
			ekuzo.crazyworldprogression.reforge.ResearchService.initialize();
		}
		if (CwpModule.SMITHING.enabled()) ekuzo.crazyworldprogression.smithing.SmithingNetworking.initialize();
		if (CwpModule.STORAGE.enabled()) {
			ekuzo.crazyworldprogression.smithing.ChestQuality.initialize();
			ekuzo.crazyworldprogression.storage.StorageNetworking.initialize();
		}
		ekuzo.crazyworldprogression.creative.CreativeAccess.initialize();
		if (CwpModule.FLETCHING.enabled()) ekuzo.crazyworldprogression.fletching.Fletching.initialize();
		if (CwpModule.AMMO.enabled()) ekuzo.crazyworldprogression.ammo.AmmoPouch.initialize();
		if (CwpModule.SOCIAL.enabled()) ekuzo.crazyworldprogression.social.Social.initialize();
		if (CwpModule.DIVINE_PROTECTION.enabled()) ekuzo.crazyworldprogression.divine.DivineProtectionNetworking.initialize();
		if (CwpModule.RESPAWN.enabled()) ekuzo.crazyworldprogression.respawn.RespawnFeedback.initialize();
		if (CwpModule.FARMING.enabled()) {
			ekuzo.crazyworldprogression.farming.CrossbreedingNetworking.initialize();
			ekuzo.crazyworldprogression.farming.FarmingLevels.initialize();
			ekuzo.crazyworldprogression.farming.ProduceQuality.initialize();
			ekuzo.crazyworldprogression.farming.AnimalProduce.initialize();
		}
		if (CwpModule.COOKING.enabled()) ekuzo.crazyworldprogression.cooking.CookingNetworking.initialize();
		if (CwpModule.SKILLS.enabled()) ekuzo.crazyworldprogression.skills.Skills.initialize();
		if (CwpModule.RANDOM_EVENTS.enabled()) RandomEventService.initialize();
		if (CwpModule.TITLES.enabled()) TitleNetworking.initialize();
		if (CwpModule.PORTALS.enabled()) {
			PortalNetworking.registerPayloadType();
			ekuzo.crazyworldprogression.portals.PortalPreviewNetworking.registerPayloadType();
			ekuzo.crazyworldprogression.buildings.BuildingPreviewNetworking.registerPayloadType();
			ekuzo.crazyworldprogression.portals.WorkshopHelpers.registerPayloadTypes();
			ekuzo.crazyworldprogression.portals.WorldHelperProviders.register();
			PortalNetworking.registerServerReceiver();
			PortalService.initialize();
			PortalBuildProtection.initialize();
			PortalService.registerMaintenanceTick();
		}
		if (CwpModule.POSTMASTER.enabled()) PostmasterService.initialize();
		if (CwpModule.SKILL_TREES.enabled()) {
			SkillTreeNetworking.registerPayloadTypes();
			SkillTreeNetworking.registerServerReceivers();
		}
		if (CwpModule.CHARACTER_SHEET.enabled()) ekuzo.crazyworldprogression.progression.skilltrees.CharacterSheet.initialize();
		if (CwpModule.MOBFORGE.enabled()) MobForge.initialize();

		// Load authoritative YAML only when a server starts, so remote clients never depend on local copies.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			BaselineStatRegistry.reload();
			if (CwpModule.SKILL_TREES.enabled()) SkillTreeManager.initialize();
			if (CwpModule.EQUIPMENT.enabled()) ekuzo.crazyworldprogression.progression.equipment.EquipmentSetRegistry.reload();
			if (CwpModule.RANDOM_EVENTS.enabled()) RandomEventManager.reload();
		});

		// Register all server commands through one callback while keeping startup ownership in this entrypoint.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			CwpAdminCommands.register(dispatcher);
			if (CwpModule.CURRENCY.enabled()) {
				BalanceCommands.register(dispatcher);
				CurrencyCommands.register(dispatcher);
			}
			if (CwpModule.SKILL_TREES.enabled()) SkillTreeCommands.register(dispatcher);
			if (CwpModule.PORTALS.enabled()) {
				PortalCommands.registerPlayer(dispatcher);
				ekuzo.crazyworldprogression.command.PartyCommands.register(dispatcher);
			}
			if (CwpModule.POSTMASTER.enabled()) PostmasterCommands.register(dispatcher);
			if (CwpModule.TITLES.enabled()) TitleCommands.register(dispatcher);
		});
	}

	// Create an identifier inside this mod's namespace.
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
