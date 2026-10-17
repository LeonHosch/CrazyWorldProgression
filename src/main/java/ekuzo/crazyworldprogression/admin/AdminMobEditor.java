package ekuzo.crazyworldprogression.admin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateYaml;
import ekuzo.crazyworldprogression.mobforge.template.TemplateValidationException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-owned MobForge template editor using the same compact rows as pockets and portals. */
final class AdminMobEditor {
    private static final Gson JSON = new Gson();
    private static final Type OBJECT_MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private static final MobTemplateYaml YAML = new MobTemplateYaml();
    private static final Map<MinecraftServer, Map<UUID, Map<String, Draft>>> DRAFTS = new WeakHashMap<>();
    private static final List<String> AI_MODES = List.of("vanilla", "target_position", "target_player", "nearest_player");
    private static final List<String> BOOLEAN = List.of("true", "false");

    private AdminMobEditor() { }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, String templateId,
                                              List<AdminPanelRegistry.AdminTab> tabs, String backPage) {
        Draft draft = draft(player, pageId, templateId);
        Map<String, String> v = draft.fields;
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(row("Identity", "Mob", "The ID is fixed after creation; use Duplicate for a new ID.",
                List.of(field("id", "ID", v, 64), field("entity", "Entity type", v, 128),
                        field("name", "Display name", v, 256)), List.of()));
        rows.add(row("Attributes", "Add attribute", "Use registry attribute IDs such as minecraft:max_health.",
                List.of(field("new_attribute", "Attribute", v, 128), field("new_attribute_value", "Value", v, 32)),
                List.of(action("mob.attr.add", "Add"))));
        for (int i = 0; i < draft.attributes.size(); i++) {
            int index = i;
            rows.add(row("Attributes", "Attribute " + (i + 1), "Numeric base value.",
                    List.of(field("attr_" + index + "_key", "Attribute", draft.attributes.get(i).key, 128),
                            field("attr_" + index + "_value", "Value", draft.attributes.get(i).value, 32)),
                    List.of(new AdminPanelRegistry.AdminAction("mob.attr.remove|" + i, "Remove", true))));
        }
        rows.add(row("Equipment", "Add equipment", "Slots: mainhand, offhand, head, chest, legs, feet.",
                List.of(field("new_slot", "Slot", v, 32), field("new_item", "Item", v, 128)),
                List.of(action("mob.equip.add", "Add"))));
        for (int i = 0; i < draft.equipment.size(); i++) {
            EquipmentForm equipment = draft.equipment.get(i);
            rows.add(row("Equipment", "Slot " + (i + 1), "Components are a JSON object.",
                    List.of(field("equip_" + i + "_slot", "Slot", equipment.slot, 32),
                            field("equip_" + i + "_item", "Item", equipment.item, 128),
                            field("equip_" + i + "_count", "Count", equipment.count, 16),
                            field("equip_" + i + "_components", "Components", equipment.components, 1024)),
                    List.of(new AdminPanelRegistry.AdminAction("mob.equip.remove|" + i, "Remove", true))));
        }
        rows.add(row("Properties", "Behavior", "How the entity behaves in the world.",
                List.of(select("persistent", "Persistent", v), select("burns", "Burns in daylight", v),
                        select("pickup", "Pick up loot", v), select("silent", "Silent", v)), List.of()));
        rows.add(row("Properties", "Appearance and physics", "Entity state flags.",
                List.of(select("glowing", "Glowing", v), select("invulnerable", "Invulnerable", v),
                        select("gravity", "No gravity", v), field("despawn", "Despawn behavior", v, 64)), List.of()));
        rows.add(row("Loot", "Vanilla drops", "XP -1 keeps the entity default.",
                List.of(select("vanilla_loot", "Drop vanilla loot", v), field("xp", "XP", v, 16)), List.of()));
        rows.add(row("Metadata", "Tags", "Comma separated tags.",
                List.of(field("tags", "Tags", v, 512)), List.of()));
        rows.add(row("AI", "Mode", "Choose target behavior and combat use.",
                List.of(AdminPanelRegistry.AdminField.select("ai_mode", "Mode", v.get("ai_mode"), AI_MODES),
                        select("ai_combat", "Combat", v)), List.of()));
        rows.add(row("AI", "Target", "Used by target_position or target_player.",
                List.of(field("ai_x", "X", v, 32), field("ai_y", "Y", v, 32), field("ai_z", "Z", v, 32),
                        field("ai_player", "Player", v, 64)), List.of()));
        rows.add(row("AI", "Distances", "Arrival radius and nearest-player search range.",
                List.of(field("ai_arrival", "Arrival radius", v, 32), field("ai_range", "Range", v, 32)), List.of()));
        rows.add(row("AI", "Extra settings", "Additional AI options as a JSON object.",
                List.of(field("ai_settings", "Settings", v, 1024)), List.of()));
        rows.add(row("Actions", "Save mob", "Validate and save this template YAML.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.wide("mob.save", "Save mob", false),
                        action("mob.duplicate", "Duplicate"), AdminPanelRegistry.AdminAction.navigate("Back", backPage))));
        if (!draft.originId.isBlank()) rows.add(row("Actions", "Delete mob", "Remove this YAML template.",
                List.of(), List.of(new AdminPanelRegistry.AdminAction("mob.delete", "Delete mob", true))));
        return new AdminPanelRegistry.AdminPage(pageId, "Edit MobForge mob", draft.fields.get("id"), tabs,
                rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String templateId,
                                                   String actionId, Map<String, String> values, String backPage) {
        Draft draft = draft(player, pageId, templateId);
        draft.apply(values);
        if (actionId.equals("mob.attr.add")) {
            String key = draft.fields.get("new_attribute").trim();
            if (key.isBlank()) throw new IllegalArgumentException("Enter an attribute ID");
            draft.attributes.add(new AttributeForm(key, draft.fields.get("new_attribute_value")));
            draft.fields.put("new_attribute", ""); draft.fields.put("new_attribute_value", "1");
            return AdminPanelRegistry.ActionResult.success("Added attribute");
        }
        if (actionId.equals("mob.equip.add")) {
            String slot = draft.fields.get("new_slot").trim();
            if (slot.isBlank()) throw new IllegalArgumentException("Enter an equipment slot");
            draft.equipment.add(new EquipmentForm(slot, draft.fields.get("new_item"), "1", Map.of()));
            draft.fields.put("new_slot", ""); draft.fields.put("new_item", "minecraft:iron_sword");
            return AdminPanelRegistry.ActionResult.success("Added equipment");
        }
        if (actionId.startsWith("mob.attr.remove|") || actionId.startsWith("mob.equip.remove|")) {
            int index = Integer.parseInt(actionId.substring(actionId.indexOf('|') + 1));
            if (actionId.startsWith("mob.attr")) draft.attributes.remove(index);
            else draft.equipment.remove(index);
            return AdminPanelRegistry.ActionResult.success("Removed entry");
        }
        if (actionId.equals("mob.duplicate")) {
            String id = draft.fields.get("id");
            draft.fields.put("id", id.endsWith("_copy") ? id + "_2" : id + "_copy");
            draft.originId = "";
            return AdminPanelRegistry.ActionResult.success("Duplicate ready; save to create it");
        }
        if (actionId.equals("mob.save")) {
            MobTemplate template = draft.build();
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

    private static AdminPanelRegistry.AdminEntry row(String section, String title, String detail,
                                                      List<AdminPanelRegistry.AdminField> fields,
                                                      List<AdminPanelRegistry.AdminAction> actions) {
        return AdminPanelRegistry.AdminEntry.editor(section, title, detail, fields, actions);
    }
    private static AdminPanelRegistry.AdminAction action(String id, String label) {
        return new AdminPanelRegistry.AdminAction(id, label, false);
    }
    private static AdminPanelRegistry.AdminField field(String id, String label, Map<String, String> values, int limit) {
        return field(id, label, values.getOrDefault(id, ""), limit);
    }
    private static AdminPanelRegistry.AdminField field(String id, String label, String value, int limit) {
        return new AdminPanelRegistry.AdminField(id, label, "", value, limit, List.of());
    }
    private static AdminPanelRegistry.AdminField select(String id, String label, Map<String, String> values) {
        return AdminPanelRegistry.AdminField.select(id, label, values.get(id), BOOLEAN);
    }
    private static Map<String, Object> objectMap(String json, String field) {
        try {
            Map<String, Object> result = JSON.fromJson(json, OBJECT_MAP);
            if (result != null) return result;
        } catch (RuntimeException ignored) { }
        throw new IllegalArgumentException(field + " must be a JSON object");
    }
    private static boolean bool(Map<String, String> fields, String key) {
        return Boolean.parseBoolean(fields.get(key));
    }
    private static double decimal(String value, String field) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(field + " must be a number"); }
    }
    private static int integer(String value, String field) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(field + " must be an integer"); }
    }

    private static final class Draft {
        private String originId;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final List<AttributeForm> attributes = new ArrayList<>();
        private final List<EquipmentForm> equipment = new ArrayList<>();
        private final Map<String, Object> originalAiSettings;
        private final String originalAiSettingsJson;

        private Draft(MobTemplate template, String originId) {
            this.originId = originId;
            fields.put("id", template.id()); fields.put("entity", template.entity());
            fields.put("name", template.name() == null ? "" : template.name());
            fields.put("new_attribute", ""); fields.put("new_attribute_value", "1");
            fields.put("new_slot", ""); fields.put("new_item", "minecraft:iron_sword");
            template.attributes().forEach((key, value) -> attributes.add(new AttributeForm(key, value.toString())));
            template.equipment().forEach((slot, entry) -> equipment.add(new EquipmentForm(slot, entry.id(),
                    Integer.toString(entry.count()), entry.components())));
            MobTemplate.Properties p = template.properties();
            fields.put("persistent", Boolean.toString(p.persistent()));
            fields.put("burns", Boolean.toString(p.burnsInDaylight()));
            fields.put("pickup", Boolean.toString(p.canPickUpLoot()));
            fields.put("silent", Boolean.toString(p.silent()));
            fields.put("glowing", Boolean.toString(p.glowing()));
            fields.put("invulnerable", Boolean.toString(p.invulnerable()));
            fields.put("gravity", Boolean.toString(p.noGravity()));
            fields.put("despawn", p.despawnBehavior());
            fields.put("vanilla_loot", Boolean.toString(template.loot().dropVanillaLoot()));
            fields.put("xp", Integer.toString(template.loot().xp()));
            fields.put("tags", String.join(",", template.tags()));
            MobTemplate.Ai ai = template.ai();
            fields.put("ai_mode", ai.mode()); fields.put("ai_combat", Boolean.toString(ai.combat()));
            fields.put("ai_x", ai.target() == null ? "0" : Double.toString(ai.target().x()));
            fields.put("ai_y", ai.target() == null ? "64" : Double.toString(ai.target().y()));
            fields.put("ai_z", ai.target() == null ? "0" : Double.toString(ai.target().z()));
            fields.put("ai_player", ai.player() == null ? "" : ai.player());
            fields.put("ai_arrival", Double.toString(ai.arrivalRadius()));
            fields.put("ai_range", Double.toString(ai.range()));
            originalAiSettings = ai.settings();
            originalAiSettingsJson = JSON.toJson(ai.settings());
            fields.put("ai_settings", originalAiSettingsJson);
        }

        private void apply(Map<String, String> values) {
            fields.keySet().forEach(key -> { if (values.containsKey(key)) fields.put(key, values.get(key)); });
            for (int i = 0; i < attributes.size(); i++) {
                AttributeForm row = attributes.get(i);
                row.key = values.getOrDefault("attr_" + i + "_key", row.key);
                row.value = values.getOrDefault("attr_" + i + "_value", row.value);
            }
            for (int i = 0; i < equipment.size(); i++) {
                EquipmentForm row = equipment.get(i);
                row.slot = values.getOrDefault("equip_" + i + "_slot", row.slot);
                row.item = values.getOrDefault("equip_" + i + "_item", row.item);
                row.count = values.getOrDefault("equip_" + i + "_count", row.count);
                row.components = values.getOrDefault("equip_" + i + "_components", row.components);
            }
        }

        private MobTemplate build() {
            Map<String, Double> attributeValues = new LinkedHashMap<>();
            for (AttributeForm row : attributes) {
                if (attributeValues.putIfAbsent(row.key.trim(), decimal(row.value, row.key)) != null)
                    throw new IllegalArgumentException("Duplicate attribute: " + row.key);
            }
            Map<String, MobTemplate.EquipmentEntry> equipmentValues = new LinkedHashMap<>();
            for (EquipmentForm row : equipment) {
                MobTemplate.EquipmentEntry entry = new MobTemplate.EquipmentEntry(row.item.trim(),
                        integer(row.count, row.slot + " count"), row.components.equals(row.originalComponentsJson)
                                ? row.originalComponents : objectMap(row.components, row.slot + " components"));
                if (equipmentValues.putIfAbsent(row.slot.trim(), entry) != null)
                    throw new IllegalArgumentException("Duplicate slot: " + row.slot);
            }
            MobTemplate.Properties p = new MobTemplate.Properties(bool(fields, "persistent"), bool(fields, "burns"),
                    bool(fields, "pickup"), bool(fields, "silent"), bool(fields, "glowing"),
                    bool(fields, "invulnerable"), bool(fields, "gravity"), fields.get("despawn").trim());
            MobTemplate.Loot loot = new MobTemplate.Loot(bool(fields, "vanilla_loot"), integer(fields.get("xp"), "XP"));
            String mode = fields.get("ai_mode");
            MobTemplate.Position target = mode.equals("target_position") ? new MobTemplate.Position(
                    decimal(fields.get("ai_x"), "AI X"), decimal(fields.get("ai_y"), "AI Y"),
                    decimal(fields.get("ai_z"), "AI Z")) : null;
            String targetPlayer = fields.get("ai_player").isBlank() ? null : fields.get("ai_player").trim();
            MobTemplate.Ai ai = new MobTemplate.Ai(mode, bool(fields, "ai_combat"), target,
                    decimal(fields.get("ai_arrival"), "Arrival radius"), targetPlayer,
                    decimal(fields.get("ai_range"), "Range"), fields.get("ai_settings").equals(originalAiSettingsJson)
                            ? originalAiSettings : objectMap(fields.get("ai_settings"), "AI settings"));
            List<String> tags = java.util.Arrays.stream(fields.get("tags").split(","))
                    .map(String::trim).filter(tag -> !tag.isBlank()).toList();
            MobTemplate template = new MobTemplate(fields.get("id").trim(), fields.get("entity").trim(),
                    fields.get("name").isBlank() ? null : fields.get("name"), attributeValues, equipmentValues,
                    p, loot, tags, ai);
            return YAML.decode(YAML.encode(template));
        }
    }
    private static final class AttributeForm {
        private String key;
        private String value;
        private AttributeForm(String key, String value) { this.key = key; this.value = value; }
    }
    private static final class EquipmentForm {
        private String slot;
        private String item;
        private String count;
        private String components;
        private final Map<String, Object> originalComponents;
        private final String originalComponentsJson;
        private EquipmentForm(String slot, String item, String count, Map<String, Object> components) {
            this.slot = slot; this.item = item; this.count = count;
            this.originalComponents = components;
            this.originalComponentsJson = JSON.toJson(components);
            this.components = originalComponentsJson;
        }
    }
}
