package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.mobforge.client.MobForgeClient;
import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		// Client initialization remains visible here so startup ownership is easy to follow.
		AdminPanelClient.initialize();
		AreaSelectionClient.initialize();
		SkillTreeClient.initialize();
		PortalClientRenderer.initialize();
		PortalEntryPromptClient.initialize();
		PortalGroupClient.initialize();
		MobForgeClient.initialize();
	}
}
