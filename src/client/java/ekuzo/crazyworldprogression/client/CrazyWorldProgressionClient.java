package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.veil.VeilRenderer;
import net.fabricmc.api.ClientModInitializer;

public class CrazyWorldProgressionClient implements ClientModInitializer {
	// Register rendering and other client-only systems.
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		VeilRenderer.register();

	}
}
