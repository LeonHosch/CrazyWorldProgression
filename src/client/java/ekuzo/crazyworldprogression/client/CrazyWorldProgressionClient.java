package ekuzo.crazyworldprogression.client;

import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		ekuzo.crazyworldprogression.worldboundary.ClientBoundaryRadii.initialize();
		ekuzo.crazyworldprogression.worldboundary.restriction.ClientRestrictions.initialize();
		EchelonClientSettings.initialize();
		TitleClient.initialize();
		EncounterBannerClient.initialize();
		AscensionHud.initialize();
		ChainLightningRenderer.initialize();
		FishingJournalScreen.initialize();
		CollectiblesClient.initialize();
		PostmasterScreen.initialize();
		CrossbreedingScreen.initialize();
		CookingScreen.initialize();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.Payload.TYPE,
				(payload, context) -> ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.clientPercent = payload.percent());
		ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.localPlayer =
				entity -> entity == net.minecraft.client.Minecraft.getInstance().player;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> ekuzo.crazyworldprogression.progression.equipment.DrawSpeed.clientPercent = 0);
		// Client initialization remains visible here so startup ownership is easy to follow.
		AdminPanelClient.initialize();
		AreaSelectionClient.initialize();
		SkillTreeClient.initialize();
		CharacterSheetClient.initialize();
		PortalClientRenderer.initialize();
		WaypointClient.initialize();
		PortalPlayerVisibility.initialize();
		BuildingPreviewRenderer.initialize();
		PortalEntryPromptClient.initialize();
		PartyScreen.initialize();
		PartyVoiceVolumes.initialize();
		EquipmentSetTooltip.initialize();
		ProtectionTooltip.initialize();
		QualityTooltip.initialize();
		ReforgeScreen.initialize();
		SmithingScreen.initialize();
		FletchingScreen.initialize();
		AmmoClient.initialize();
		ChestLockClient.initialize();
		SocialScreen.initialize();
		ScrollChestScreen.initialize();
		StorageClient.initialize();
		ItemLockClient.initialize();
		DivineProtectionScreen.initialize();
		RespawnFeedbackClient.initialize();
		ekuzo.crazyworldprogression.client.map.MapData.initialize();
		StudyScreen.initialize();
	}
}
