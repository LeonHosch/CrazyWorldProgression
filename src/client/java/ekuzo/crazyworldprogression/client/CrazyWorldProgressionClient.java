package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.mobforge.client.MobForgeClient;
import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		EchelonClientSettings.initialize();
		TitleClient.initialize();
		EncounterBannerClient.initialize();
		// Client initialization remains visible here so startup ownership is easy to follow.
		AdminPanelClient.initialize();
		AreaSelectionClient.initialize();
		SkillTreeClient.initialize();
		PortalClientRenderer.initialize();
		PortalPlayerVisibility.initialize();
		BuildingPreviewRenderer.initialize();
		PortalEntryPromptClient.initialize();
		PortalGroupClient.initialize();
		PartyScreen.initialize();
		MobForgeClient.initialize();
	}
}
