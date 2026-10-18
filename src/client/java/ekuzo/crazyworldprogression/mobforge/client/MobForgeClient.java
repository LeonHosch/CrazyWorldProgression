package ekuzo.crazyworldprogression.mobforge.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import ekuzo.crazyworldprogression.mobforge.client.screen.ScriptEditorScreen;
import ekuzo.crazyworldprogression.mobforge.networking.EditorPayloads;
import ekuzo.crazyworldprogression.client.AdminPanelScreen;

public final class MobForgeClient {
	private MobForgeClient() {}

	public static void initialize() {
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.OpenScriptEditor.TYPE, (payload, context) ->
				context.client().setScreenAndShow(new ScriptEditorScreen(context.client(), payload.yaml(),
						payload.templates(), payload.entities(), payload.abilities(),
						context.client().gui.screen() instanceof AdminPanelScreen screen ? screen : null,
						payload.existing())));
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.SaveResult.TYPE, (payload, context) -> {
			if (payload.kind().equals("script")
					&& context.client().gui.screen() instanceof ScriptEditorScreen screen) {
				screen.saveResult(payload.success(), payload.message());
			}
		});
	}
}
