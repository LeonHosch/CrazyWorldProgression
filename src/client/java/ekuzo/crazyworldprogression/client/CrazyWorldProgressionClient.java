package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.veil.VeilRenderer;
import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		// Client initialization remains visible here so startup ownership is easy to follow.
		VeilRenderer.register();
		SkillTreeClient.initialize();
	}
}
