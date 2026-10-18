package ekuzo.crazyworldprogression.admin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateValidator;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateYaml;
import ekuzo.crazyworldprogression.mobforge.template.TemplateValidationException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The MobForge mob editor (the only one; /mobforge editor opens it too). Size, movement speed and attack speed are
 * entered as multipliers of the entity type's own defaults; health, damage, follow range, armor and knockback as
 * absolute values. Entities and equipment are picked with the same type-to-search
 * fields as the loot pool editor.
 */
final class AdminMobEditor {
    private static final Gson JSON = new Gson();
    private static final Type OBJECT_MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private static final MobTemplateYaml YAML = new MobTemplateYaml();
    private static final Map<MinecraftServer, Map<UUID, Map<String, Draft>>> DRAFTS = new WeakHashMap<>();
    private static final List<String> AI_MODES = List.of("vanilla", "target_position", "target_player", "nearest_player");
    private static final List<String> YES_NO = List.of("Yes", "No");
    private static final List<String> DESPAWN = List.of("vanilla", "never");
    /** Entered as multipliers of the entity's default (1 = unchanged). */
    private static final List<String> MULTIPLIED = List.of("scale", "movement_speed", "attack_speed");
    /** Entered as absolute values, blank = the entity's default. */
    private static final List<String> ABSOLUTE = List.of("max_health", "attack_damage", "follow_range", "armor",
            "armor_toughness", "knockback_resistance", "attack_knockback");
    private static final List<String> SLOTS = List.of("main_hand", "off_hand", "head", "chest", "legs", "feet");
    static final String DRAFT_ACTION = "mob.draft";

