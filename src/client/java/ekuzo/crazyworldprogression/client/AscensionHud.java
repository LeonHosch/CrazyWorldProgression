package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.equipment.Hubris;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Hubris's Ascension stacks on the left side of the screen, like buff stacks in Destiny: a framed icon with the
 * stack count, the name beside it, and a thin bar filling up towards the next stack.
 */
final class AscensionHud {
    private static final Identifier ICON = CrazyWorldProgression.id("textures/gui/ascension.png");
    private static final Identifier DOWNFALL_ICON = CrazyWorldProgression.id("textures/gui/downfall.png");
    private static final int SIZE = 20;
    private static Hubris.Payload state;
    private static long receivedAt;
    private static Hubris.DownfallPayload downfall;
    private static final Identifier LUST_ICON = CrazyWorldProgression.id("textures/gui/blood_lust.png");
    private static int lust;
    private static long downfallAt;

    private AscensionHud() { }

    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(Hubris.Payload.TYPE, (payload, context) -> {
            // Sounds are only for the player whose stacks change, so they play here, not on the server.
            if (state != null && payload.stacks() > state.stacks())
                play(SoundEvents.BLAZE_SHOOT, 0.3F, 1.4F + payload.stacks() * 0.05F);
            state = payload;
            receivedAt = System.currentTimeMillis();
        });
        ClientPlayNetworking.registerGlobalReceiver(Hubris.DownfallPayload.TYPE, (payload, context) -> {
            if (payload.stacks() > 0 && downfall == null) play(SoundEvents.BEACON_DEACTIVATE, 0.6F, 0.8F);
            else if (payload.stacks() == 0 && downfall != null) play(SoundEvents.FIRE_EXTINGUISH, 0.3F, 1.2F);
            downfall = payload.stacks() > 0 ? payload : null;
            downfallAt = System.currentTimeMillis();
        });
        ClientPlayNetworking.registerGlobalReceiver(ekuzo.crazyworldprogression.progression.equipment.BloodPact.LustPayload.TYPE,
                (payload, context) -> {
                    if (payload.stacks() > lust) play(SoundEvents.WARDEN_HEARTBEAT, 0.8F, 1.0F + payload.stacks() * 0.1F);
                    lust = payload.stacks();
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            state = null;
            downfall = null;
            lust = 0;
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.MOB_EFFECTS,
                CrazyWorldProgression.id("ascension"), AscensionHud::render);
    }

    private static void play(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.playSound(sound, volume, pitch);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Hubris.Payload current = state;
        int x = 6, y = graphics.guiHeight() / 2 - SIZE / 2;
        if (current != null && current.active() && visible(current)) {
            renderAscension(graphics, current, x, y);
            y += SIZE + 6;
        }
        Hubris.DownfallPayload fall = downfall;
        if (fall != null && fall.ticks() - (System.currentTimeMillis() - downfallAt) / 50 > 0) {
            renderDownfall(graphics, fall, x, y);
            y += SIZE + 6;
        }
        if (lust > 0) renderLust(graphics, lust, x, y);
    }

    /** Bloodletter's Lust: dark red frame, blood drop icon and stacks (it follows health, so no timer bar). */
    private static void renderLust(GuiGraphicsExtractor graphics, int stacks, int x, int y) {
        var font = Minecraft.getInstance().font;
        graphics.fill(x - 1, y - 1, x + SIZE + 1, y + SIZE + 1, 0xFF8A0A12);
        graphics.fill(x, y, x + SIZE, y + SIZE, 0xD0180A0C);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LUST_ICON, x + 2, y + 2, 0, 0, 16, 16, 16, 16, 16, 16);
        String count = Integer.toString(stacks);
        graphics.text(font, Component.literal(count), x + SIZE - font.width(count), y + SIZE - font.lineHeight + 1,
                0xFFFFFFFF, true);
        graphics.text(font, Component.translatable("hud.crazy-world-progression.blood_lust"), x + SIZE + 5, y + 6,
                0xFFE04050, true);
    }

    /** Shown with stacks, or while a streak is running (even at 0 stacks); hidden once both timers are out. */
    private static boolean visible(Hubris.Payload current) {
        if (current.stacks() > 0) return true;
        long elapsed = (System.currentTimeMillis() - receivedAt) / 50;
        return current.idle() - elapsed > 0 || (current.next() >= 0 && current.idle() < 0);
    }

    /** Downfall, the debuff left when a set piece comes off: red frame, stacks, and a white bar until it fades. */
    private static void renderDownfall(GuiGraphicsExtractor graphics, Hubris.DownfallPayload fall, int x, int y) {
        var font = Minecraft.getInstance().font;
        long elapsed = (System.currentTimeMillis() - downfallAt) / 50;
        float left = Math.max(0, fall.ticks() - elapsed);
        if (left <= 0) return;
        graphics.fill(x - 1, y - 1, x + SIZE + 1, y + SIZE + 1, 0xFFB03030);
        graphics.fill(x, y, x + SIZE, y + SIZE, 0xD0181010);
        graphics.blit(RenderPipelines.GUI_TEXTURED, DOWNFALL_ICON, x + 2, y + 2, 0, 0, 16, 16, 16, 16, 16, 16);
        String count = Integer.toString(fall.stacks());
        graphics.text(font, Component.literal(count), x + SIZE - font.width(count), y + SIZE - font.lineHeight + 1,
                0xFFFFFFFF, true);
        int textX = x + SIZE + 5;
        graphics.text(font, Component.translatable("hud.crazy-world-progression.downfall"), textX, y + 1, 0xFFFF7A6A, true);
        bar(graphics, textX, y + 12, left / fall.total(), 0xFFFFFFFF);
    }

    private static void renderAscension(GuiGraphicsExtractor graphics, Hubris.Payload current, int x, int y) {
        var font = Minecraft.getInstance().font;
        boolean stacked = current.stacks() > 0;
        graphics.fill(x - 1, y - 1, x + SIZE + 1, y + SIZE + 1, stacked ? 0xFFB8862B : 0xFF5A4A2A);
        graphics.fill(x, y, x + SIZE, y + SIZE, 0xD0141018);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x + 2, y + 2, 0, 0, 16, 16, 16, 16, 16, 16,
                stacked ? 0xFFFFFFFF : 0x80FFFFFF);
        String count = Integer.toString(current.stacks());
        graphics.text(font, Component.literal(count), x + SIZE - font.width(count), y + SIZE - font.lineHeight + 1,
                stacked ? 0xFFFFFFFF : 0xFFAAAAAA, true);
        int textX = x + SIZE + 5;
        graphics.text(font, Component.translatable("hud.crazy-world-progression.ascension"), textX, y + 1,
                stacked ? 0xFFFFD87A : 0xFFB0A080, true);
        long elapsed = (System.currentTimeMillis() - receivedAt) / 50;
        // Gold: fills up to the next stack. White: drains until the stacks reset out of combat.
        bar(graphics, textX, y + 12, current.next() < 0 ? (current.stacks() >= Hubris.MAX_STACKS ? 1 : 0)
                : 1.0F - Math.max(0, current.next() - elapsed) / (float) current.perStack(), 0xFFFFC94A);
        bar(graphics, textX, y + 16, current.idle() < 0 ? 0
                : Math.max(0, current.idle() - elapsed) / (float) current.idleTotal(), 0xFFFFFFFF);
    }

    private static void bar(GuiGraphicsExtractor graphics, int x, int y, float progress, int color) {
        int width = 54;
        graphics.fill(x, y, x + width, y + 2, 0x80000000);
        graphics.fill(x, y, x + Math.round(width * Math.min(1, progress)), y + 2, color);
    }
}
