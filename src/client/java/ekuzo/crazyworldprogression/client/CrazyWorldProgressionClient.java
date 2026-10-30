package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.config.CwpModule;
import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems, for each part of CWP that is on (CwpModule).
	@Override
	public void onInitializeClient() {
		// The framework itself.
		CwpClientSettings.initialize();
		AdminPanelClient.initialize();
		AreaSelectionClient.initialize();
		ekuzo.crazyworldprogression.client.map.MapData.initialize();

		if (CwpModule.WORLD_BOUNDARY.enabled()) {
			ekuzo.crazyworldprogression.worldboundary.ClientBoundaryRadii.initialize();
			ekuzo.crazyworldprogression.worldboundary.restriction.ClientRestrictions.initialize();
		}
		if (CwpModule.TITLES.enabled()) TitleClient.initialize();
		if (CwpModule.EQUIPMENT.enabled()) {
			AscensionHud.initialize();
			ChainLightningRenderer.initialize();
			EquipmentSetTooltip.initialize();
			ProtectionTooltip.initialize();
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
					ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.Payload.TYPE,
					(payload, context) -> ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.clientPercent = payload.percent());
			ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.localPlayer =
					entity -> entity == net.minecraft.client.Minecraft.getInstance().player;
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
					(handler, client) -> ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.clientPercent = 0);
		}
		if (CwpModule.FISHING.enabled()) FishingJournalScreen.initialize();
		if (CwpModule.COLLECTIBLES.enabled()) CollectiblesClient.initialize();
		if (CwpModule.POSTMASTER.enabled()) PostmasterScreen.initialize();
		if (CwpModule.FARMING.enabled()) CrossbreedingScreen.initialize();
		if (CwpModule.COOKING.enabled()) CookingScreen.initialize();
		if (CwpModule.SKILL_TREES.enabled()) SkillTreeClient.initialize();
		if (CwpModule.CHARACTER_SHEET.enabled()) CharacterSheetClient.initialize();
		if (CwpModule.PORTALS.enabled()) {
			EncounterBannerClient.initialize();
			PortalClientRenderer.initialize();
			WaypointClient.initialize();
			PortalPlayerVisibility.initialize();
			BuildingPreviewRenderer.initialize();
			WorkshopHelperClient.initialize();
			PortalEntryPromptClient.initialize();
			PartyScreen.initialize();
			PartyVoiceVolumes.initialize();
		}
		if (CwpModule.SMITHING.enabled()) {
			QualityTooltip.initialize();
			SmithingScreen.initialize();
		}
		if (CwpModule.REFORGE.enabled()) {
			ReforgeScreen.initialize();
			StudyScreen.initialize();
		}
		if (CwpModule.FLETCHING.enabled()) FletchingScreen.initialize();
		if (CwpModule.ACCESSORIES.enabled()) CreativeSlotsClient.initialize();
		if (CwpModule.AMMO.enabled()) AmmoClient.initialize();
		if (CwpModule.SOCIAL.enabled()) {
			ChestLockClient.initialize();
			SocialScreen.initialize();
		}
		if (CwpModule.STORAGE.enabled()) {
			ScrollChestScreen.initialize();
			StorageClient.initialize();
			ItemLockClient.initialize();
		}
		if (CwpModule.DIVINE_PROTECTION.enabled()) DivineProtectionScreen.initialize();
		if (CwpModule.RESPAWN.enabled()) RespawnFeedbackClient.initialize();
	}
}
