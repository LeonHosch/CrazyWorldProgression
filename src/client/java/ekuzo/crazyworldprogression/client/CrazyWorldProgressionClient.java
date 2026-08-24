package ekuzo.crazyworldprogression.client;

import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		// Client initialization remains visible here so startup ownership is easy to follow.
		SkillTreeClient.initialize();
	}
}
