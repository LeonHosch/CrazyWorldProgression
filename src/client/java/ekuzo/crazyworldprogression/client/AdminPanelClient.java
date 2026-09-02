package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Client receiver and navigation sender for CWP's server-described admin panel. */
public final class AdminPanelClient {
    // Prevent construction of the static client bridge.
    private AdminPanelClient() {
    }

    // Register the one snapshot receiver used by CWP and every dependent admin module.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(AdminPanelNetworking.PanelSnapshotPayload.TYPE,
                (payload, context) -> show(context.client(), AdminPanelNetworking.decodeSnapshot(payload.json())));
    }

    // Open a new screen or update the currently open panel without losing its parent.
    private static void show(Minecraft client, AdminPanelNetworking.PanelSnapshot snapshot) {
        if (client.gui.screen() instanceof AdminPanelScreen screen) {
            screen.updateSnapshot(snapshot);
        } else {
            client.gui.setScreen(new AdminPanelScreen(client.gui.screen(), snapshot));
        }
    }
}