    private AdminMobEditor() { }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, String templateId,
                                              List<AdminPanelRegistry.AdminTab> tabs, String backPage) {
        Draft draft = draft(player, pageId, templateId);
        Map<String, String> v = draft.fields;
        EntityType<?> type = entityType(v.get("entity"));
        String entityName = type == null ? "this entity" : type.getDescription().getString();
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();

        rows.add(row("Identity", "Mob", "The ID is fixed after creation; use Duplicate for a new ID.",
                List.of(text("id", "ID", "my_mob", v, 64), text("name", "Display name", "shown above the mob", v, 256)),
                List.of(), null));
        rows.add(row("Identity", "Entity type", type == null ? "Type to search" : entityName + " · " + v.get("entity"),
                List.of(new AdminPanelRegistry.AdminField("entity", "Entity type (type to search)", "zombie",
                        v.get("entity"), 128, entityIds())),
                List.of(), new AdminPanelRegistry.EntryPreview("entity", "field:entity", 0)));
        rows.add(row("Identity", "Tags", "Comma separated, for selecting mobs in scripts (such as raid, wave_1).",
                List.of(text("tags", "Tags", "raid, wave_1", v, 512)), List.of(), null));

        rows.add(row("Multipliers", "Size and speed", "1 = " + entityName + "'s normal value, 2 = double. "
                        + defaults(type, MULTIPLIED.toArray(String[]::new)),
                List.of(multiplier("scale", "Size ×", v), multiplier("movement_speed", "Movement speed ×", v),
                        multiplier("attack_speed", "Attack speed ×", v)), List.of(), null));
        rows.add(row("Values", "Combat", "Blank keeps the default. "
                        + defaults(type, "max_health", "attack_damage", "follow_range"),
                List.of(text("max_health", "Health", "default", v, 16), text("attack_damage", "Attack damage", "default", v, 16),
                        text("follow_range", "Follow range", "default", v, 16)), List.of(), null));
        rows.add(row("Values", "Defense", "Blank keeps the default. Knockback resistance goes from 0 to 1. "
                        + defaults(type, "armor", "armor_toughness", "knockback_resistance", "attack_knockback"),
                List.of(text("armor", "Armor", "default", v, 16), text("armor_toughness", "Toughness", "default", v, 16),
                        text("knockback_resistance", "Knockback res.", "default", v, 16),
                        text("attack_knockback", "Attack knockback", "default", v, 16)), List.of(), null));

        List<String> items = itemIds();
        for (String slot : SLOTS) {
            rows.add(row("Equipment", slotName(slot), "Type to search; leave empty for nothing.",
                    List.of(new AdminPanelRegistry.AdminField("equip_" + slot, slotName(slot) + " (type to search)", "empty",
                                    v.get("equip_" + slot), 128, items),
                            text("equip_" + slot + "_count", "Count", "1", v, 4)),
                    List.of(), new AdminPanelRegistry.EntryPreview("item", "field:equip_" + slot, 0)));
        }

        rows.add(row("Properties", "Behavior", "How the entity behaves in the world.",
                List.of(yesNo("persistent", "Persistent", v), yesNo("burns", "Burns in daylight", v),
                        yesNo("pickup", "Picks up loot", v), yesNo("silent", "Silent", v)), List.of(), null));
        rows.add(row("Properties", "Appearance and physics", "Despawn: vanilla rules or never.",
                List.of(yesNo("glowing", "Glowing", v), yesNo("invulnerable", "Invulnerable", v),
                        yesNo("gravity", "No gravity", v),
                        AdminPanelRegistry.AdminField.select("despawn", "Despawn", v.get("despawn"), DESPAWN)),
                List.of(), null));
        rows.add(row("Loot", "Death rewards", "XP -1 keeps the entity's normal amount.",
                List.of(yesNo("vanilla_loot", "Vanilla loot", v), text("xp", "XP", "-1", v, 16)), List.of(), null));
        rows.add(row("Loot", "Loot pool", "The player who lands the killing blow gets one roll in this pool. Empty for none.",
                List.of(new AdminPanelRegistry.AdminField("loot_pool", "Loot pool (type to search)", "none",
                        v.get("loot_pool"), 128, lootPools(player.level().getServer()))), List.of(), null));
        rows.add(row("AI", "Mode", "Choose what the mob goes for and whether it fights on the way.",
                List.of(AdminPanelRegistry.AdminField.select("ai_mode", "Mode", v.get("ai_mode"), AI_MODES),
                        yesNo("ai_combat", "Combat", v)), List.of(), null));
        switch (v.get("ai_mode")) {
            case "target_position" -> rows.add(row("AI", "Target position", "Where it walks and how close counts as there.",
                    List.of(text("ai_x", "X", "0", v, 32), text("ai_y", "Y", "64", v, 32), text("ai_z", "Z", "0", v, 32),
                            text("ai_arrival", "Arrival radius", "2", v, 32)), List.of(), null));
            case "target_player" -> rows.add(row("AI", "Target player", "The player it pursues.",
                    List.of(text("ai_player", "Player", "name", v, 64)), List.of(), null));
            case "nearest_player" -> rows.add(row("AI", "Player search", "How far it looks for the nearest player.",
                    List.of(text("ai_range", "Range", "64", v, 32)), List.of(), null));
            default -> { }
        }
        rows.add(row("AI", "Extra settings", "Additional AI options as a JSON object (advanced).",
                List.of(text("ai_settings", "Settings", "{}", v, 1024)), List.of(), null));

        rows.add(row("Actions", "Save mob", "Validate and save this mob.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.wide("mob.save", "Save mob", false),
                        action("mob.duplicate", "Duplicate"), AdminPanelRegistry.AdminAction.navigate("Back", backPage)),
                null));
        if (!draft.originId.isBlank()) rows.add(row("Actions", "Delete mob", "Remove this mob's YAML file.",
                List.of(), List.of(new AdminPanelRegistry.AdminAction("mob.delete", "Delete mob", true)), null));
        return new AdminPanelRegistry.AdminPage(pageId, draft.originId.isBlank() ? "Create MobForge mob" : "Edit MobForge mob",
                v.get("id"), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR, DRAFT_ACTION);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String templateId,
                                                   String actionId, Map<String, String> values, String backPage) {
        Draft draft = draft(player, pageId, templateId);
        String previousEntity = draft.fields.get("entity");
        String previousMode = draft.fields.get("ai_mode");
        draft.apply(values);
        if (actionId.equals(DRAFT_ACTION)) {
            // Typing is kept in the draft; the page only rebuilds when it changes what is shown.
            boolean reshape = !previousEntity.equals(draft.fields.get("entity")) && entityType(draft.fields.get("entity")) != null
                    || !previousMode.equals(draft.fields.get("ai_mode"));
            return reshape ? AdminPanelRegistry.ActionResult.success("") : AdminPanelRegistry.ActionResult.silentSuccess();
        }
        if (actionId.equals("mob.duplicate")) {
            String id = draft.fields.get("id");
            draft.fields.put("id", id.endsWith("_copy") ? id + "_2" : id + "_copy");
            draft.originId = "";
            return AdminPanelRegistry.ActionResult.success("Duplicate ready; save to create it");
        }
        if (actionId.equals("mob.save")) {
            MobTemplate template = draft.build();
            String pool = template.loot().lootPool();
            if (pool != null && ekuzo.crazyworldprogression.loot.LootPoolService.get(player.level().getServer(),
                    Identifier.parse(pool)) == null) throw new IllegalArgumentException("Unknown loot pool: " + pool);
            if (!draft.originId.isBlank() && !template.id().equals(draft.originId)) {
                throw new IllegalArgumentException("Use Duplicate to save under a new ID");
            }
            if (draft.originId.isBlank() && MobForge.TEMPLATES.get(template.id()).isPresent()) {
                throw new IllegalArgumentException("Mob ID already exists: " + template.id());
            }
            try { MobForge.TEMPLATES.save(template); }
            catch (IOException | TemplateValidationException exception) {
                throw new IllegalStateException("Could not save mob: " + exception.getMessage(), exception);
            }
            drafts(player).remove(pageId);
            return AdminPanelRegistry.ActionResult.success("Saved mob " + template.id(), backPage);
        }
        if (actionId.equals("mob.delete")) {
            if (draft.originId.isBlank()) throw new IllegalArgumentException("Only a saved mob can be deleted");
            try { MobForge.TEMPLATES.delete(draft.originId); }
            catch (IOException exception) { throw new IllegalStateException("Could not delete mob: " + exception.getMessage(), exception); }
            drafts(player).remove(pageId);
            return AdminPanelRegistry.ActionResult.success("Deleted mob " + draft.originId, backPage);
        }
        throw new IllegalArgumentException("Unknown mob editor action");
    }

    private static Draft draft(ServerPlayer player, String pageId, String templateId) {
        return drafts(player).computeIfAbsent(pageId, ignored -> {
            MobTemplate template = templateId.equals("@new")
                    ? new MobTemplate("new_mob", "minecraft:zombie", null, Map.of(), Map.of(),
                            null, null, List.of(), null)
                    : MobForge.TEMPLATES.get(templateId).orElseThrow(() -> new IllegalArgumentException(
                            "Unknown MobForge mob: " + templateId));
            return new Draft(template, templateId.equals("@new") ? "" : template.id());
        });
    }

    private static Map<String, Draft> drafts(ServerPlayer player) {
        return DRAFTS.computeIfAbsent(player.level().getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
    }

    private static EntityType<?> entityType(String value) {
        Identifier id = value == null ? null : Identifier.tryParse(value.trim());
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    /** For example "Defaults: size 1, movement speed 0.23, follow range 35". */
    private static String defaults(EntityType<?> type, String... attributes) {
        if (type == null) return "";
        List<String> parts = new ArrayList<>();
        for (String attribute : attributes) {
            double value = MobTemplateValidator.defaultValue(type, attribute);
            if (value >= 0) parts.add(attribute.replace('_', ' ') + " " + number(value));
        }
        return parts.isEmpty() ? "" : "Defaults: " + String.join(", ", parts) + ".";
    }

    private static List<String> lootPools(MinecraftServer server) {
        var pools = ekuzo.crazyworldprogression.loot.LootPoolService.all(server);
        return pools.values().stream().filter(pool -> !ekuzo.crazyworldprogression.loot.LootPoolService.nested(pools, pool.id()))
                .map(pool -> pool.id().toString()).sorted().toList();
    }

    private static List<String> entityIds() {
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Object::toString)
                .filter(id -> !id.equals("minecraft:player")).sorted().toList();
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(id -> !id.equals("minecraft:air")).sorted().toList();
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String slotName(String slot) {
        return switch (slot) {
            case "main_hand" -> "Main hand";
            case "off_hand" -> "Off hand";
            case "head" -> "Helmet";
            case "chest" -> "Chestplate";
            case "legs" -> "Leggings";
            case "feet" -> "Boots";
            default -> pretty(slot);
        };
    }

    private static String pretty(String key) {
        String value = key.replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static AdminPanelRegistry.AdminEntry row(String section, String title, String detail,
                                                      List<AdminPanelRegistry.AdminField> fields,
                                                      List<AdminPanelRegistry.AdminAction> actions,
                                                      AdminPanelRegistry.EntryPreview preview) {
        return new AdminPanelRegistry.AdminEntry(title, detail, AdminPanelRegistry.Tone.NORMAL, fields, actions,
                section, preview);
    }
    private static AdminPanelRegistry.AdminAction action(String id, String label) {
        return new AdminPanelRegistry.AdminAction(id, label, false);
    }
    private static AdminPanelRegistry.AdminField text(String id, String label, String placeholder,
                                                      Map<String, String> values, int limit) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, values.getOrDefault(id, ""), limit, List.of());
    }
    private static AdminPanelRegistry.AdminField multiplier(String attribute, String label, Map<String, String> values) {
        return text("mult_" + attribute, label, "1", values, 16);
    }
    private static AdminPanelRegistry.AdminField yesNo(String id, String label, Map<String, String> values) {
        return AdminPanelRegistry.AdminField.select(id, label, values.get(id), YES_NO);
    }
    private static Map<String, Object> objectMap(String json, String field) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> result = JSON.fromJson(json, OBJECT_MAP);
            if (result != null) return result;
        } catch (RuntimeException ignored) { }
        throw new IllegalArgumentException(field + " must be a JSON object");
    }
    private static boolean yes(Map<String, String> fields, String key) {
        return "Yes".equals(fields.get(key));
    }
    private static String yesNo(boolean value) { return value ? "Yes" : "No"; }
    private static double decimal(String value, String field) {
        try { return Double.parseDouble(value.trim().replace(',', '.')); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(field + " must be a number"); }
    }
    private static int integer(String value, String field) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(field + " must be a whole number"); }
    }

    private static final class Draft {
        private String originId;
        private final Map<String, String> fields = new LinkedHashMap<>();
        /** Equipment components are kept as they were; the editor does not change them. */
        private final Map<String, Map<String, Object>> components = new HashMap<>();
        /** Old absolute values of multiplier attributes that cannot be converted (entity without defaults). */
        private final Map<String, Double> keptAbsolute = new LinkedHashMap<>();
        private final Map<String, Object> originalAiSettings;
        private final String originalAiSettingsJson;

        private Draft(MobTemplate template, String originId) {
            this.originId = originId;
            fields.put("id", template.id()); fields.put("entity", template.entity());
            fields.put("name", template.name() == null ? "" : template.name());
            fields.put("tags", String.join(", ", template.tags()));
            EntityType<?> type = entityType(template.entity());
            for (String attribute : MULTIPLIED) {
                Double factor = template.multipliers().get(attribute);
                Double absolute = template.attributes().get(attribute);
                double base = type == null ? -1 : MobTemplateValidator.defaultValue(type, attribute);
                if (factor == null && absolute != null && base > 0) factor = absolute / base;
                else if (factor == null && absolute != null) keptAbsolute.put(attribute, absolute);
                fields.put("mult_" + attribute, factor == null ? "" : number(factor));
            }
            for (String attribute : ABSOLUTE) {
                Double value = template.attributes().get(attribute);
                Double factor = template.multipliers().get(attribute);
                double base = type == null ? -1 : MobTemplateValidator.defaultValue(type, attribute);
                if (value == null && factor != null && base >= 0) value = base * factor;
                fields.put(attribute, value == null ? "" : number(value));
            }
            for (String slot : SLOTS) {
                MobTemplate.EquipmentEntry entry = template.equipment().get(slot);
                fields.put("equip_" + slot, entry == null ? "" : entry.id());
                fields.put("equip_" + slot + "_count", entry == null ? "1" : Integer.toString(entry.count()));
                if (entry != null) components.put(slot, entry.components());
            }
            MobTemplate.Properties p = template.properties();
            fields.put("persistent", yesNo(p.persistent()));
            fields.put("burns", yesNo(p.burnsInDaylight()));
            fields.put("pickup", yesNo(p.canPickUpLoot()));
            fields.put("silent", yesNo(p.silent()));
            fields.put("glowing", yesNo(p.glowing()));
            fields.put("invulnerable", yesNo(p.invulnerable()));
            fields.put("gravity", yesNo(p.noGravity()));
            fields.put("despawn", DESPAWN.contains(p.despawnBehavior()) ? p.despawnBehavior() : "vanilla");
            fields.put("vanilla_loot", yesNo(template.loot().dropVanillaLoot()));
            fields.put("xp", Integer.toString(template.loot().xp()));
            fields.put("loot_pool", template.loot().lootPool() == null ? "" : template.loot().lootPool());
            MobTemplate.Ai ai = template.ai();
            fields.put("ai_mode", AI_MODES.contains(ai.mode()) ? ai.mode() : "vanilla");
            fields.put("ai_combat", yesNo(ai.combat()));
            fields.put("ai_x", ai.target() == null ? "0" : number(ai.target().x()));
            fields.put("ai_y", ai.target() == null ? "64" : number(ai.target().y()));
            fields.put("ai_z", ai.target() == null ? "0" : number(ai.target().z()));
            fields.put("ai_player", ai.player() == null ? "" : ai.player());
            fields.put("ai_arrival", number(ai.arrivalRadius()));
            fields.put("ai_range", number(ai.range()));
            originalAiSettings = ai.settings();
            originalAiSettingsJson = ai.settings().isEmpty() ? "" : JSON.toJson(ai.settings());
            fields.put("ai_settings", originalAiSettingsJson);
        }

        private void apply(Map<String, String> values) {
            fields.keySet().forEach(key -> { if (values.containsKey(key)) fields.put(key, values.get(key)); });
        }

        private MobTemplate build() {
            Map<String, Double> attributes = new LinkedHashMap<>(keptAbsolute);
            Map<String, Double> multipliers = new LinkedHashMap<>();
            for (String attribute : MULTIPLIED) {
                String value = fields.get("mult_" + attribute).trim();
                if (value.isEmpty()) continue;
                double factor = decimal(value, pretty(attribute) + " multiplier");
                attributes.remove(attribute);
                if (factor != 1.0) multipliers.put(attribute, factor);
            }
            for (String attribute : ABSOLUTE) {
                String value = fields.get(attribute).trim();
                if (!value.isEmpty()) attributes.put(attribute, decimal(value, pretty(attribute)));
            }
            Map<String, MobTemplate.EquipmentEntry> equipment = new LinkedHashMap<>();
            for (String slot : SLOTS) {
                String item = fields.get("equip_" + slot).trim();
                if (item.isEmpty()) continue;
                Identifier id = Identifier.tryParse(item.toLowerCase(java.util.Locale.ROOT));
                if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty())
                    throw new IllegalArgumentException("Unknown item for " + pretty(slot) + ": " + item);
                equipment.put(slot, new MobTemplate.EquipmentEntry(id.toString(),
                        integer(fields.get("equip_" + slot + "_count"), pretty(slot) + " count"), components.get(slot)));
            }
            MobTemplate.Properties p = new MobTemplate.Properties(yes(fields, "persistent"), yes(fields, "burns"),
                    yes(fields, "pickup"), yes(fields, "silent"), yes(fields, "glowing"),
                    yes(fields, "invulnerable"), yes(fields, "gravity"), fields.get("despawn"));
            String pool = fields.get("loot_pool").trim();
            if (!pool.isEmpty() && (Identifier.tryParse(pool) == null || !pool.contains(":")))
                throw new IllegalArgumentException("Loot pool must be an ID such as cwp:raid_rewards");
            MobTemplate.Loot loot = new MobTemplate.Loot(yes(fields, "vanilla_loot"), integer(fields.get("xp"), "XP"),
                    pool.isEmpty() ? null : pool);
            String mode = fields.get("ai_mode");
            MobTemplate.Position target = mode.equals("target_position") ? new MobTemplate.Position(
                    decimal(fields.get("ai_x"), "AI X"), decimal(fields.get("ai_y"), "AI Y"),
                    decimal(fields.get("ai_z"), "AI Z")) : null;
            String targetPlayer = fields.get("ai_player").isBlank() ? null : fields.get("ai_player").trim();
            MobTemplate.Ai ai = new MobTemplate.Ai(mode, yes(fields, "ai_combat"), target,
                    decimal(fields.get("ai_arrival"), "Arrival radius"), targetPlayer,
                    decimal(fields.get("ai_range"), "Range"), fields.get("ai_settings").equals(originalAiSettingsJson)
                            ? originalAiSettings : objectMap(fields.get("ai_settings"), "AI settings"));
            List<String> tags = java.util.Arrays.stream(fields.get("tags").split(","))
                    .map(String::trim).filter(tag -> !tag.isBlank()).toList();
            Identifier entity = Identifier.tryParse(fields.get("entity").trim().toLowerCase(java.util.Locale.ROOT));
            MobTemplate template = new MobTemplate(fields.get("id").trim(),
                    entity == null ? fields.get("entity").trim() : entity.toString(),
                    fields.get("name").isBlank() ? null : fields.get("name"), attributes, equipment,
                    p, loot, tags, ai, multipliers);
            return YAML.decode(YAML.encode(template));
        }
    }
}
