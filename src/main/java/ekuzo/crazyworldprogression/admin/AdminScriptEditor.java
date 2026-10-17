package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptYaml;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** MobForge's complete step editor as server-backed rows inside the CWP admin panel. */
final class AdminScriptEditor {
    private static final Map<MinecraftServer, Map<UUID, Map<String, Draft>>> DRAFTS = new WeakHashMap<>();
    private static final List<String> TYPES = List.of("Spawn mobs", "Sleep", "If", "While",
            "Event box", "Check redstone power", "Spawn portal", "Track counter", "Variable",
            "Particles", "Explosion", "Play sound", "Apply effect", "Remove effect", "Loot pool", "Unlock achievement", "Encounter start", "Encounter end",
            "Spawn building", "Despawn building");
    private static final List<String> ADVANCED_TYPES = List.of("Spawn wave", "Spawn entity", "Wait until",
            "Mob cast", "Set phase", "Trigger event", "Reward currency", "Reward item", "Reward loot table",
            "Watch event", "Portal location", "Raid complete");
    private static final List<String> AI_MODES = List.of("template_default", "vanilla", "target_position",
            "target_player", "nearest_player");
    private static final MobScriptYaml YAML = new MobScriptYaml();

    private AdminScriptEditor() { }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, String backPage,
                                              Identifier pocketId, String scriptId,
                                              List<AdminPanelRegistry.AdminTab> tabs) {
        requirePocket(player, pocketId);
        Draft draft = draft(player, pageId, pocketId, scriptId);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(row("Script", "Identity", "Lowercase letters, numbers, _ and -.",
                List.of(field("script_id", "Script ID", draft.id, 64)), List.of()));
        rows.add(row("Timeline", "Add step", "Steps run from top to bottom.",
                List.of(AdminPanelRegistry.AdminField.select("new_step_type", "Type", TYPES.getFirst(), TYPES)),
                List.of(new AdminPanelRegistry.AdminAction("script.step.add", "Add", false))));
        rows.add(row("Timeline", "Advanced and older steps",
                "Ability, reward, anchor, and legacy script actions remain available.",
                List.of(AdminPanelRegistry.AdminField.select("advanced_step_type", "Type",
                        ADVANCED_TYPES.getFirst(), ADVANCED_TYPES)),
                List.of(new AdminPanelRegistry.AdminAction("script.step.add_advanced", "Add", false))));
        int nesting = 0;
        for (int i = 0; i < draft.steps.size(); i++) {
            StepForm step = draft.steps.get(i);
            String prefix = "step_" + i + "_";
            if (step.type.equals("Else") || step.type.equals("End if") || step.type.equals("End loop"))
                nesting = Math.max(0, nesting - 1);
            String section = (i + 1) + ". " + "  ".repeat(nesting) + step.type;
            List<AdminPanelRegistry.AdminAction> controls = new ArrayList<>();
            if (!List.of("If", "Else", "End if", "While", "End loop").contains(step.type)) {
                controls.add(new AdminPanelRegistry.AdminAction("script.step.up|" + i, "Up", false));
                controls.add(new AdminPanelRegistry.AdminAction("script.step.down|" + i, "Down", false));
            }
            if (!step.type.equals("End if") && !step.type.equals("End loop"))
                controls.add(new AdminPanelRegistry.AdminAction("script.step.insert|" + i, "Insert after", false));
            if (step.type.equals("If")) controls.add(new AdminPanelRegistry.AdminAction("script.step.else|" + i, "Add else", false));
            if (!step.type.equals("End if") && !step.type.equals("End loop"))
                controls.add(new AdminPanelRegistry.AdminAction("script.step.remove|" + i,
                        List.of("If", "While").contains(step.type) ? "Remove block" : "Remove", true));
            rows.add(row(section, "Step controls", "Insert or manage this step.", List.of(), controls));
            switch (step.type) {
                case "Spawn mobs" -> {
                    rows.add(row(section, "Mob and count", "Use a namespaced MobForge template, API mob, or Minecraft mob ID.",
                            List.of(suggested(prefix, step, "mob_id", "Mob ID", entityIds()),
                                    field(prefix, step, "count", "Count")), List.of()));
                    rows.add(row(section, "Location", "World coordinates; pocket scripts move with their instance.",
                            coordinates(prefix, step, "x", "y", "z"),
                            List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                    rows.add(row(section, "Offset", "Random spread in blocks around the location.",
                            coordinates(prefix, step, "ox", "oy", "oz"), List.of()));
                }
                case "Spawn wave" -> {
                    rows.add(row(section, "Mob and count", "Spawn a loaded MobForge template.",
                            List.of(suggested(prefix, step, "template", "Mob template", templateIds()),
                                    field(prefix, step, "count", "Count")), List.of()));
                    rows.add(row(section, "Location", "Build-space coordinates; translated into each instance.",
                            coordinates(prefix, step, "x", "y", "z"),
                            List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                    rows.add(row(section, "Random offset", "Random spread around the location.",
                            coordinates(prefix, step, "ox", "oy", "oz"), List.of()));
                    rows.add(row(section, "AI override", "Template default or a specific AI mode.",
                            List.of(AdminPanelRegistry.AdminField.select(prefix + "ai_mode", "AI mode",
                                            step.value("ai_mode"), AI_MODES),
                                    AdminPanelRegistry.AdminField.select(prefix + "combat", "Combat",
                                            step.value("combat"), List.of("true", "false"))), List.of()));
                    rows.add(row(section, "AI target", "Used when AI mode is target_position.",
                            coordinates(prefix, step, "tx", "ty", "tz"), List.of()));
                    rows.add(row(section, "AI options", "Radius, player, or range apply to the selected AI mode.",
                            List.of(field(prefix, step, "arrival", "Arrival radius"),
                                    field(prefix, step, "player", "Target player"),
                                    field(prefix, step, "range", "Search range")), List.of()));
                }
                case "Sleep" -> rows.add(row(section, "Duration", "Examples: 40t, 5s, 500ms, 2m.",
                        List.of(field(prefix, step, "sleep", "Duration")), List.of()));
                case "Spawn entity" -> {
                    rows.add(row(section, "Entity", "Namespaced MobForge, template, or Minecraft entity ID.",
                            List.of(suggested(prefix, step, "entity", "Entity ID", entityIds())), List.of()));
                    rows.add(row(section, "Anchor and handle", "origin and pocket_spawn are available in portal instances.",
                            List.of(field(prefix, step, "anchor", "Anchor"),
                                    field(prefix, step, "save_as", "Save as")), List.of()));
                }
                case "Wait until" -> rows.add(row(section, "Condition", "Example: ${guardian.health_percent <= 0.5}.",
                        List.of(field(prefix, step, "condition", "Condition"),
                                field(prefix, step, "timeout", "Timeout")), List.of()));
                case "Mob cast" -> rows.add(row(section, "Ability", "Use a handle from an earlier spawn.",
                        List.of(field(prefix, step, "mob", "Mob handle"),
                                suggested(prefix, step, "ability", "Ability ID", abilityIds())), List.of()));
                case "Set phase" -> rows.add(row(section, "Phase", "Use a handle from an earlier spawn.",
                        List.of(field(prefix, step, "mob", "Mob handle"),
                                field(prefix, step, "phase", "Phase")), List.of()));
                case "Trigger event" -> rows.add(row(section, "Event", "Namespaced event ID.",
                        List.of(field(prefix, step, "event", "Event ID")), List.of()));
                case "Reward currency" -> rows.add(row(section, "Currency", "Give this amount to every player currently in the pocket instance.",
                        List.of(suggested(prefix, step, "currency", "Player currency", CurrencyRegistry.values().stream()
                                        .filter(currency -> currency.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                                        .map(currency -> currency.id().toString()).sorted().toList()),
                                field(prefix, step, "amount", "Amount")), List.of()));
                case "Reward item" -> rows.add(row(section, "Item loot", "Give this item to every player currently in the pocket instance.",
                        List.of(suggested(prefix, step, "item", "Item ID", BuiltInRegistries.ITEM.keySet().stream()
                                        .map(Object::toString).sorted().toList()),
                                field(prefix, step, "count", "Count")), List.of()));
                case "Reward loot table" -> rows.add(row(section, "Random loot", "Roll this chest loot table separately for each player in the pocket instance.",
                        List.of(field(prefix, step, "loot_table", "Loot table ID"),
                                field(prefix, step, "rolls", "Rolls per player")), List.of()));
                case "Loot pool" -> rows.add(row(section, "Saved loot pool",
                        "Roll this pool for every player in the instance, or a player list variable from an event box.",
                        List.of(suggested(prefix, step, "lootpool_id", "Loot pool ID",
                                        LootPoolService.all(player.level().getServer()).keySet().stream().sorted().toList()),
                                field(prefix, step, "player", "Player variable or UUID (optional)")), List.of()));
                case "Unlock achievement" -> rows.add(row(section, "Achievement unlock",
                        "Award a Minecraft advancement to each player in this instance, or a saved player list.",
                        List.of(field(prefix, step, "achievement_id", "Achievement ID"),
                                field(prefix, step, "player", "Player variable or UUID (optional)")), List.of()));
                case "Variable" -> rows.add(row(section, "Variable", "Set a number or true/false, or increment a number.",
                        List.of(field(prefix, step, "name", "Name"),
                                AdminPanelRegistry.AdminField.select(prefix + "operation", "Operation", step.value("operation"), List.of("set", "increment")),
                                field(prefix, step, "value", "Value")), List.of()));
                case "Watch event" -> rows.add(row(section, "Watched event", "On each false-to-true transition, change a run variable. Example: ${box.plate >= 2}.",
                        List.of(field(prefix, step, "condition", "Condition"), field(prefix, step, "variable", "Variable"),
                                AdminPanelRegistry.AdminField.select(prefix + "operation", "Operation", step.value("operation"), List.of("set", "increment")),
                                field(prefix, step, "value", "Value")), List.of()));
                case "Event box" -> {
                    rows.add(row(section, "Event box", "Count players or mobs. A player box can collect every player who enters for later effects.",
                            List.of(field(prefix, step, "name", "Name"),
                                    AdminPanelRegistry.AdminField.select(prefix + "mode", "Checks", step.value("mode"),
                                            List.of("players", "mobs")),
                                    suggested(prefix, step, "mob_id", "Mob ID (mobs mode)", entityIds()),
                                    field(prefix, step, "player_variable", "Save entered players as (optional)"),
                                    field(prefix, step, "count_variable", "Save current count as (optional)")), List.of()));
                    rows.add(row(section, "Minimum corner", "Build-space coordinates move with each pocket instance.",
                            List.of(field(prefix, step, "x1", "X"), field(prefix, step, "y1", "Y"),
                                    field(prefix, step, "z1", "Z")), List.of()));
                    rows.add(row(section, "Maximum corner", "The maximum block is included.",
                            List.of(field(prefix, step, "x2", "X"), field(prefix, step, "y2", "Y"),
                                    field(prefix, step, "z2", "Z")), List.of()));
                }
                case "Redstone sensor" -> rows.add(row(section, "Redstone sensor", "Checks whether the block at XYZ receives redstone power.",
                        List.of(field(prefix, step, "name", "Name"), field(prefix, step, "x", "X"),
                                field(prefix, step, "y", "Y"), field(prefix, step, "z", "Z")), List.of()));
                case "If", "While" -> rows.add(row(section, "Condition", "Examples: kills('cwp:guardian') >= 6, box_triggers('arena') >= 2, powered(1, 64, 2).",
                        step.type.equals("While")
                                ? List.of(field(prefix, step, "condition", "Condition"), field(prefix, step, "max", "Max iterations"))
                                : List.of(field(prefix, step, "condition", "Condition")), List.of()));
                case "Else", "End if", "End loop" -> rows.add(row(section, "Control flow", "Pairs with the nearest open If or Loop.", List.of(), List.of()));
                case "Raid complete" -> rows.add(row(section, "Encounter complete", "Resets each player's revive charge and once-per-attempt portal use in this raid instance.", List.of(), List.of()));
                case "Portal location" -> {
                    rows.add(row(section, "Portal preset placement", "Add or remove a named location; clear removes all locations in this instance.",
                            List.of(suggested(prefix, step, "portal", "Portal ID", ekuzo.crazyworldprogression.portals.PortalService.portals(player.level().getServer()).stream().map(p -> p.id().toString()).toList()),
                                    AdminPanelRegistry.AdminField.select(prefix + "action", "Action", step.value("action"), List.of("add", "remove", "clear")),
                                    field(prefix, step, "location", "Location name")), List.of()));
                    rows.add(row(section, "Placement", "Used by Add; build-space coordinates move with the pocket instance.",
                            List.of(field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                    field(prefix, step, "z", "Z")),
                            List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                    rows.add(row(section, "Destination override", "Optional Coordinates portal target in this pocket's build-space coordinates.",
                            List.of(AdminPanelRegistry.AdminField.select(prefix + "target_enabled", "Use target",
                                            step.value("target_enabled"), List.of("No", "Yes")),
                                    field(prefix, step, "tx", "Target X"), field(prefix, step, "ty", "Target Y"),
                                    field(prefix, step, "tz", "Target Z")), List.of()));
                }
                case "Check redstone power" -> rows.add(row(section, "Power check",
                        "Wait for the block at these coordinates to have the selected power state. Works in worlds and instances.",
                        List.of(field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                field(prefix, step, "z", "Z"),
                                AdminPanelRegistry.AdminField.select(prefix + "powered", "Power state",
                                        step.value("powered"), List.of("powered", "unpowered")),
                                field(prefix, step, "timeout", "Timeout")),
                        List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                case "Spawn portal" -> rows.add(row(section, "Spawn portal",
                        "Place this saved portal at the selected location in this world or instance.",
                        List.of(suggested(prefix, step, "portal", "Portal ID",
                                        ekuzo.crazyworldprogression.portals.PortalService.portals(player.level().getServer())
                                                .stream().map(p -> p.id().toString()).toList()),
                                field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                field(prefix, step, "z", "Z")),
                        List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                case "Track counter" -> rows.add(row(section, "Track counter",
                        "Adds each mob kill or event box activation to a run variable.",
                        List.of(AdminPanelRegistry.AdminField.select(prefix + "source", "Source",
                                        step.value("source"), List.of("mob kills", "event box triggers")),
                                field(prefix, step, "source_id", "Mob ID or event box name"),
                                field(prefix, step, "variable", "Variable name")), List.of()));
                case "Encounter start", "Encounter end" -> rows.add(row(section, "Raid encounter",
                        step.type.equals("Encounter start") ? "Begin the one-revive-per-player attempt."
                                : "Finish the attempt and reset revive and entry locks.", List.of(), List.of()));
                case "Spawn building", "Despawn building" -> rows.add(row(section, "Saved building",
                        step.type.equals("Spawn building") ? "Paste the saved block range here."
                                : "Clear the entire pasted block range here.",
                        List.of(suggested(prefix, step, "build_id", "Building ID",
                                        BuildingService.buildings(player.level().getServer()).stream()
                                                .map(value -> value.id().toString()).toList()),
                                field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                field(prefix, step, "z", "Z")),
                        List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                case "Particles" -> {
                    rows.add(row(section, "Particle effect", "Use a simple particle ID, count, and speed.",
                            List.of(suggested(prefix, step, "particle_id", "Particle ID",
                                            BuiltInRegistries.PARTICLE_TYPE.keySet().stream()
                                                    .filter(id -> BuiltInRegistries.PARTICLE_TYPE.getValue(id)
                                                            instanceof net.minecraft.core.particles.ParticleOptions)
                                                    .map(Object::toString).sorted().toList()),
                                    field(prefix, step, "count", "Count"), field(prefix, step, "speed", "Speed")),
                            List.of()));
                    rows.add(row(section, "Location and spread", "The offset spreads particles around the center.",
                            List.of(field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                    field(prefix, step, "z", "Z"), field(prefix, step, "ox", "Offset X"),
                                    field(prefix, step, "oy", "Offset Y"), field(prefix, step, "oz", "Offset Z")),
                            List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                }
                case "Explosion" -> rows.add(row(section, "Explosion",
                        "Deals damage and shows an explosion without breaking blocks.",
                        List.of(field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                field(prefix, step, "z", "Z"), field(prefix, step, "power", "Power (0-8)")),
                        List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                case "Play sound" -> rows.add(row(section, "Sound effect",
                        "Play a sound at this position in the world or pocket instance.",
                        List.of(suggested(prefix, step, "sound_id", "Sound ID",
                                        BuiltInRegistries.SOUND_EVENT.keySet().stream()
                                                .map(Object::toString).sorted().toList()),
                                field(prefix, step, "x", "X"), field(prefix, step, "y", "Y"),
                                field(prefix, step, "z", "Z")),
                        List.of(new AdminPanelRegistry.AdminAction("script.step.here|" + i, "Here", false))));
                case "Apply effect", "Remove effect" -> rows.add(row(section, "Player effect",
                        "Leave Player ID blank for everyone in the instance. A saved event box variable targets its full player list.",
                        List.of(suggested(prefix, step, "effect_id", "Effect ID",
                                        BuiltInRegistries.MOB_EFFECT.keySet().stream().map(Object::toString).sorted().toList()),
                                field(prefix, step, "player_id", "Player list variable or UUID (optional)"),
                                field(prefix, step, "amplifier", "Amplifier (0 = level I; Apply only)")), List.of()));
                default -> throw new IllegalStateException("Unknown script step: " + step.type);
            }
            if (step.type.equals("If") || step.type.equals("Else") || step.type.equals("While")) nesting++;
        }
        rows.add(row("Actions", "Save script", "Validates and saves MobForge .mfs source.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.wide("script.save", "Save script", false),
                        new AdminPanelRegistry.AdminAction("script.duplicate", "Duplicate", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", backPage))));
        if (!draft.originId.isBlank()) rows.add(row("Actions", "Delete script", "Deletes this script globally.",
                List.of(), List.of(new AdminPanelRegistry.AdminAction("script.delete", "Delete script", true))));
        return new AdminPanelRegistry.AdminPage(pageId, "Edit MobForge script",
                (pocketId == null ? "MobForge" : "Pocket " + pocketId) + " · " + draft.steps.size() + " steps",
                tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String actionId,
                                                   Map<String, String> values, String backPage,
                                                   Identifier pocketId, String scriptId) {
        requirePocket(player, pocketId);
        Draft draft = draft(player, pageId, pocketId, scriptId);
        draft.apply(values);
        if (actionId.equals("script.step.add") || actionId.equals("script.step.add_advanced")) {
            boolean advanced = actionId.equals("script.step.add_advanced");
            String type = values.getOrDefault(advanced ? "advanced_step_type" : "new_step_type",
                    advanced ? ADVANCED_TYPES.getFirst() : TYPES.getFirst());
            if (!(advanced ? ADVANCED_TYPES : TYPES).contains(type)) throw new IllegalArgumentException("Unknown step type");
            StepForm added = StepForm.empty(type, player);
            if (pocketId != null && type.equals("Spawn wave")
                    && !player.level().dimension().identifier().equals(
                            PocketEditorService.dimension(player.level().getServer(), pocketId))) {
                var entry = PocketEditorService.entry(player.level().getServer(), pocketId);
                added.fields.put("x", Integer.toString(entry.getX()));
                added.fields.put("y", Integer.toString(entry.getY()));
                added.fields.put("z", Integer.toString(entry.getZ()));
            }
            insertStep(draft.steps, draft.steps.size(), added);
            return AdminPanelRegistry.ActionResult.success("Added " + type);
        }
        if (actionId.startsWith("script.step.")) {
            String[] parts = actionId.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Missing step index");
            int index = Integer.parseInt(parts[1]);
            if (index < 0 || index >= draft.steps.size()) throw new IllegalArgumentException("Unknown step");
            switch (parts[0]) {
                case "script.step.remove" -> {
                    StepForm selected = draft.steps.get(index);
                    if (selected.type.equals("If") || selected.type.equals("While")) {
                        int end = matchingEnd(draft.steps, index);
                        draft.steps.subList(index, end + 1).clear();
                    } else if (selected.type.equals("Else")) {
                        int end = matchingElseEnd(draft.steps, index);
                        draft.steps.subList(index, end).clear();
                    } else draft.steps.remove(index);
                }
                case "script.step.up" -> {
                    if (index > 0) java.util.Collections.swap(draft.steps, index, index - 1);
                }
                case "script.step.down" -> {
                    if (index + 1 < draft.steps.size()) java.util.Collections.swap(draft.steps, index, index + 1);
                }
                case "script.step.insert" -> {
                    String type = values.getOrDefault("new_step_type", TYPES.getFirst());
                    if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown step type");
                    insertStep(draft.steps, index + 1, StepForm.empty(type, player));
                }
                case "script.step.else" -> {
                    if (!draft.steps.get(index).type.equals("If")) throw new IllegalArgumentException("Select an If block");
                    int end = matchingEnd(draft.steps, index);
                    if (hasElse(draft.steps, index, end))
                        throw new IllegalArgumentException("This If already has an Else branch");
                    draft.steps.add(end, StepForm.empty("Else", player));
                    draft.steps.add(end + 1, StepForm.empty("Sleep", player));
                }
                case "script.step.here" -> {
                    if (!List.of("Spawn wave", "Spawn mobs", "Portal location", "Spawn portal",
                            "Spawn building", "Despawn building", "Check redstone power", "Particles", "Explosion",
                            "Play sound")
                            .contains(draft.steps.get(index).type))
                        throw new IllegalArgumentException("This step has no placement position");
                    if (pocketId != null && !player.level().dimension().identifier().equals(
                            PocketEditorService.dimension(player.level().getServer(), pocketId))) {
                        throw new IllegalArgumentException("Enter this pocket before using your position");
                    }
                    StepForm step = draft.steps.get(index);
                    if (step.type.equals("Check redstone power")) {
                        step.fields.put("x", Integer.toString(player.blockPosition().getX()));
                        step.fields.put("y", Integer.toString(player.blockPosition().getY()));
                        step.fields.put("z", Integer.toString(player.blockPosition().getZ()));
                    } else {
                        step.fields.put("x", Double.toString(player.getX()));
                        step.fields.put("y", Double.toString(player.getY()));
                        step.fields.put("z", Double.toString(player.getZ()));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown step action");
            }
            return AdminPanelRegistry.ActionResult.success("Updated script timeline");
        }
        if (actionId.equals("script.duplicate")) {
            draft.id = draft.id.endsWith("_copy") ? draft.id + "_2" : draft.id + "_copy";
            draft.originId = "";
            return AdminPanelRegistry.ActionResult.success("Duplicate ready; save to create it");
        }
        if (actionId.equals("script.save")) {
            if (!draft.originId.isBlank() && !draft.id.equals(draft.originId)) {
                throw new IllegalArgumentException("Use Duplicate to save under another ID");
            }
            if (draft.originId.isBlank() && MobForge.SCRIPTS.get(draft.id).isPresent()) {
                throw new IllegalArgumentException("Script ID already exists: " + draft.id);
            }
            MobScript script = draft.build();
            if (pocketId != null) PocketEditorService.ensureEditor(player.level().getServer(), pocketId);
            try { MobForge.SCRIPTS.save(script); }
            catch (IOException exception) { throw new IllegalStateException("Could not save script: " + exception.getMessage(), exception); }
            draft.originId = script.id();
            if (pocketId != null) PocketEditorService.attachScript(player.level().getServer(), pocketId, script.id());
            drafts(player).remove(pageId);
            return AdminPanelRegistry.ActionResult.success("Saved MobForge script " + script.id(), backPage);
        }
        if (actionId.equals("script.delete")) {
            if (draft.originId.isBlank() || !draft.originId.equals(draft.id)) {
                throw new IllegalArgumentException("Only the original saved script can be deleted");
            }
            MobForge.SCRIPT_EXECUTOR.cancel(draft.id);
            try { MobForge.SCRIPTS.delete(draft.id); }
            catch (IOException exception) { throw new IllegalStateException("Could not delete script: " + exception.getMessage(), exception); }
            PocketEditorService.detachScriptEverywhere(player.level().getServer(), draft.id);
            drafts(player).remove(pageId);
            return AdminPanelRegistry.ActionResult.success("Deleted script " + draft.id, backPage);
        }
        throw new IllegalArgumentException("Unknown script action");
    }

    private static void insertStep(List<StepForm> steps, int index, StepForm form) {
        steps.add(index, form);
        if (form.type.equals("If") || form.type.equals("While")) {
            StepForm pause = StepForm.empty("Sleep", null);
            pause.fields.put("sleep", "1t");
            steps.add(index + 1, pause);
            steps.add(index + 2, StepForm.empty(form.type.equals("If") ? "End if" : "End loop", null));
        }
    }

    private static int matchingEnd(List<StepForm> steps, int start) {
        String opener = steps.get(start).type;
        String closer = opener.equals("If") ? "End if" : "End loop";
        int depth = 0;
        for (int i = start; i < steps.size(); i++) {
            if (steps.get(i).type.equals(opener)) depth++;
            else if (steps.get(i).type.equals(closer) && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unclosed " + opener + " block");
    }

    private static int matchingElseEnd(List<StepForm> steps, int start) {
        int depth = 0;
        for (int i = start + 1; i < steps.size(); i++) {
            String type = steps.get(i).type;
            if (type.equals("If")) depth++;
            else if (type.equals("End if")) {
                if (depth == 0) return i;
                depth--;
            }
        }
        throw new IllegalArgumentException("Unclosed Else branch");
    }

    private static boolean hasElse(List<StepForm> steps, int start, int end) {
        int depth = 0;
        for (int i = start + 1; i < end; i++) {
            String type = steps.get(i).type;
            if (type.equals("If")) depth++;
            else if (type.equals("End if")) depth--;
            else if (type.equals("Else") && depth == 0) return true;
        }
        return false;
    }

    private static Draft draft(ServerPlayer player, String pageId, Identifier pocketId, String scriptId) {
        return drafts(player)
                .computeIfAbsent(pageId, ignored -> {
                    MobScript script = scriptId.equals("@new") ? new MobScript("new_script", List.of())
                            : MobForge.SCRIPTS.get(scriptId).orElseThrow(() -> new IllegalArgumentException(
                                    "Unknown MobForge script: " + scriptId));
                    return new Draft(script.id(), scriptId.equals("@new") ? "" : script.id(),
                            new ArrayList<>(script.steps().stream().map(StepForm::from).toList()));
                });
    }

    private static Map<String, Draft> drafts(ServerPlayer player) {
        return DRAFTS.computeIfAbsent(player.level().getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
    }

    private static void requirePocket(ServerPlayer player, Identifier pocketId) {
        if (pocketId != null && !PocketEditorService.exists(player.level().getServer(), pocketId)) {
            throw new IllegalArgumentException("Unknown pocket: " + pocketId);
        }
    }

    private static AdminPanelRegistry.AdminEntry row(String section, String title, String detail,
                                                      List<AdminPanelRegistry.AdminField> fields,
                                                      List<AdminPanelRegistry.AdminAction> actions) {
        return AdminPanelRegistry.AdminEntry.editor(section, title, detail, fields, actions);
    }

    private static AdminPanelRegistry.AdminField field(String id, String label, String value, int limit) {
        return new AdminPanelRegistry.AdminField(id, label, "", value, limit, List.of());
    }

    private static AdminPanelRegistry.AdminField field(String prefix, StepForm step, String key, String label) {
        return field(prefix + key, label, step.value(key), 256);
    }

    private static AdminPanelRegistry.AdminField suggested(String prefix, StepForm step, String key,
                                                            String label, List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(prefix + key, label, "", step.value(key), 256, suggestions);
    }

    private static List<AdminPanelRegistry.AdminField> coordinates(String prefix, StepForm step,
                                                                    String x, String y, String z) {
        return List.of(field(prefix, step, x, x.toUpperCase()), field(prefix, step, y, y.toUpperCase()),
                field(prefix, step, z, z.toUpperCase()));
    }

    private static List<String> templateIds() {
        return MobForge.TEMPLATES.all().stream().map(MobTemplate::id).sorted().toList();
    }

    private static List<String> abilityIds() {
        return MobForge.abilities().all().keySet().stream().map(Object::toString).sorted().toList();
    }

    private static List<String> entityIds() {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        MobForge.mobs().all().keySet().forEach(id -> ids.add(id.toString()));
        MobForge.TEMPLATES.all().forEach(template -> ids.add(MobForge.id(template.id()).toString()));
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> ids.add(id.toString()));
        return List.copyOf(ids);
    }

    private static final class Draft {
        private String id;
        private String originId;
        private final List<StepForm> steps;

        private Draft(String id, String originId, List<StepForm> steps) {
            this.id = id;
            this.originId = originId;
            this.steps = steps;
        }

        private void apply(Map<String, String> values) {
            if (values.containsKey("script_id")) id = values.get("script_id").trim();
            for (int i = 0; i < steps.size(); i++) {
                StepForm step = steps.get(i);
                String prefix = "step_" + i + "_";
                for (String key : step.fields.keySet()) {
                    if (values.containsKey(prefix + key)) step.fields.put(key, values.get(prefix + key));
                }
            }
        }

        private MobScript build() {
            if (steps.isEmpty()) throw new IllegalArgumentException("Add at least one script step");
            MobScript script = new MobScript(id, steps.stream().map(StepForm::build).toList());
            return YAML.decode(YAML.encode(script));
        }
    }

    private static final class StepForm {
        private final String type;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private Map<String, Object> aiSettings = Map.of();

        private StepForm(String type) { this.type = type; }
        private String value(String key) { return fields.getOrDefault(key, ""); }

        private static StepForm empty(String type, ServerPlayer player) {
            StepForm form = new StepForm(type);
            switch (type) {
                case "Spawn mobs" -> {
                    form.fields.put("mob_id", "minecraft:zombie"); form.fields.put("count", "1");
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                    form.fields.put("ox", "0"); form.fields.put("oy", "0"); form.fields.put("oz", "0");
                }
                case "Spawn wave" -> {
                    form.fields.put("template", ""); form.fields.put("count", "1");
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                    form.fields.put("ox", "0"); form.fields.put("oy", "0"); form.fields.put("oz", "0");
                    form.fields.put("ai_mode", "template_default"); form.fields.put("combat", "true");
                    form.fields.put("tx", "0"); form.fields.put("ty", "64"); form.fields.put("tz", "0");
                    form.fields.put("arrival", "2"); form.fields.put("player", ""); form.fields.put("range", "64");
                }
                case "Sleep" -> form.fields.put("sleep", "5s");
                case "Spawn entity" -> {
                    form.fields.put("entity", "minecraft:zombie"); form.fields.put("anchor", "origin");
                    form.fields.put("save_as", "mob");
                }
                case "Wait until" -> {
                    form.fields.put("condition", "${mob.dead}"); form.fields.put("timeout", "5m");
                }
                case "Mob cast" -> {
                    form.fields.put("mob", "${mob}"); form.fields.put("ability", "");
                }
                case "Set phase" -> {
                    form.fields.put("mob", "${mob}"); form.fields.put("phase", "phase2");
                }
                case "Trigger event" -> form.fields.put("event", "cwp:example");
                case "Reward currency" -> {
                    form.fields.put("currency", ""); form.fields.put("amount", "1");
                }
                case "Reward item" -> {
                    form.fields.put("item", "minecraft:diamond"); form.fields.put("count", "1");
                }
                case "Reward loot table" -> {
                    form.fields.put("loot_table", "minecraft:chests/simple_dungeon");
                    form.fields.put("rolls", "1");
                }
                case "Loot pool" -> { form.fields.put("lootpool_id", "cwp:my_loot"); form.fields.put("player", ""); }
                case "Unlock achievement" -> { form.fields.put("achievement_id", "minecraft:story/root"); form.fields.put("player", ""); }
                case "Variable" -> { form.fields.put("name", "charge"); form.fields.put("operation", "set"); form.fields.put("value", "0"); }
                case "Watch event" -> { form.fields.put("condition", "${box.plate >= 1}"); form.fields.put("variable", "charge"); form.fields.put("operation", "increment"); form.fields.put("value", "1"); }
                case "Event box" -> {
                    form.fields.put("name", "plate");
                    form.fields.put("mode", "players"); form.fields.put("mob_id", "");
                    form.fields.put("player_variable", ""); form.fields.put("count_variable", "");
                    form.fields.put("x1", "0"); form.fields.put("y1", "64"); form.fields.put("z1", "0");
                    form.fields.put("x2", "1"); form.fields.put("y2", "66"); form.fields.put("z2", "1");
                }
                case "Redstone sensor" -> { form.fields.put("name", "switch"); form.fields.put("x", "0"); form.fields.put("y", "64"); form.fields.put("z", "0"); }
                case "If" -> form.fields.put("condition", "${var.charge >= 3}");
                case "Else", "End if", "End loop", "Raid complete" -> { }
                case "While" -> { form.fields.put("condition", "${var.charge < 3}"); form.fields.put("max", "1000"); }
                case "Portal location" -> {
                    form.fields.put("portal", "cwp:gate"); form.fields.put("action", "add"); form.fields.put("location", "main");
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                    form.fields.put("target_enabled", "No"); form.fields.put("tx", "0");
                    form.fields.put("ty", "64"); form.fields.put("tz", "0");
                }
                case "Check redstone power" -> {
                    form.fields.put("x", player == null ? "0" : Integer.toString(player.blockPosition().getX()));
                    form.fields.put("y", player == null ? "64" : Integer.toString(player.blockPosition().getY()));
                    form.fields.put("z", player == null ? "0" : Integer.toString(player.blockPosition().getZ()));
                    form.fields.put("powered", "powered"); form.fields.put("timeout", "5m");
                }
                case "Spawn portal" -> {
                    form.fields.put("portal", "cwp:gate");
                    form.fields.put("location", "script_" + java.util.UUID.randomUUID().toString().substring(0, 8));
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                }
                case "Track counter" -> {
                    form.fields.put("source", "mob kills"); form.fields.put("source_id", "minecraft:zombie");
                    form.fields.put("variable", "kills");
                }
                case "Encounter start", "Encounter end" -> { }
                case "Spawn building", "Despawn building" -> {
                    form.fields.put("build_id", "cwp:arena_wall");
                    form.fields.put("x", player == null ? "0" : Integer.toString(player.blockPosition().getX()));
                    form.fields.put("y", player == null ? "64" : Integer.toString(player.blockPosition().getY()));
                    form.fields.put("z", player == null ? "0" : Integer.toString(player.blockPosition().getZ()));
                }
                case "Particles" -> {
                    form.fields.put("particle_id", "minecraft:flame");
                    form.fields.put("count", "20"); form.fields.put("speed", "0.05");
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                    form.fields.put("ox", "0.5"); form.fields.put("oy", "0.5"); form.fields.put("oz", "0.5");
                }
                case "Explosion" -> {
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                    form.fields.put("power", "2");
                }
                case "Play sound" -> {
                    form.fields.put("sound_id", "minecraft:block.note_block.pling");
                    form.fields.put("x", player == null ? "0" : Double.toString(player.getX()));
                    form.fields.put("y", player == null ? "64" : Double.toString(player.getY()));
                    form.fields.put("z", player == null ? "0" : Double.toString(player.getZ()));
                }
                case "Apply effect", "Remove effect" -> {
                    form.fields.put("effect_id", "minecraft:speed");
                    form.fields.put("player_id", "");
                    form.fields.put("amplifier", "0");
                }
                default -> throw new IllegalArgumentException("Unknown step type: " + type);
            }
            return form;
        }

        private static StepForm from(MobScript.Step step) {
            StepForm form;
            if (step instanceof MobScript.SpawnMobsStep value) {
                form = empty("Spawn mobs", null);
                form.fields.put("mob_id", value.mobId().toString());
                form.fields.put("count", Integer.toString(value.count()));
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
                form.fields.put("ox", Double.toString(value.offset().x()));
                form.fields.put("oy", Double.toString(value.offset().y()));
                form.fields.put("oz", Double.toString(value.offset().z()));
            } else if (step instanceof MobScript.SpawnStep value) {
                form = empty("Spawn wave", null);
                form.fields.put("template", value.template()); form.fields.put("count", Integer.toString(value.count()));
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
                form.fields.put("ox", Double.toString(value.offset().x()));
                form.fields.put("oy", Double.toString(value.offset().y()));
                form.fields.put("oz", Double.toString(value.offset().z()));
                if (value.aiOverride() != null) {
                    MobTemplate.Ai ai = value.aiOverride();
                    form.fields.put("ai_mode", ai.mode()); form.fields.put("combat", Boolean.toString(ai.combat()));
                    if (ai.target() != null) {
                        form.fields.put("tx", Double.toString(ai.target().x()));
                        form.fields.put("ty", Double.toString(ai.target().y()));
                        form.fields.put("tz", Double.toString(ai.target().z()));
                    }
                    form.fields.put("arrival", Double.toString(ai.arrivalRadius()));
                    form.fields.put("player", ai.player() == null ? "" : ai.player());
                    form.fields.put("range", Double.toString(ai.range()));
                    form.aiSettings = ai.settings();
                }
            } else if (step instanceof MobScript.SleepStep value) {
                form = new StepForm("Sleep"); form.fields.put("sleep", MobScriptYaml.formatDuration(value.ticks()));
            } else if (step instanceof MobScript.SpawnEntityStep value) {
                form = new StepForm("Spawn entity"); form.fields.put("entity", value.id().toString());
                form.fields.put("anchor", value.at()); form.fields.put("save_as", value.saveAs());
            } else if (step instanceof MobScript.WaitUntilStep value) {
                var power = java.util.regex.Pattern.compile("powered\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)(?:\\s*==\\s*false)?")
                        .matcher(value.condition());
                if (power.matches()) {
                    form = empty("Check redstone power", null);
                    form.fields.put("x", power.group(1)); form.fields.put("y", power.group(2));
                    form.fields.put("z", power.group(3));
                    form.fields.put("powered", value.condition().endsWith("false") ? "unpowered" : "powered");
                    form.fields.put("timeout", MobScriptYaml.formatDuration(value.timeoutTicks()));
                } else {
                    form = new StepForm("Wait until"); form.fields.put("condition", value.condition());
                    form.fields.put("timeout", MobScriptYaml.formatDuration(value.timeoutTicks()));
                }
            } else if (step instanceof MobScript.MobCastStep value) {
                form = new StepForm("Mob cast"); form.fields.put("mob", value.mob());
                form.fields.put("ability", value.ability().toString());
            } else if (step instanceof MobScript.SetPhaseStep value) {
                form = new StepForm("Set phase"); form.fields.put("mob", value.mob());
                form.fields.put("phase", value.phase());
            } else if (step instanceof MobScript.TriggerStep value) {
                form = new StepForm("Trigger event"); form.fields.put("event", value.event().toString());
            } else if (step instanceof MobScript.RewardCurrencyStep value) {
                form = new StepForm("Reward currency"); form.fields.put("currency", value.currency().toString());
                form.fields.put("amount", Long.toString(value.amount()));
            } else if (step instanceof MobScript.RewardItemStep value) {
                form = new StepForm("Reward item"); form.fields.put("item", value.item().toString());
                form.fields.put("count", Integer.toString(value.count()));
            } else if (step instanceof MobScript.RewardLootStep value) {
                form = new StepForm("Reward loot table");
                form.fields.put("loot_table", value.lootTable().toString());
                form.fields.put("rolls", Integer.toString(value.rolls()));
            } else if (step instanceof MobScript.LootPoolStep value) {
                form = empty("Loot pool", null);
                form.fields.put("lootpool_id", value.lootPoolId().toString());
                form.fields.put("player", value.playerId());
            } else if (step instanceof MobScript.AchievementStep value) {
                form = empty("Unlock achievement", null);
                form.fields.put("achievement_id", value.achievementId().toString());
                form.fields.put("player", value.playerId());
            } else if (step instanceof MobScript.VariableStep value) {
                form = empty("Variable", null); form.fields.put("name", value.name());
                form.fields.put("operation", value.operation()); form.fields.put("value", value.value());
            } else if (step instanceof MobScript.EventStep value) {
                var counter = java.util.regex.Pattern.compile("(kills|box_triggers)\\(['\"]([^'\"]+)['\"]\\)")
                        .matcher(value.condition());
                if (counter.matches() && value.operation().equals("increment") && value.value().equals("1")) {
                    form = empty("Track counter", null);
                    form.fields.put("source", counter.group(1).equals("kills") ? "mob kills" : "event box triggers");
                    form.fields.put("source_id", counter.group(2)); form.fields.put("variable", value.variable());
                } else {
                    form = empty("Watch event", null); form.fields.put("condition", value.condition());
                    form.fields.put("variable", value.variable()); form.fields.put("operation", value.operation());
                    form.fields.put("value", value.value());
                }
            } else if (step instanceof MobScript.EventBoxStep value) {
                form = empty("Event box", null); form.fields.put("name", value.name());
                form.fields.put("mode", value.mode()); form.fields.put("mob_id", value.mobId());
                form.fields.put("player_variable", value.playerVariable());
                form.fields.put("count_variable", value.countVariable());
                form.fields.put("x1", Double.toString(value.min().x())); form.fields.put("y1", Double.toString(value.min().y()));
                form.fields.put("z1", Double.toString(value.min().z())); form.fields.put("x2", Double.toString(value.max().x()));
                form.fields.put("y2", Double.toString(value.max().y())); form.fields.put("z2", Double.toString(value.max().z()));
            } else if (step instanceof MobScript.RedstoneStep value) {
                form = empty("Redstone sensor", null); form.fields.put("name", value.name());
                form.fields.put("x", Double.toString(value.position().x())); form.fields.put("y", Double.toString(value.position().y()));
                form.fields.put("z", Double.toString(value.position().z()));
            } else if (step instanceof MobScript.IfStep value) {
                form = empty("If", null); form.fields.put("condition", value.condition());
            } else if (step instanceof MobScript.ElseStep) {
                form = empty("Else", null);
            } else if (step instanceof MobScript.EndIfStep) {
                form = empty("End if", null);
            } else if (step instanceof MobScript.LoopStep value) {
                form = empty("While", null); form.fields.put("condition", value.condition());
                form.fields.put("max", Integer.toString(value.maxIterations()));
            } else if (step instanceof MobScript.EndLoopStep) {
                form = empty("End loop", null);
            } else if (step instanceof MobScript.PortalLocationStep value) {
                form = empty(value.action().equals("add") && value.target() == null
                        ? "Spawn portal" : "Portal location", null);
                form.fields.put("portal", value.portalId().toString());
                form.fields.put("action", value.action()); form.fields.put("location", value.locationId());
                form.fields.put("x", Double.toString(value.position().x())); form.fields.put("y", Double.toString(value.position().y()));
                form.fields.put("z", Double.toString(value.position().z()));
                if (value.target() != null) {
                    form.fields.put("target_enabled", "Yes");
                    form.fields.put("tx", Double.toString(value.target().x()));
                    form.fields.put("ty", Double.toString(value.target().y()));
                    form.fields.put("tz", Double.toString(value.target().z()));
                }
            } else if (step instanceof MobScript.BuildingStep value) {
                form = empty(value.action().equals("spawn") ? "Spawn building" : "Despawn building", null);
                form.fields.put("build_id", value.buildId().toString());
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
            } else if (step instanceof MobScript.ParticleStep value) {
                form = empty("Particles", null); form.fields.put("particle_id", value.particleId().toString());
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
                form.fields.put("ox", Double.toString(value.offset().x()));
                form.fields.put("oy", Double.toString(value.offset().y()));
                form.fields.put("oz", Double.toString(value.offset().z()));
                form.fields.put("count", Integer.toString(value.count()));
                form.fields.put("speed", Double.toString(value.speed()));
            } else if (step instanceof MobScript.ExplosionStep value) {
                form = empty("Explosion", null);
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
                form.fields.put("power", Float.toString(value.power()));
            } else if (step instanceof MobScript.SoundStep value) {
                form = empty("Play sound", null); form.fields.put("sound_id", value.soundId().toString());
                form.fields.put("x", Double.toString(value.location().x()));
                form.fields.put("y", Double.toString(value.location().y()));
                form.fields.put("z", Double.toString(value.location().z()));
            } else if (step instanceof MobScript.EffectStep value) {
                form = empty(value.action().equals("remove") ? "Remove effect" : "Apply effect", null);
                form.fields.put("effect_id", value.effectId().toString());
                form.fields.put("player_id", value.playerId());
                form.fields.put("amplifier", Integer.toString(value.amplifier()));
            } else if (step instanceof MobScript.EncounterStartStep) {
                form = empty("Encounter start", null);
            } else if (step instanceof MobScript.EncounterEndStep || step instanceof MobScript.RaidCompleteStep) {
                form = empty("Encounter end", null);
            } else throw new IllegalArgumentException("Unsupported script step");
            return form;
        }

        private MobScript.Step build() {
            return switch (type) {
                case "Spawn mobs" -> new MobScript.SpawnMobsStep(Identifier.parse(value("mob_id")),
                        new MobScript.Position(number("x"), number("y"), number("z")),
                        new MobScript.Offset(number("ox"), number("oy"), number("oz")), integer("count"));
                case "Spawn wave" -> {
                    String mode = value("ai_mode");
                    MobTemplate.Ai ai = mode.equals("template_default") ? null : new MobTemplate.Ai(mode,
                            Boolean.parseBoolean(value("combat")),
                            mode.equals("target_position") ? new MobTemplate.Position(number("tx"), number("ty"), number("tz")) : null,
                            number("arrival"), mode.equals("target_player") ? value("player") : null,
                            number("range"), aiSettings);
                    yield new MobScript.SpawnStep(value("template"),
                            new MobScript.Position(number("x"), number("y"), number("z")),
                            new MobScript.Offset(number("ox"), number("oy"), number("oz")),
                            integer("count"), ai);
                }
                case "Sleep" -> new MobScript.SleepStep(MobScriptYaml.parseDuration(value("sleep")));
                case "Spawn entity" -> new MobScript.SpawnEntityStep(Identifier.parse(value("entity")),
                        value("anchor"), value("save_as"));
                case "Wait until" -> new MobScript.WaitUntilStep(value("condition"),
                        MobScriptYaml.parseDuration(value("timeout")));
                case "Mob cast" -> new MobScript.MobCastStep(value("mob"), Identifier.parse(value("ability")));
                case "Set phase" -> new MobScript.SetPhaseStep(value("mob"), value("phase"));
                case "Trigger event" -> new MobScript.TriggerStep(Identifier.parse(value("event")));
                case "Reward currency" -> new MobScript.RewardCurrencyStep(Identifier.parse(value("currency")),
                        positiveLong("amount"));
                case "Reward item" -> new MobScript.RewardItemStep(Identifier.parse(value("item")), integer("count"));
                case "Reward loot table" -> new MobScript.RewardLootStep(
                        Identifier.parse(value("loot_table")), integer("rolls"));
                case "Loot pool" -> new MobScript.LootPoolStep(Identifier.parse(value("lootpool_id")), value("player"));
                case "Unlock achievement" -> new MobScript.AchievementStep(Identifier.parse(value("achievement_id")), value("player"));
                case "Variable" -> new MobScript.VariableStep(value("name"), value("operation"), value("value"));
                case "Watch event" -> new MobScript.EventStep(value("condition"), value("variable"),
                        value("operation"), value("value"));
                case "Event box" -> new MobScript.EventBoxStep(value("name"),
                        new MobScript.Position(number("x1"), number("y1"), number("z1")),
                        new MobScript.Position(number("x2"), number("y2"), number("z2")),
                        value("mode"), value("mob_id"), value("player_variable"), value("count_variable"));
                case "Redstone sensor" -> new MobScript.RedstoneStep(value("name"),
                        new MobScript.Position(number("x"), number("y"), number("z")));
                case "If" -> new MobScript.IfStep(value("condition"));
                case "Else" -> new MobScript.ElseStep();
                case "End if" -> new MobScript.EndIfStep();
                case "While" -> new MobScript.LoopStep(value("condition"), integer("max"));
                case "End loop" -> new MobScript.EndLoopStep();
                case "Portal location" -> new MobScript.PortalLocationStep(Identifier.parse(value("portal")),
                        value("action"), value("location"), new MobScript.Position(number("x"), number("y"), number("z")),
                        value("target_enabled").equals("Yes")
                                ? new MobScript.Position(number("tx"), number("ty"), number("tz")) : null);
                case "Check redstone power" -> new MobScript.WaitUntilStep(
                        "powered(" + integer("x") + ", " + integer("y") + ", " + integer("z") + ")"
                                + (value("powered").equals("unpowered") ? " == false" : ""),
                        MobScriptYaml.parseDuration(value("timeout")));
                case "Spawn portal" -> new MobScript.PortalLocationStep(Identifier.parse(value("portal")),
                        "add", value("location"), new MobScript.Position(number("x"), number("y"), number("z")));
                case "Track counter" -> new MobScript.EventStep(
                        (value("source").equals("mob kills") ? "kills" : "box_triggers")
                                + "('" + value("source_id") + "')", value("variable"), "increment", "1");
                case "Encounter start" -> new MobScript.EncounterStartStep();
                case "Encounter end" -> new MobScript.EncounterEndStep();
                case "Spawn building", "Despawn building" -> new MobScript.BuildingStep(
                        Identifier.parse(value("build_id")),
                        type.equals("Spawn building") ? "spawn" : "despawn",
                        new MobScript.Position(number("x"), number("y"), number("z")));
                case "Particles" -> new MobScript.ParticleStep(Identifier.parse(value("particle_id")),
                        new MobScript.Position(number("x"), number("y"), number("z")),
                        new MobScript.Offset(number("ox"), number("oy"), number("oz")),
                        integer("count"), number("speed"));
                case "Explosion" -> new MobScript.ExplosionStep(
                        new MobScript.Position(number("x"), number("y"), number("z")),
                        (float) number("power"));
                case "Play sound" -> new MobScript.SoundStep(Identifier.parse(value("sound_id")),
                        new MobScript.Position(number("x"), number("y"), number("z")));
                case "Apply effect", "Remove effect" -> new MobScript.EffectStep(Identifier.parse(value("effect_id")),
                        value("player_id"), type.equals("Remove effect") ? "remove" : "apply", integer("amplifier"));
                case "Raid complete" -> new MobScript.RaidCompleteStep();
                default -> throw new IllegalArgumentException("Unknown step type: " + type);
            };
        }

        private double number(String key) {
            try { return Double.parseDouble(value(key)); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be a number"); }
        }
        private int integer(String key) {
            try { return Integer.parseInt(value(key)); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be an integer"); }
        }
        private long positiveLong(String key) {
            try {
                long amount = Long.parseLong(value(key));
                if (amount > 0) return amount;
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException(key + " must be a positive whole number");
        }
    }
}
