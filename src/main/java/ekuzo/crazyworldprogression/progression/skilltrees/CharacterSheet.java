package ekuzo.crazyworldprogression.progression.skilltrees;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatSnapshot;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatValueFormat;
import ekuzo.crazyworldprogression.skills.Skills;
import ekuzo.crazyworldprogression.titles.TitleService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The character sheet shown beside the inventory: the player's name, equipped title, skill levels, stats (every stat a
 * skill tree uses, with global and personal boosts and worn sets included) and unlocked abilities. Sent only when the
 * client asks (while the sheet is open), so it costs the server nothing otherwise.
 */
public final class CharacterSheet {
    private static final Gson GSON = new Gson();

    public record Sheet(String name, String title, String titleColor, List<SkillRow> skills, List<StatRow> attributes,
                        List<StatRow> levels, List<StatRow> bonuses, List<String> abilities) { }

    /** levelXp: total XP where this level began; nextXp: total XP of the next level, -1 at the max level. */
    public record SkillRow(String label, int level, int xp, int levelXp, int nextXp) { }

    public record StatRow(String translationKey, StatValueFormat format, double value) { }

    private CharacterSheet() { }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(RequestPayload.TYPE, RequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> ekuzo.crazyworldprogression.CwpPayloads.send(context.player(),
                        new Payload(GSON.toJson(create(context.player()))))));
    }

    public static Sheet create(ServerPlayer player) {
        TitleService.Definition title = TitleService.equipped(player);
        List<SkillRow> skills = new ArrayList<>();
        for (Skills.Skill skill : Skills.Skill.values()) {
            int xp = Skills.xp(player, skill), level = Skills.levelFor(xp);
            skills.add(new SkillRow(skill.label(), level, xp, Skills.threshold(level),
                    level >= Skills.MAX_LEVEL ? -1 : Skills.threshold(level + 1)));
        }

        // Personal rows already include the global boost; global-only stats are added after them.
        Map<String, StatSnapshot> rows = new LinkedHashMap<>();
        for (StatSnapshot row : SkillStatSheetService.create(player, SkillTreeDefinition.SkillTreeType.PERSONAL))
            rows.put(row.translationKey(), row);
        for (StatSnapshot row : SkillStatSheetService.create(player, SkillTreeDefinition.SkillTreeType.GLOBAL))
            rows.putIfAbsent(row.translationKey(), row);

        MinecraftServer server = player.level().getServer();
        Map<String, Double> totals = new java.util.HashMap<>(SkillStatRegistry.getTotals(server, player.getUUID(),
                SkillTreeDefinition.SkillTreeType.GLOBAL));
        SkillStatRegistry.getTotals(server, player.getUUID(), SkillTreeDefinition.SkillTreeType.PERSONAL)
                .forEach((key, value) -> totals.merge(key, value, Double::sum));

        List<StatRow> attributes = new ArrayList<>(), levels = new ArrayList<>(), bonuses = new ArrayList<>();
        List<String> abilities = new ArrayList<>();
        for (SkillStatRegistry.StatDefinition definition : SkillStatRegistry.definitions()) {
            if (SkillStatRegistry.isUnlockDefinition(definition)) {
                if (totals.getOrDefault(definition.key(), 0.0) > 0) abilities.add(definition.translationKey() + ".name");
                continue;
            }
            StatSnapshot row = rows.get(definition.translationKey());
            if (row == null) continue;
            StatRow stat = new StatRow(row.translationKey(), row.format(), row.totalValue());
            if (definition.attribute() != null) attributes.add(stat);
            else if (definition.format() == StatValueFormat.NUMBER) levels.add(stat);
            else bonuses.add(stat);
        }
        // Armor comes from what is worn, not from skill trees, but belongs on a character sheet.
        attributes.add(new StatRow("attribute.name.armor", StatValueFormat.NUMBER, player.getAttributeValue(Attributes.ARMOR)));
        attributes.add(new StatRow("attribute.name.armor_toughness", StatValueFormat.NUMBER,
                player.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));

        return new Sheet(player.getGameProfile().name(), title == null ? "" : title.name(),
                title == null ? "#FFFFFF" : title.color(), skills, attributes, levels, bonuses, abilities);
    }

    public static Sheet decode(String json) { return GSON.fromJson(json, Sheet.class); }

    public record RequestPayload() implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(CrazyWorldProgression.id("request_character_sheet"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = StreamCodec.unit(new RequestPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("character_sheet"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
