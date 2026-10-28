package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptCode;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.portals.PortalService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MobForge's script editor in the CWP admin panel: the script's .mfs source in a text editor. */
final class AdminScriptEditor {
    private static final MobScriptCode CODE = new MobScriptCode();
    private static final int MAX_SOURCE_LENGTH = 65_536;
    private static final String NEW_SCRIPT = "@new";

    private AdminScriptEditor() { }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, String backPage,
                                              Identifier pocketId, String scriptId,
                                              List<AdminPanelRegistry.AdminTab> tabs) {
        requirePocket(player, pocketId);
        String source = source(scriptId);
        String subtitle = (pocketId == null ? "MobForge" : "Pocket " + pocketId) + " · "
                + (scriptId.equals(NEW_SCRIPT) ? "new script" : scriptId + ".mfs");
        if (source.length() > MAX_SOURCE_LENGTH)
            return new AdminPanelRegistry.AdminPage(pageId, "MobForge script",
                    "Source exceeds the 65,536-character in-game limit", tabs,
                    List.of(row("Script", "Source too large", "Edit this .mfs file on the server.", List.of(),
                            List.of(AdminPanelRegistry.AdminAction.navigate("Back", backPage)))));
        List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
        if (!scriptId.equals(NEW_SCRIPT))
            actions.add(new AdminPanelRegistry.AdminAction("script.delete", "Delete script", true));
        actions.add(AdminPanelRegistry.AdminAction.wide("script.save", "Save script", false));
        actions.add(AdminPanelRegistry.AdminAction.navigate("Back", backPage));
        List<AdminPanelRegistry.AdminEntry> rows = List.of(
                row("Script", "Source code", "Tab completes commands and their inputs; an unmatched Tab inserts four spaces.",
                        List.of(new AdminPanelRegistry.AdminField("script_source", "Source", "", source,
                                MAX_SOURCE_LENGTH, valueLists(player.level().getServer()))), List.of()),
                row("Actions", "Save", "The server validates every step before saving. Change the ID in the first "
                        + "line to save a copy.", List.of(), actions));
        return new AdminPanelRegistry.AdminPage(pageId, scriptId.equals(NEW_SCRIPT) ? "New MobForge script"
                : "Edit MobForge script", subtitle, tabs, rows, AdminPanelRegistry.PageLayout.CODE);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String actionId,
                                                   Map<String, String> values, String backPage,
                                                   Identifier pocketId, String scriptId) {
        requirePocket(player, pocketId);
        if (actionId.equals("script.save")) {
            String source = values.get("script_source");
            if (source == null || source.length() > MAX_SOURCE_LENGTH)
                throw new IllegalArgumentException("Script source is missing or too large");
            MobScript script = CODE.decode(source);
            boolean copy = !scriptId.equals(NEW_SCRIPT) && !script.id().equals(scriptId);
            if ((copy || scriptId.equals(NEW_SCRIPT)) && MobForge.SCRIPTS.get(script.id()).isPresent())
                throw new IllegalArgumentException("Script ID already exists: " + script.id());
            if (pocketId != null) PocketEditorService.ensureEditor(player.level().getServer(), pocketId);
            try { MobForge.SCRIPTS.saveSource(source); }
            catch (IOException exception) { throw new IllegalStateException("Could not save script: " + exception.getMessage(), exception); }
            if (pocketId != null) PocketEditorService.attachScript(player.level().getServer(), pocketId, script.id());
            return AdminPanelRegistry.ActionResult.success((copy ? "Saved a copy as MobForge script "
                    : "Saved MobForge script ") + script.id(), backPage);
        }
        if (actionId.equals("script.delete")) {
            if (scriptId.equals(NEW_SCRIPT) || MobForge.SCRIPTS.get(scriptId).isEmpty())
                throw new IllegalArgumentException("Only a saved script can be deleted");
            MobForge.SCRIPT_EXECUTOR.cancel(scriptId);
            try { MobForge.SCRIPTS.delete(scriptId); }
            catch (IOException exception) { throw new IllegalStateException("Could not delete script: " + exception.getMessage(), exception); }
            PocketEditorService.detachScriptEverywhere(player.level().getServer(), scriptId);
            return AdminPanelRegistry.ActionResult.success("Deleted script " + scriptId, backPage);
        }
        throw new IllegalArgumentException("Unknown script action");
    }

    /**
     * IDs only the server knows, suggested while typing script inputs, as "list<TAB>id". Sounds, effects,
     * particles, items and entity types come from the client's own registries instead.
     */
    private static List<String> valueLists(MinecraftServer server) {
        List<String> values = new ArrayList<>();
        MobForge.TEMPLATES.all().forEach(template -> values.add("template	" + template.id()));
        java.util.Set<String> mobs = new java.util.TreeSet<>();
        MobForge.mobs().all().keySet().forEach(id -> mobs.add(id.toString()));
        MobForge.TEMPLATES.all().forEach(template -> mobs.add(MobForge.id(template.id()).toString()));
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> mobs.add(id.toString()));
        mobs.forEach(id -> values.add("mob	" + id));
        MobForge.abilities().all().keySet().forEach(id -> values.add("ability	" + id));
        LootPoolService.all(server).keySet().forEach(id -> values.add("loot_pool	" + id));
        BuildingService.buildings(server).forEach(building -> values.add("building	" + building.id()));
        PortalService.portals(server).forEach(portal -> values.add("portal	" + portal.id()));
        CurrencyRegistry.values().stream().filter(currency -> currency.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                .forEach(currency -> values.add("currency	" + currency.id()));
        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements())
            if (!advancement.id().getPath().startsWith("recipes/")) values.add("advancement	" + advancement.id());
        server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds()
                .forEach(key -> values.add("loot_table	" + key.identifier()));
        return values;
    }

    /** The saved .mfs text, or a starting file for a new script. */
    private static String source(String scriptId) {
        if (scriptId.equals(NEW_SCRIPT)) return CODE.encode(new MobScript("new_script", List.of()));
        if (MobForge.SCRIPTS.get(scriptId).isEmpty()) throw new IllegalArgumentException("Unknown MobForge script: " + scriptId);
        try { return MobForge.SCRIPTS.source(scriptId); }
        catch (IOException exception) { throw new IllegalStateException("Could not read script source: " + exception.getMessage(), exception); }
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
}
