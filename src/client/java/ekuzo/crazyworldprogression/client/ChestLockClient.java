package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.style.PanelStyle;
import ekuzo.crazyworldprogression.social.ChestLockNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;

/**
 * The lock button above a chest or barrel window: it shows the chest's lock, and its owner clicks it to switch
 * Public, Private, Friends and Clan (Clan only while in a clan). The server sends the lock when the chest is opened
 * and after each change (ChestLockNetworking).
 */
final class ChestLockClient {
    private static final String[] ORDER = {"PUBLIC", "PRIVATE", "FRIENDS", "CLAN"};
    private static ChestLockNetworking.InfoPayload info;
    private static long receivedAt;
    private static Button button;

    private ChestLockClient() { }

    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(ChestLockNetworking.InfoPayload.TYPE, (payload, context) -> {
            info = payload;
            receivedAt = System.currentTimeMillis();
            if (button != null) update();
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            button = null;
            if (!(screen instanceof AbstractContainerScreen<?> container) || !(container.getMenu() instanceof ChestMenu menu)
                    || info == null || System.currentTimeMillis() - receivedAt > 3000) return;
            int left = (width - 176) / 2, top = (height - (114 + menu.getRowCount() * 18)) / 2;
            button = Button.builder(Component.empty(), ignored -> next()).bounds(left + 176 - 110, top - 15, 110, 14).build();
            Screens.getWidgets(screen).add(PanelStyle.styled(button));
            update();
            ScreenEvents.remove(screen).register(closed -> button = null);
        });
    }

    private static void update() {
        button.setMessage(Component.literal("Lock: " + info.text()));
        button.active = info.manage();
        String hint = info.manage() ? "Click to change who can open it" : "Only its owner can change this";
        String error = info.error().isEmpty() ? "" : "\n§c" + info.error();
        button.setTooltip(Tooltip.create(Component.literal(hint + "\n§7" + info.usage() + error)));
    }

    private static void next() {
        int index = 0;
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(info.mode())) index = i;
        String mode = ORDER[(index + 1) % ORDER.length];
        if (mode.equals("CLAN") && !info.inClan()) mode = ORDER[0];
        ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new ChestLockNetworking.SetPayload(info.pos(), mode));
    }
}
