package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.progression.skilltrees.CharacterSheet;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatValueFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The character sheet beside the inventory, opened with the "Show stats" button: the player's face, name and title,
 * their skill levels with XP bars, their stats and their unlocked abilities, and a button to the skill trees. The
 * server sends the sheet when it opens and every two seconds while it stays open.
 */
public final class CharacterSheetClient {
    public static final int WIDTH = 160;
    public static final int GAP = 2;
    private static final int HEADER = 30;
    private static final int FOOTER = 24;
    private static final int REFRESH_TICKS = 40;
    private static final int TEXT = 0xFF404040;
    private static final int MUTED = 0xFF6B6B6B;
    private static boolean open;
    private static CharacterSheet.Sheet sheet;
    private static int scroll;
    private static int contentHeight;
    private static int sinceRequest;

    private CharacterSheetClient() { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(CharacterSheet.Payload.TYPE,
                (payload, context) -> sheet = CharacterSheet.decode(payload.json()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sheet = null);
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen)) return;
            Button skillTrees = Button.builder(Component.translatable("button.crazy-world-progression.skill_trees"),
                    ignored -> SkillTreeClient.requestOpen()).bounds(0, 0, WIDTH - 12, 18).build();
            Screens.getWidgets(screen).add(skillTrees);
            ScreenEvents.beforeExtract(screen).register((ignored, graphics, mouseX, mouseY, delta) -> {
                int[] box = box(screen);
                skillTrees.visible = open && box != null;
                if (box != null) skillTrees.setPosition(box[0] + 6, box[1] + box[3] - FOOTER + 3);
            });
            ScreenEvents.afterTick(screen).register(ignored -> {
                if (open && ++sinceRequest >= REFRESH_TICKS) request();
            });
            ScreenMouseEvents.allowMouseScroll(screen).register((ignored, mouseX, mouseY, horizontal, vertical) -> {
                if (!inside(screen, mouseX, mouseY)) return true;
                scroll = (int) Math.max(0, Math.min(maxScroll(screen), scroll - vertical * 12));
                return false;
            });
            if (open) request();
        });
    }

    public static boolean isOpen() { return open; }

    public static void toggle() {
        open = !open;
        scroll = 0;
        if (open) request();
    }

    private static void request() {
        sinceRequest = 0;
        if (ClientPlayNetworking.canSend(CharacterSheet.RequestPayload.TYPE))
            ClientPlayNetworking.send(new CharacterSheet.RequestPayload());
    }

    /** The panel's x, y, width and height, or null while closed. */
    private static int[] box(net.minecraft.client.gui.screens.Screen screen) {
        if (!open || !(screen instanceof ContainerBounds bounds)) return null;
        return new int[] {bounds.cwp$leftPos() + bounds.cwp$imageWidth() + GAP, bounds.cwp$topPos(), WIDTH, bounds.cwp$imageHeight()};
    }

    public static boolean inside(net.minecraft.client.gui.screens.Screen screen, double mouseX, double mouseY) {
        int[] box = box(screen);
        return box != null && mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3];
    }

    private static int maxScroll(net.minecraft.client.gui.screens.Screen screen) {
        int[] box = box(screen);
        return box == null ? 0 : Math.max(0, contentHeight - (box[3] - HEADER - FOOTER));
    }

    /** Implemented by the inventory screen (through a mixin) so the panel can sit beside it. */
    public interface ContainerBounds {
        int cwp$leftPos();
        int cwp$topPos();
        int cwp$imageWidth();
        int cwp$imageHeight();
    }

    public static void draw(InventoryScreen screen, GuiGraphicsExtractor graphics, Font font) {
        int[] box = box(screen);
        if (box == null) return;
        int x = box[0], y = box[1], w = box[2], h = box[3];
        // Vanilla window look: light grey with a bevelled edge.
        graphics.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        graphics.fill(x, y, x + w - 1, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + h - 1, 0xFFFFFFFF);
        graphics.fill(x + 1, y + h - 1, x + w, y + h, 0xFF555555);
        graphics.fill(x + w - 1, y + 1, x + w, y + h, 0xFF555555);

        Minecraft client = Minecraft.getInstance();
        // Header: face, name and title on a dark inset.
        graphics.fill(x + 4, y + 4, x + w - 4, y + HEADER - 2, 0xFF373737);
        if (client.player != null) PlayerFaceExtractor.extractRenderState(graphics, client.player.getSkin(), x + 7, y + 7, 18);
        String name = sheet != null ? sheet.name() : client.player != null ? client.player.getGameProfile().name() : "";
        graphics.text(font, Component.literal(name).withStyle(ChatFormatting.BOLD), x + 29, y + 7, 0xFFFFFFFF, true);
        if (sheet != null && !sheet.title().isEmpty())
            graphics.text(font, fit(font, sheet.title(), w - 37), x + 29, y + 17, 0xFF000000 | TitleClient.color(sheet.titleColor()), true);
        else graphics.text(font, Component.translatable("screen.crazy-world-progression.character.no_title"), x + 29, y + 17, 0xFFA0A0A0, true);

        int top = y + HEADER, bottom = y + h - FOOTER;
        graphics.fill(x + 4, bottom + 1, x + w - 4, bottom + 2, 0xFF8B8B8B);
        if (sheet == null) {
            graphics.centeredText(font, Component.translatable("screen.crazy-world-progression.character.loading"), x + w / 2, top + 10, MUTED);
            return;
        }
        graphics.enableScissor(x + 2, top, x + w - 2, bottom);
        int row = top + 2 - scroll, left = x + 7, right = x + w - 7;
        row = section(graphics, font, "skills", left, right, row);
        for (CharacterSheet.SkillRow skill : sheet.skills()) {
            graphics.text(font, skill.label(), left, row, TEXT, false);
            String level = skill.nextXp() < 0 ? "Lv " + skill.level() + " ★" : "Lv " + skill.level();
            graphics.text(font, level, right - font.width(level), row, skill.nextXp() < 0 ? 0xFFB8860B : TEXT, false);
            double progress = skill.nextXp() < 0 ? 1.0
                    : (skill.xp() - skill.levelXp()) / (double) Math.max(1, skill.nextXp() - skill.levelXp());
            graphics.fill(left, row + 10, right, row + 12, 0xFF555555);
            graphics.fill(left, row + 10, left + (int) ((right - left) * Math.max(0, Math.min(1, progress))), row + 12, 0xFF3FA34D);
            row += 15;
        }
        row = stats(graphics, font, "attributes", sheet.attributes(), left, right, row);
        row = stats(graphics, font, "levels", sheet.levels(), left, right, row);
        row = stats(graphics, font, "bonuses", sheet.bonuses(), left, right, row);
        if (!sheet.abilities().isEmpty()) {
            row = section(graphics, font, "abilities", left, right, row);
            for (String ability : sheet.abilities()) {
                graphics.text(font, fit(font, Component.translatable(ability).getString(), right - left), left, row, 0xFF6A2FA0, false);
                row += 10;
            }
        }
        graphics.disableScissor();
        contentHeight = row + scroll - top;
        int view = bottom - top, max = Math.max(0, contentHeight - view);
        scroll = Math.min(scroll, max);
        if (max > 0) {
            int barHeight = Math.max(12, view * view / contentHeight);
            int barY = top + (view - barHeight) * scroll / max;
            graphics.fill(x + w - 5, barY, x + w - 3, barY + barHeight, 0xFF8B8B8B);
        }
    }

    private static int section(GuiGraphicsExtractor graphics, Font font, String key, int left, int right, int row) {
        row += 2;
        graphics.text(font, Component.translatable("screen.crazy-world-progression.character." + key)
                .withStyle(ChatFormatting.BOLD), left, row, 0xFF2B2B2B, false);
        graphics.fill(left, row + 9, right, row + 10, 0xFF8B8B8B);
        return row + 13;
    }

    private static int stats(GuiGraphicsExtractor graphics, Font font, String key, List<CharacterSheet.StatRow> rows,
                             int left, int right, int row) {
        if (rows.isEmpty()) return row;
        row = section(graphics, font, key, left, right, row);
        for (CharacterSheet.StatRow stat : rows) {
            String value = format(stat.format(), stat.value());
            graphics.text(font, fit(font, Component.translatable(stat.translationKey()).getString(), right - left - font.width(value) - 4),
                    left, row, TEXT, false);
            graphics.text(font, value, right - font.width(value), row, 0xFF1F4E8C, false);
            row += 10;
        }
        return row;
    }

    private static String format(StatValueFormat format, double value) {
        double normalized = Math.abs(value) < 0.0005 ? 0.0 : value;
        return switch (format) {
            case NUMBER -> compact(normalized, 2);
            case PERCENT -> compact(normalized * 100.0, 1) + "%";
            case MULTIPLIER -> compact(normalized, 2) + "×";
        };
    }

    private static String compact(double value, int places) {
        String formatted = String.format(Locale.ROOT, "%." + places + "f", value);
        while (formatted.contains(".") && formatted.endsWith("0")) formatted = formatted.substring(0, formatted.length() - 1);
        return formatted.endsWith(".") ? formatted.substring(0, formatted.length() - 1) : formatted;
    }

    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String shortened = text;
        while (!shortened.isEmpty() && font.width(shortened + "…") > width) shortened = shortened.substring(0, shortened.length() - 1);
        return shortened + "…";
    }
}
