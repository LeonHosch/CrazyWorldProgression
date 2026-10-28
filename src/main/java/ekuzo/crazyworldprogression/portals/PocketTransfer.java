package ekuzo.crazyworldprogression.portals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.loot.LootPoolData;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptCode;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moves a pocket between worlds, for example from singleplayer to a server, as one .cwppocket file (a zip).
 * It holds the pocket's saved blocks, size and spawn, where it was built, its attached scripts, the portals
 * inside it or used by its scripts, and the buildings, mob templates and loot pools those scripts use.
 * Importing writes nothing over existing content: a pocket ID that exists is refused, and any other ID that
 * exists is reused only when it is identical; otherwise the import is refused before anything is written.
 */
public final class PocketTransfer {
    public static final String EXTENSION = ".cwppocket";
    private static final int FORMAT = 1;
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final MobScriptCode CODE = new MobScriptCode();

    private PocketTransfer() { }

    /** The folder export writes to and import reads from: cwp_pockets next to the world folders. */
    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("cwp_pockets");
    }

    /** Export files in the folder, newest first, for command suggestions and the admin panel. */
    public static List<String> files() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) return List.of();
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .map(path -> path.getFileName().toString()).toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public record Result(Path file, List<String> notes) { }

    // ---------------------------------------------------------------- export

    public static Result export(MinecraftServer server, Identifier id) throws IOException {
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
        if (template == null) throw new IllegalArgumentException("Save pocket " + id + " before exporting it");
        if (PocketCaptures.capturing(server, id))
            throw new IllegalStateException("Pocket " + id + " is being saved; try again in a moment");
        if (PocketEditorService.autoSaveStatus(server, id).startsWith("Build changes are waiting"))
            throw new IllegalStateException("Pocket " + id + " has unsaved build changes; leave its workshop so they save, then export");
        PocketEditorData editorData = PocketEditorData.get(server);
        BlockPos origin = editorData.captureOrigin(id);
        BlockPos entry = editorData.entry(id);
        if (origin == null) origin = entry == null ? new BlockPos(0, 64, 0)
                : entry.offset(-template.spawnX(), -template.spawnY(), -template.spawnZ());
        if (entry == null) entry = origin.offset(template.spawnX(), template.spawnY(), template.spawnZ());
        Identifier editor = PocketEditorService.dimension(server, id);
        List<String> notes = new ArrayList<>();

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT);
        manifest.addProperty("id", id.toString());
        manifest.addProperty("size_x", template.sizeX());
        manifest.addProperty("size_y", template.sizeY());
        manifest.addProperty("size_z", template.sizeZ());
        manifest.addProperty("spawn_x", template.spawnX());
        manifest.addProperty("spawn_y", template.spawnY());
        manifest.addProperty("spawn_z", template.spawnZ());
        manifest.addProperty("author", template.author());
        manifest.add("capture_origin", position(origin));
        manifest.add("entry", position(entry));

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pocket.nbt", Files.readAllBytes(structurePath(server, id)));

        // Scripts, and what they use.
        List<String> scripts = PocketEditorService.scripts(server, id);
        Set<Identifier> portals = new LinkedHashSet<>(), buildings = new LinkedHashSet<>(), pools = new LinkedHashSet<>();
        Set<String> templates = new LinkedHashSet<>();
        JsonArray scriptIds = new JsonArray();
        for (String scriptId : scripts) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            if (script == null) { notes.add("Skipped missing script " + scriptId); continue; }
            files.put("scripts/" + scriptId + ".mfs", MobForge.SCRIPTS.source(scriptId).getBytes(StandardCharsets.UTF_8));
            scriptIds.add(scriptId);
            for (MobScript.Step step : script.steps()) {
                if (step instanceof MobScript.PortalLocationStep portal) portals.add(portal.portalId());
                else if (step instanceof MobScript.BuildingStep building) buildings.add(building.buildId());
                else if (step instanceof MobScript.LootPoolStep loot) pools.add(loot.lootPoolId());
                else if (step instanceof MobScript.SpawnStep spawn) templates.add(spawn.template());
                else if (step instanceof MobScript.SpawnMobsStep spawn && spawn.mobId().getNamespace().equals(MobForge.MOD_ID)
                        && MobForge.TEMPLATES.get(spawn.mobId().getPath()).isPresent()) templates.add(spawn.mobId().getPath());
            }
        }
        manifest.add("scripts", scriptIds);

        // Mob templates (YAML as written) and the loot pools they roll.
        JsonArray templateIds = new JsonArray();
        for (String templateId : templates) {
            MobTemplate mob = MobForge.TEMPLATES.get(templateId).orElse(null);
            Path file = templateFile(templateId);
            if (mob == null || file == null) { notes.add("Skipped missing mob template " + templateId); continue; }
            files.put("templates/" + file.getFileName(), Files.readAllBytes(file));
            templateIds.add(templateId);
            if (mob.loot().lootPool() != null) {
                Identifier pool = Identifier.tryParse(mob.loot().lootPool());
                if (pool != null) pools.add(pool);
            }
        }
        manifest.add("templates", templateIds);

        // Loot pools, with every pool they nest.
        JsonArray poolArray = new JsonArray();
        Set<Identifier> visited = new LinkedHashSet<>();
        List<Identifier> pending = new ArrayList<>(pools);
        while (!pending.isEmpty()) {
            Identifier poolId = pending.removeFirst();
            if (!visited.add(poolId)) continue;
            LootPoolData.Pool pool = LootPoolService.get(server, poolId);
            if (pool == null) { notes.add("Skipped missing loot pool " + poolId); continue; }
            poolArray.add(LootPoolData.Pool.CODEC.encodeStart(JsonOps.INSTANCE, pool).getOrThrow());
            for (LootPoolData.Entry entryValue : pool.entries())
                if (LootPoolService.get(server, entryValue.target()) != null) pending.add(entryValue.target());
        }
        manifest.add("loot_pools", poolArray);

        // Buildings.
        JsonArray buildingArray = new JsonArray();
        for (Identifier buildingId : buildings) {
            BuildingService.BuildingInfo building = BuildingService.building(server, buildingId);
            Path file = buildingStructurePath(server, buildingId);
            if (building == null || !Files.isRegularFile(file)) { notes.add("Skipped missing building " + buildingId); continue; }
            JsonObject value = new JsonObject();
            value.addProperty("id", buildingId.toString());
            value.addProperty("size_x", building.sizeX());
            value.addProperty("size_y", building.sizeY());
            value.addProperty("size_z", building.sizeZ());
            value.addProperty("author", building.author());
            buildingArray.add(value);
            files.put("buildings/" + fileName(buildingId) + ".nbt", Files.readAllBytes(file));
        }
        manifest.add("buildings", buildingArray);

        // Portals placed inside the pocket's workshop, and portal presets its scripts place.
        if (editor != null) for (PortalDefinition portal : PortalService.portals(server))
            if (PortalService.locations(server, portal.id()).values().stream()
                    .anyMatch(location -> location.dimension().equals(editor))) portals.add(portal.id());
        JsonArray portalArray = new JsonArray();
        for (Identifier portalId : portals) {
            PortalDefinition portal = PortalService.portal(server, portalId);
            if (portal == null) { notes.add("Skipped missing portal " + portalId); continue; }
            JsonObject value = new JsonObject();
            value.add("definition", PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, portal).getOrThrow());
            JsonObject locations = new JsonObject();
            PortalService.locations(server, portalId).forEach((name, location) -> {
                if (editor != null && location.dimension().equals(editor)) {
                    JsonObject point = new JsonObject();
                    point.addProperty("x", location.x());
                    point.addProperty("y", location.y());
                    point.addProperty("z", location.z());
                    locations.add(name, point);
                }
            });
            value.add("locations", locations);
            portalArray.add(value);
            if (!portal.isExit() && !portal.isCoordinates() && !portal.templateId().equals(id))
                notes.add("Portal " + portalId + " leads to pocket " + portal.templateId() + ", which is not in this file");
        }
        manifest.add("portals", portalArray);

        Files.createDirectories(folder());
        Path destination = folder().resolve(fileName(id) + EXTENSION);
        Path temporary = Files.createTempFile(folder(), fileName(id) + "-", ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            write(zip, "pocket.json", GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            for (var file : files.entrySet()) write(zip, file.getKey(), file.getValue());
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        notes.addFirst(scriptIds.size() + " scripts, " + portalArray.size() + " portals, " + buildingArray.size()
                + " buildings, " + templateIds.size() + " mob templates, " + poolArray.size() + " loot pools");
        return new Result(destination, notes);
    }

    // ---------------------------------------------------------------- import

    public static Result importFile(MinecraftServer server, String fileName) throws IOException {
        if (!fileName.endsWith(EXTENSION) || fileName.contains("/") || fileName.contains("\\") || fileName.contains(".."))
            throw new IllegalArgumentException("Choose a " + EXTENSION + " file from the cwp_pockets folder");
        Path file = folder().resolve(fileName);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No file " + fileName + " in " + folder());
        Map<String, byte[]> files = read(file);
        byte[] manifestBytes = files.get("pocket.json");
        if (manifestBytes == null) throw new IllegalArgumentException(fileName + " is not a CWP pocket export");
        JsonObject manifest = GSON.fromJson(new String(manifestBytes, StandardCharsets.UTF_8), JsonObject.class);
        if (manifest.get("format").getAsInt() != FORMAT)
            throw new IllegalArgumentException(fileName + " was written by a different CWP version");
        Identifier id = Identifier.parse(manifest.get("id").getAsString());
        byte[] pocketBlocks = files.get("pocket.nbt");
        if (pocketBlocks == null) throw new IllegalArgumentException(fileName + " has no pocket blocks");

        // Check everything first; nothing is written unless the whole import can go ahead.
        List<String> conflicts = new ArrayList<>();
        if (PocketEditorService.exists(server, id)) conflicts.add("pocket " + id);
        PortalTemplateData.PortalTemplate template = new PortalTemplateData.PortalTemplate(id,
                manifest.get("size_x").getAsInt(), manifest.get("size_y").getAsInt(), manifest.get("size_z").getAsInt(),
                manifest.get("spawn_x").getAsInt(), manifest.get("spawn_y").getAsInt(), manifest.get("spawn_z").getAsInt(),
                manifest.get("author").getAsString());
        BlockPos origin = blockPos(manifest.getAsJsonObject("capture_origin"));
        BlockPos entry = blockPos(manifest.getAsJsonObject("entry"));

        Map<String, byte[]> newTemplates = new LinkedHashMap<>();
        for (var file2 : files.entrySet()) {
            if (!file2.getKey().startsWith("templates/")) continue;
            String name = file2.getKey().substring("templates/".length());
            String templateId = name.replaceFirst("\\.ya?ml$", "");
            requireId(templateId, "mob template");
            Path existing = templateFile(templateId);
            if (existing == null) newTemplates.put(name, file2.getValue());
            else if (!Arrays.equals(Files.readAllBytes(existing), file2.getValue())) conflicts.add("mob template " + templateId);
        }

        List<LootPoolData.Pool> newPools = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("loot_pools")) {
            LootPoolData.Pool pool = LootPoolData.Pool.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
            LootPoolData.Pool existing = LootPoolService.get(server, pool.id());
            if (existing == null) newPools.add(pool);
            else if (!existing.equals(pool)) conflicts.add("loot pool " + pool.id());
        }

        record NewBuilding(Identifier id, int sizeX, int sizeY, int sizeZ, String author, byte[] blocks) { }
        List<NewBuilding> newBuildings = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("buildings")) {
            JsonObject value = element.getAsJsonObject();
            Identifier buildingId = Identifier.parse(value.get("id").getAsString());
            byte[] blocks = files.get("buildings/" + fileName(buildingId) + ".nbt");
            if (blocks == null) throw new IllegalArgumentException(fileName + " is missing the blocks of building " + buildingId);
            if (BuildingService.building(server, buildingId) == null)
                newBuildings.add(new NewBuilding(buildingId, value.get("size_x").getAsInt(), value.get("size_y").getAsInt(),
                        value.get("size_z").getAsInt(), value.get("author").getAsString(), blocks));
            else {
                Path existing = buildingStructurePath(server, buildingId);
                if (!Files.isRegularFile(existing) || !Arrays.equals(Files.readAllBytes(existing), blocks))
                    conflicts.add("building " + buildingId);
            }
        }

        record NewPortal(PortalDefinition definition, Map<String, Vec3> locations, boolean exists) { }
        List<NewPortal> newPortals = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("portals")) {
            JsonObject value = element.getAsJsonObject();
            PortalDefinition definition = PortalDefinition.CODEC.parse(JsonOps.INSTANCE, value.get("definition")).getOrThrow();
            Map<String, Vec3> locations = new LinkedHashMap<>();
            for (var location : value.getAsJsonObject("locations").entrySet()) {
                JsonObject point = location.getValue().getAsJsonObject();
                locations.put(location.getKey(), new Vec3(point.get("x").getAsDouble(), point.get("y").getAsDouble(),
                        point.get("z").getAsDouble()));
            }
            PortalDefinition existing = PortalService.portal(server, definition.id());
            if (existing == null) newPortals.add(new NewPortal(definition, locations, false));
            // The same preset (same settings, placed elsewhere) is shared: it only gets this pocket's placements.
            else if (samePortal(existing, definition)) newPortals.add(new NewPortal(existing, locations, true));
            else conflicts.add("portal " + definition.id());
        }

        Map<String, String> newScripts = new LinkedHashMap<>();
        List<String> attach = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("scripts")) {
            String scriptId = element.getAsString();
            requireId(scriptId, "script");
            byte[] source = files.get("scripts/" + scriptId + ".mfs");
            if (source == null) throw new IllegalArgumentException(fileName + " is missing script " + scriptId);
            String text = new String(source, StandardCharsets.UTF_8);
            if (!CODE.decode(text).id().equals(scriptId))
                throw new IllegalArgumentException("Script " + scriptId + " in " + fileName + " has a different ID inside");
            attach.add(scriptId);
            if (MobForge.SCRIPTS.get(scriptId).isEmpty()) newScripts.put(scriptId, text);
            else if (!MobForge.SCRIPTS.source(scriptId).equals(text)) conflicts.add("script " + scriptId);
        }

        if (!conflicts.isEmpty()) throw new IllegalArgumentException("Nothing was imported. These already exist on this "
                + "server with different content: " + String.join(", ", conflicts));

        // Write: content the scripts depend on first, then the pocket, its portals and finally its scripts.
        List<String> notes = new ArrayList<>();
        Path templateFolder = MobForge.TEMPLATES.directory();
        Files.createDirectories(templateFolder);
        for (var template2 : newTemplates.entrySet())
            Files.write(templateFolder.resolve(template2.getKey()), template2.getValue());
        if (!newTemplates.isEmpty()) MobForge.TEMPLATES.reload();
        for (String name : newTemplates.keySet())
            if (MobForge.TEMPLATES.get(name.replaceFirst("\\.ya?ml$", "")).isEmpty())
                notes.add("Mob template " + name + " did not load; see the server log");
        // Nested pools are saved before the pools that contain them.
        for (LootPoolData.Pool pool : newPools.reversed()) LootPoolService.save(server, pool);
        for (NewBuilding building : newBuildings) {
            Path destination = buildingStructurePath(server, building.id());
            Files.createDirectories(destination.getParent());
            Files.write(destination, building.blocks());
            server.getStructureManager().remove(buildingStructureId(building.id()));
            BuildingService.register(server, building.id(), building.sizeX(), building.sizeY(), building.sizeZ(),
                    building.author());
        }

        Path pocketFile = structurePath(server, id);
        Files.createDirectories(pocketFile.getParent());
        Files.write(pocketFile, pocketBlocks);
        server.getStructureManager().remove(id);
        PortalTemplateData.get(server).put(template);
        // The workshop is rebuilt where the pocket was built, so script and portal coordinates stay correct.
        Identifier editor = PocketEditorService.importEditor(server, id, origin, entry);

        for (NewPortal portal : newPortals) {
            PortalDefinition definition = portal.definition();
            if (!portal.exists()) {
                Vec3 first = portal.locations().values().stream().findFirst().orElse(null);
                PortalDefinition placed = first == null ? definition : definition.withPlacement(editor, first);
                try { PortalService.createUnplaced(server, placed); }
                catch (RuntimeException exception) {
                    notes.add("Portal " + definition.id() + " was not imported: " + exception.getMessage());
                    continue;
                }
            }
            for (var location : portal.locations().entrySet()) {
                String name = location.getKey();
                if (PortalService.locations(server, definition.id()).containsKey(name)) name = name + "_" + fileName(id);
                PortalService.setLocation(server, definition.id(), name, editor, location.getValue());
            }
        }

        for (var script : newScripts.entrySet()) {
            try { MobForge.SCRIPTS.saveSource(script.getValue()); }
            catch (IOException | RuntimeException exception) {
                notes.add("Script " + script.getKey() + " was not imported: " + exception.getMessage());
            }
        }
        for (String scriptId : attach)
            if (MobForge.SCRIPTS.get(scriptId).isPresent()) PocketEditorService.attachScript(server, id, scriptId);

        notes.addFirst("Imported pocket " + id + " with " + attach.size() + " scripts, " + newPortals.size()
                + " portals, " + newBuildings.size() + " new buildings, " + newTemplates.size() + " new mob templates and "
                + newPools.size() + " new loot pools");
        return new Result(file, notes);
    }

    /** Same portal settings, ignoring where its original copy stands. */
    private static boolean samePortal(PortalDefinition a, PortalDefinition b) {
        Vec3 zero = Vec3.ZERO;
        Identifier dimension = CrazyWorldProgression.id("compare");
        return a.withPlacement(dimension, zero).equals(b.withPlacement(dimension, zero));
    }

    // ---------------------------------------------------------------- files

    private static Path structurePath(MinecraftServer server, Identifier id) throws IOException {
        Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).toAbsolutePath().normalize();
        Path path = generated.resolve(id.getNamespace()).resolve("structure").resolve(id.getPath() + ".nbt").normalize();
        if (!path.startsWith(generated)) throw new IOException("Unsafe structure path for " + id);
        return path;
    }

    private static Identifier buildingStructureId(Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(), "cwp_buildings/" + id.getPath());
    }

    private static Path buildingStructurePath(MinecraftServer server, Identifier id) throws IOException {
        return structurePath(server, buildingStructureId(id));
    }

    /** The YAML file a mob template was loaded from, or null. */
    private static Path templateFile(String templateId) {
        for (String extension : List.of(".yml", ".yaml")) {
            Path file = MobForge.TEMPLATES.directory().resolve(templateId + extension).normalize();
            if (file.getParent().equals(MobForge.TEMPLATES.directory().normalize()) && Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private static void requireId(String value, String kind) {
        if (!value.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid " + kind + " ID: " + value);
    }

    private static String fileName(Identifier id) {
        return (id.getNamespace() + "_" + id.getPath()).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject value = new JsonObject();
        value.addProperty("x", pos.getX());
        value.addProperty("y", pos.getY());
        value.addProperty("z", pos.getZ());
        return value;
    }

    private static BlockPos blockPos(JsonObject value) {
        return new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt());
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Every entry of the zip, refusing paths that leave it and entries larger than any real pocket. */
    private static Map<String, byte[]> read(Path file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\"))
                    throw new IOException("Unsafe entry in pocket file: " + name);
                entries.put(name, readLimited(zip));
            }
        }
        return entries;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) > 0) {
            total += read;
            if (total > MAX_ENTRY_BYTES) throw new IOException("Pocket file entry is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
