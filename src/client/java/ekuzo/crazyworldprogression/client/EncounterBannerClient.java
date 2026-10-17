package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.portals.EncounterBanner;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Draws encounter banners slightly below the top center of the screen, fading in and out. */
public final class EncounterBannerClient {
    private static final long FADE_IN_MS = 300L;
    private static final long HOLD_MS = 2_800L;
    private static final long FADE_OUT_MS = 700L;
    private static final float TITLE_SCALE = 2.25F;
    private static Banner current;

    private EncounterBannerClient() { }

    private record Banner(Component title, Component subtitle, int color, long shownAt) { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(EncounterBanner.Payload.TYPE, (payload, context) -> {
            EncounterBanner.Kind kind;
            try { kind = EncounterBanner.Kind.valueOf(payload.kind()); }
            catch (IllegalArgumentException exception) { return; }
            current = new Banner(Component.translatable(kind.titleKey()),
                    Component.translatable(kind.subtitleKey(), payload.argument()), kind.color(),
                    System.currentTimeMillis());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = null);
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE,
                CrazyWorldProgression.id("encounter_banner"), EncounterBannerClient::render);
    }

    /** 0 to 1 opacity for the given age, or a negative value once the banner is finished. */
    static float alpha(long ageMs) {
        if (ageMs < 0L) return 0.0F;
        if (ageMs < FADE_IN_MS) return ageMs / (float) FADE_IN_MS;
        if (ageMs < FADE_IN_MS + HOLD_MS) return 1.0F;
        long fade = ageMs - FADE_IN_MS - HOLD_MS;
        return fade < FADE_OUT_MS ? 1.0F - fade / (float) FADE_OUT_MS : -1.0F;
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Banner banner = current;
        if (banner == null) return;
        float alpha = alpha(System.currentTimeMillis() - banner.shownAt());
        if (alpha < 0.0F) {
            current = null;
            return;
        }
        int alphaBits = Math.max(5, Math.round(alpha * 255.0F)) << 24;
        Minecraft minecraft = Minecraft.getInstance();
        var font = minecraft.font;
        int screenWidth = graphics.guiWidth();
        int top = Math.max(18, graphics.guiHeight() / 7);
        graphics.pose().pushMatrix();
        graphics.pose().translate(screenWidth / 2.0F, top);
        graphics.pose().scale(TITLE_SCALE, TITLE_SCALE);
        graphics.text(font, banner.title(), -font.width(banner.title()) / 2, 0, alphaBits | banner.color(), true);
        graphics.pose().popMatrix();
        int subtitleY = top + Math.round(font.lineHeight * TITLE_SCALE) + 4;
        graphics.text(font, banner.subtitle(), (screenWidth - font.width(banner.subtitle())) / 2, subtitleY,
                alphaBits | 0xE0E0E0, true);
    }
}
