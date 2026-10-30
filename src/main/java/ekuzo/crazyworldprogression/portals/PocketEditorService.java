package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/** Allocates one persistent, isolated build dimension for every pocket. */
public final class PocketEditorService {
    /** Workshop worlds, as many as config/crazy-world-progression.yml asks for (GeneratedWorlds makes them). */
    public static final int MAX_POCKETS = ekuzo.crazyworldprogression.config.CwpConfig.get().workshops();
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final long SAVE_DELAY_TICKS = 200L;
    private static final long MAX_SAVE_DELAY_TICKS = 1_200L;
    private static final long RETRY_DELAY_TICKS = 600L;
    private static final Map<MinecraftServer, Map<Identifier, AutoSave>> AUTO_SAVES = new WeakHashMap<>();

    private PocketEditorService() {
    }

    public static List<Identifier> pockets(MinecraftServer server) {
        return Stream.concat(PocketEditorData.get(server).pockets().keySet().stream(),
                        PortalTemplateService.templates(server).stream().map(info -> info.id().toString()))
                .distinct().map(Identifier::parse).sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    public static boolean exists(MinecraftServer server, Identifier id) {
        return PocketEditorData.get(server).pockets().containsKey(id.toString())
                || PortalTemplateService.template(server, id) != null;
    }

    public static Identifier dimension(MinecraftServer server, Identifier id) {
        Integer slot = PocketEditorData.get(server).pockets().get(id.toString());
        return slot == null ? null : slotDimension(slot);
    }

    public static boolean isEditorDimension(Identifier dimension) {
        for (int slot = 1; slot <= MAX_POCKETS; slot++) {
            if (slotDimension(slot).equals(dimension)) return true;
        }
        return false;
    }

    public static boolean isAssignedDimension(MinecraftServer server, Identifier dimension) {
        return PocketEditorData.get(server).pockets().values().stream()
                .anyMatch(slot -> slotDimension(slot).equals(dimension));
    }

    public static Identifier pocketForDimension(MinecraftServer server, Identifier dimension) {
        for (int slot = 1; slot <= MAX_POCKETS; slot++) {
            if (slotDimension(slot).equals(dimension)) return PocketEditorData.get(server).pocketForSlot(slot);
        }
        return null;
    }

    public static BlockPos captureOrigin(MinecraftServer server, Identifier id) {
        return PocketEditorData.get(server).captureOrigin(id);
    }

    public static Identifier create(MinecraftServer server, Identifier id) {
        if (id.equals(PortalDefinition.EXIT_TEMPLATE)) throw new IllegalArgumentException("Exit is a reserved portal link");
        if (exists(server, id)) throw new IllegalArgumentException("Pocket already exists: " + id);
        return allocate(server, id, false);
    }

    /** Give a preexisting structure its own authoring dimension when opened for editing. */
    public static Identifier ensureEditor(MinecraftServer server, Identifier id) {
        Identifier assigned = dimension(server, id);
        if (assigned != null) return assigned;
        if (PortalTemplateService.template(server, id) == null) {
            throw new IllegalArgumentException("Unknown pocket: " + id);
        }
        return allocate(server, id, true);
    }

    private static Identifier allocate(MinecraftServer server, Identifier id, boolean reconstruct) {
        return allocate(server, id, reconstruct, ORIGIN, null);
    }

    /**
     * An imported pocket gets a workshop with its saved blocks rebuilt at the same place they were captured from,
     * so its scripts' and portals' workshop coordinates stay correct.
     */
    static Identifier importEditor(MinecraftServer server, Identifier id, BlockPos origin, BlockPos entry) {
        if (dimension(server, id) != null) throw new IllegalArgumentException("Pocket already has a workshop: " + id);
        return allocate(server, id, true, origin, entry);
    }

    private static Identifier allocate(MinecraftServer server, Identifier id, boolean reconstruct, BlockPos origin,
                                       BlockPos entry) {
        PocketEditorData data = PocketEditorData.get(server);
        for (int slot = 1; slot <= MAX_POCKETS; slot++) {
            if (data.usedSlots().contains(slot) || data.pockets().containsValue(slot)) continue;
            Identifier dimension = slotDimension(slot);
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) throw new IllegalStateException("Pocket editor dimension is not registered: " + dimension);
            // An existing pocket is built into its new workshop over several ticks; players who try to enter
            // meanwhile are brought in when it is ready.
            if (reconstruct) PortalTemplateService.reconstructThen(server, level, id, origin,
                    () -> PortalService.finishWorkshop(server, dimension),
                    exception -> {
                        CrazyWorldProgression.LOGGER.error("Could not rebuild the workshop of pocket {}", id, exception);
                        PortalService.finishWorkshop(server, dimension);
                    });
            else if (level.getBlockState(ORIGIN.below()).isAir()) {
                level.setBlock(ORIGIN.below(), Blocks.STONE.defaultBlockState(), 3);
            }
            data.assign(id, slot);
            data.noteBuiltChunk(id, ChunkPos.pack(origin));
            if (reconstruct) {
                PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
                data.setEntry(id, entry != null ? entry
                        : origin.offset(template.spawnX(), template.spawnY(), template.spawnZ()));
                data.setCaptureOrigin(id, origin);
            } else data.setEntry(id, ORIGIN);
            markDirty(server, id);
            return dimension;
        }
        throw new IllegalStateException("All " + MAX_POCKETS + " pocket editor dimensions have been allocated in this world");
    }

    public static BlockPos entry(MinecraftServer server, Identifier id) {
        BlockPos entry = PocketEditorData.get(server).entry(id);
        return entry == null ? ORIGIN : entry;
    }

    /** Repair an old or obstructed entry before any editor teleport occurs. */
    public static BlockPos safeEditorEntry(MinecraftServer server, ServerLevel level, Identifier id) {
        BlockPos desired = entry(server, id);
        if (isSafe(level, desired)) return desired;
        BlockPos nearest = null;
        long distance = Long.MAX_VALUE;
        int minY = Math.max(level.getMinY() + 1, desired.getY() - 32);
        int maxY = Math.min(level.getMinY() + level.getLogicalHeight() - 2, desired.getY() + 12);
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos candidate = new BlockPos(desired.getX() + dx, y, desired.getZ() + dz);
                if (!isSafe(level, candidate)) continue;
                long score = (long) dx * dx + (long) dz * dz
                        + 4L * (y - desired.getY()) * (y - desired.getY());
                if (score < distance) { nearest = candidate; distance = score; }
            }
        }
        if (nearest == null) nearest = emergencyLanding(level, desired);
        setEntry(server, id, nearest);
        return nearest;
    }

    private static boolean isSafe(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private static BlockPos emergencyLanding(ServerLevel level, BlockPos desired) {
        int top = level.getMinY() + level.getLogicalHeight() - 2;
        for (int offset = 0; offset <= 128; offset += 16) {
            int x = desired.getX() + offset, z = desired.getZ();
            for (int y = Math.min(top, Math.max(96, desired.getY() + 4)); y <= top; y++) {
                BlockPos feet = new BlockPos(x, y, z);
                if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
                if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                    level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 3);
                }
                return feet;
            }
        }
        throw new IllegalStateException("No safe editor entry could be created");
    }

    public static void setEntry(MinecraftServer server, Identifier id, BlockPos spawn) {
        if (dimension(server, id) == null) throw new IllegalArgumentException("Unknown pocket: " + id);
        PocketEditorData.get(server).setEntry(id, spawn);
        markDirty(server, id);
    }

    public static void setCaptureOrigin(MinecraftServer server, Identifier id, BlockPos origin) {
        PocketEditorData.get(server).setCaptureOrigin(id, origin);
    }

    /** Called by the level block-change hook; records chunks rather than restricting build coordinates. */
    public static void noteBlockChange(ServerLevel level, BlockPos pos) {
        Identifier dimension = level.dimension().identifier();
        if (!dimension.getNamespace().equals(CrazyWorldProgression.MOD_ID)
                || !dimension.getPath().startsWith("pocket_editor_")) return;
        int slot;
        try { slot = Integer.parseInt(dimension.getPath().substring("pocket_editor_".length())); }
        catch (NumberFormatException exception) { return; }
        PocketEditorData data = PocketEditorData.get(level.getServer());
        Identifier pocket = data.pocketForSlot(slot);
        if (pocket != null) {
            data.noteBuiltChunk(pocket, ChunkPos.pack(pos));
            markDirty(level.getServer(), pocket);
        }
    }

    private static void markDirty(MinecraftServer server, Identifier id) {
        long now = server.getTickCount();
        AUTO_SAVES.computeIfAbsent(server, ignored -> new java.util.HashMap<>())
                .compute(id, (ignored, state) -> state == null
                        ? new AutoSave(now, now, now, null)
                        : new AutoSave(state.firstTick(), now, now, null));
    }

    /** Workshops that had someone in them at the last check. */
    private static final Map<MinecraftServer, java.util.Set<Identifier>> OCCUPIED = new java.util.WeakHashMap<>();

    /**
     * Saves edited pockets only while someone is in their workshop: after a quiet period, or periodically during
     * continuous building. When the last person leaves (any way: walking out, teleporting, disconnecting,
     * dying) the latest state is saved once. Workshops nobody is in are not saved at all.
     */
    public static void tickAutoSave(MinecraftServer server) {
        long now = server.getTickCount();
        java.util.Set<Identifier> occupied = OCCUPIED.computeIfAbsent(server, ignored -> new java.util.HashSet<>());
        if (now % 20L == 0L) {
            for (Identifier id : pockets(server)) {
                Identifier editor = dimension(server, id);
                ServerLevel level = editor == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, editor));
                boolean inside = level != null && !level.players().isEmpty();
                if (inside) occupied.add(id);
                else if (occupied.remove(id)) saveNow(server, id);
            }
        }
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        if (pending == null || pending.isEmpty()) return;
        for (var entry : List.copyOf(pending.entrySet())) {
            Identifier id = entry.getKey();
            if (!occupied.contains(id)) continue;
            AutoSave state = entry.getValue();
            if (now < state.nextAttemptTick() || now - state.lastTick() < SAVE_DELAY_TICKS
                    && now - state.firstTick() < MAX_SAVE_DELAY_TICKS) continue;
            saveNow(server, id);
            break;
        }
    }

    /**
     * Starts saving pending build edits right away (when leaving a workshop or after the autosave delay). The save
     * runs over several ticks; returns false only when it could not even start.
     */
    public static boolean saveNow(MinecraftServer server, Identifier id) {
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        if (pending == null || !pending.containsKey(id)) return true;
        if (PocketCaptures.capturing(server, id) || preparing(server, id)) return true;
        Identifier editor = dimension(server, id);
        if (editor == null) { pending.remove(id); return true; }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, editor));
        if (level == null) return false;
        try {
            PocketCaptures.start(server, level, id, entry(server, id), "CWP autosave", info -> { },
                    exception -> autoSaveFailed(server, id, exception));
            return true;
        } catch (RuntimeException exception) {
            autoSaveFailed(server, id, exception);
            return false;
        }
    }

    private static void autoSaveFailed(MinecraftServer server, Identifier id, RuntimeException exception) {
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        AutoSave state = pending == null ? null : pending.get(id);
        String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (state != null) {
            pending.put(id, new AutoSave(state.firstTick(), state.lastTick(),
                    server.getTickCount() + RETRY_DELAY_TICKS, error));
            if (!error.equals(state.error())) {
                CrazyWorldProgression.LOGGER.warn("Automatic save of pocket {} failed: {}", id, error);
            }
        }
    }

    /** A save that started at startTick finished: edits made before it are saved, later ones still pending. */
    static void savedAt(MinecraftServer server, Identifier id, long startTick) {
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        AutoSave state = pending == null ? null : pending.get(id);
        if (state == null) return;
        if (state.lastTick() < startTick) pending.remove(id);
        // Changes made during the save are still pending; count their waiting time from the save's start, or
        // continuous changes (flowing water, someone building) would start the next save right away, forever.
        else pending.put(id, new AutoSave(startTick, state.lastTick(), 0L, null));
    }

    public static String autoSaveStatus(MinecraftServer server, Identifier id) {
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        AutoSave state = pending == null ? null : pending.get(id);
        if (state == null) return "Build changes save automatically";
        return state.error() == null ? "Build changes are waiting for automatic save"
                : "Automatic save failed: " + state.error();
    }

    /** On server stop: finish running saves and save pending edits, in one step since the server is stopping. */
    public static void flushAutoSaves(MinecraftServer server) {
        PocketCaptures.finishAll(server);
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        if (pending == null) return;
        for (Identifier id : List.copyOf(pending.keySet())) {
            Identifier editor = dimension(server, id);
            ServerLevel level = editor == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, editor));
            if (level == null || preparing(server, id)) continue;
            try {
                PortalTemplateService.captureAll(server, level, id, entry(server, id), "CWP autosave");
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Saving pocket {} on shutdown failed: {}", id, exception.getMessage());
            }
        }
    }

    public static void clearAutoSave(MinecraftServer server, Identifier id) {
        Map<Identifier, AutoSave> pending = AUTO_SAVES.get(server);
        if (pending != null) pending.remove(id);
    }

    public static void clearAutoSaves(MinecraftServer server) {
        AUTO_SAVES.remove(server);
    }

    private record AutoSave(long firstTick, long lastTick, long nextAttemptTick, String error) { }

    /** Scan edited chunks plus the last saved footprint and find a supported entry block. */
    /** The chunk columns a pocket save has to look at: built chunks, the previous saved area, around the entry. */
    static Set<Long> scanChunks(MinecraftServer server, ServerLevel level, Identifier id, BlockPos preferred) {
        Identifier assigned = dimension(server, id);
        if (assigned == null || !assigned.equals(level.dimension().identifier())) {
            throw new IllegalArgumentException("Enter this pocket's build dimension before saving");
        }
        Set<Long> chunks = new LinkedHashSet<>(PocketEditorData.get(server).builtChunks(id));
        chunks.add(ChunkPos.pack(ORIGIN));
        PortalTemplateService.TemplateInfo previous = PortalTemplateService.template(server, id);
        if (previous != null) {
            BlockPos origin = PocketEditorData.get(server).captureOrigin(id);
            if (origin == null) {
                BlockPos entry = PocketEditorData.get(server).entry(id);
                origin = entry == null ? ORIGIN : entry.offset(-previous.spawnX(), -previous.spawnY(),
                        -previous.spawnZ());
            }
            int lastX = origin.getX() + previous.sizeX() - 1;
            int lastZ = origin.getZ() + previous.sizeZ() - 1;
            for (int cx = origin.getX() >> 4; cx <= lastX >> 4; cx++) {
                for (int cz = origin.getZ() >> 4; cz <= lastZ >> 4; cz++) {
                    chunks.add(ChunkPos.pack(cx, cz));
                    if (chunks.size() > 8192) throw new IllegalArgumentException("Pocket spans too many chunks to save");
                }
            }
        }
        int preferredChunkX = preferred.getX() >> 4;
        int preferredChunkZ = preferred.getZ() >> 4;
        for (int cx = preferredChunkX - 2; cx <= preferredChunkX + 2; cx++) {
            for (int cz = preferredChunkZ - 2; cz <= preferredChunkZ + 2; cz++) chunks.add(ChunkPos.pack(cx, cz));
        }
        if (chunks.size() > 8192) throw new IllegalArgumentException("Pocket spans too many chunks to save");
        return chunks;
    }

    /** Finds a build's bounds and a safe entry point chunk by chunk, so a save can spread it over ticks. */
    static final class BuildScanner {
        private final BlockPos preferred;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private BlockPos safeSpawn;
        private long bestDistance = Long.MAX_VALUE;

        BuildScanner(BlockPos preferred) { this.preferred = preferred; }

        void scan(ServerLevel level, LevelChunk chunk) {
            int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z();
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section.hasOnlyAir()) continue;
                int baseY = level.getSectionYFromSectionIndex(index) << 4;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var state = section.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    int worldX = (chunkX << 4) + x, worldY = baseY + y, worldZ = (chunkZ << 4) + z;
                    minX = Math.min(minX, worldX); minY = Math.min(minY, worldY); minZ = Math.min(minZ, worldZ);
                    maxX = Math.max(maxX, worldX); maxY = Math.max(maxY, worldY); maxZ = Math.max(maxZ, worldZ);
                    cursor.set(worldX, worldY, worldZ);
                    if (!state.isFaceSturdy(level, cursor, Direction.UP)
                            || !level.getBlockState(cursor.above()).isAir()
                            || !level.getBlockState(cursor.above(2)).isAir()) continue;
                    long dx = (long) worldX - preferred.getX();
                    long dy = (long) worldY + 1 - preferred.getY();
                    long dz = (long) worldZ - preferred.getZ();
                    long distance = dx * dx + dy * dy * 4 + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        safeSpawn = new BlockPos(worldX, worldY + 1, worldZ);
                    }
                }
            }
        }

        BuildScan result() {
            if (safeSpawn == null) throw new IllegalArgumentException("Build a solid floor with two air blocks above it before saving");
            int top = Math.max(maxY, safeSpawn.getY() + 1);
            BlockPos first = new BlockPos(minX, minY, minZ);
            BlockPos last = new BlockPos(maxX, top, maxZ);
            long volume = ((long) maxX - minX + 1) * ((long) top - minY + 1) * ((long) maxZ - minZ + 1);
            return new BuildScan(first, last, safeSpawn, volume);
        }
    }

    public record BuildScan(BlockPos first, BlockPos last, BlockPos spawn, long volume) { }

    public static List<String> scripts(MinecraftServer server, Identifier id) {
        return PocketEditorData.get(server).scripts(id);
    }

    public static boolean attachScript(MinecraftServer server, Identifier id, String scriptId) {
        if (!exists(server, id)) throw new IllegalArgumentException("Unknown pocket: " + id);
        if (MobForge.SCRIPTS.get(scriptId).isEmpty()) throw new IllegalArgumentException("Unknown MobForge script: " + scriptId);
        ensureEditor(server, id);
        return PocketEditorData.get(server).attachScript(id, scriptId);
    }

    public static boolean removeScript(MinecraftServer server, Identifier id, String scriptId) {
        if (!exists(server, id)) throw new IllegalArgumentException("Unknown pocket: " + id);
        return PocketEditorData.get(server).removeScript(id, scriptId);
    }

    public static void detachScriptEverywhere(MinecraftServer server, String scriptId) {
        PocketEditorData data = PocketEditorData.get(server);
        for (Identifier id : pockets(server)) data.removeScript(id, scriptId);
    }

    public static void startScripts(MinecraftServer server, Identifier id, ServerLevel instance,
                                    BlockPos instanceOrigin, java.util.UUID instanceId) {
        startScripts(server, id, instance, instanceOrigin, instanceId, 0);
    }

    /** resumeEncounters fast-forwards each script past that many completed encounters (raid checkpoints). */
    public static void startScripts(MinecraftServer server, Identifier id, ServerLevel instance,
                                    BlockPos instanceOrigin, java.util.UUID instanceId, int resumeEncounters) {
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
        BlockPos sourceOrigin = PocketEditorData.get(server).captureOrigin(id);
        if (sourceOrigin == null && template != null) {
            BlockPos entry = PocketEditorData.get(server).entry(id);
            if (entry != null) sourceOrigin = entry.offset(-template.spawnX(), -template.spawnY(),
                    -template.spawnZ());
        }
        BlockPos spawn = template == null ? instanceOrigin : instanceOrigin.offset(
                template.spawnX(), template.spawnY(), template.spawnZ());
        BlockPos center = template == null ? instanceOrigin : instanceOrigin.offset(
                template.sizeX() / 2, 0, template.sizeZ() / 2);
        for (String scriptId : scripts(server, id)) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            if (script == null) {
                CrazyWorldProgression.LOGGER.warn("Pocket {} references missing MobForge script {}", id, scriptId);
                continue;
            }
            try {
                MobScript translated = sourceOrigin == null ? script : translate(script,
                        instanceOrigin.subtract(sourceOrigin));
                var anchors = java.util.Map.of("origin", Vec3.atBottomCenterOf(instanceOrigin),
                        "pocket_spawn", Vec3.atBottomCenterOf(spawn),
                        "arena_center", Vec3.atBottomCenterOf(center));
                MobForge.SCRIPT_EXECUTOR.run(translated, instance, anchors, instanceId, instanceId, true, resumeEncounters);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Could not start MobForge script {} for pocket {}", scriptId, id, exception);
            }
        }
    }

    /** The script with every position moved by {@code delta} (pocket instances, scripts placed in the world). */
    public static MobScript translate(MobScript script, BlockPos delta) {
        List<MobScript.Step> steps = script.steps().stream().map(step -> {
            if (step instanceof MobScript.EventBoxStep box) return (MobScript.Step) new MobScript.EventBoxStep(
                    box.name(), shifted(box.min(), delta), shifted(box.max(), delta), box.mode(), box.mobId(),
                    box.playerVariable(), box.countVariable());
            if (step instanceof MobScript.RedstoneStep sensor) return (MobScript.Step) new MobScript.RedstoneStep(
                    sensor.name(), shifted(sensor.position(), delta));
            if (step instanceof MobScript.IfStep test) return (MobScript.Step) new MobScript.IfStep(
                    shiftPowerChecks(test.condition(), delta));
            if (step instanceof MobScript.LoopStep loop) return (MobScript.Step) new MobScript.LoopStep(
                    shiftPowerChecks(loop.condition(), delta), loop.maxIterations());
            if (step instanceof MobScript.WaitUntilStep wait) return (MobScript.Step) new MobScript.WaitUntilStep(
                    shiftPowerChecks(wait.condition(), delta), wait.timeoutTicks());
            if (step instanceof MobScript.EventStep event) return (MobScript.Step) new MobScript.EventStep(
                    shiftPowerChecks(event.condition(), delta), event.variable(), event.operation(), event.value());
            if (step instanceof MobScript.PortalLocationStep portal) return (MobScript.Step) new MobScript.PortalLocationStep(
                    portal.portalId(), portal.action(), portal.locationId(), shifted(portal.position(), delta),
                    portal.target() == null ? null : shifted(portal.target(), delta));
            if (step instanceof MobScript.SpawnMobsStep spawn) return (MobScript.Step) spawn.at(shifted(spawn.location(), delta));
            if (step instanceof MobScript.AreaStep area) return (MobScript.Step) new MobScript.AreaStep(area.name(),
                    shifted(area.center(), delta), area.radius(), area.height(), area.graceTicks(), area.playerVariable(),
                    area.countVariable(), area.defendRadius());
            if (step instanceof MobScript.ReplaceBlocksStep replace && replace.min() != null)
                return (MobScript.Step) new MobScript.ReplaceBlocksStep(replace.from(), replace.to(), replace.area(),
                        shifted(replace.min(), delta), shifted(replace.max(), delta));
            if (step instanceof MobScript.AuraStep aura && aura.center() != null)
                return (MobScript.Step) new MobScript.AuraStep(aura.name(), aura.effectId(), shifted(aura.center(), delta),
                        aura.radius(), aura.intervalTicks(), aura.maxStacks(), aura.durationTicks(), aura.action());
            if (step instanceof MobScript.BuildingStep building) return (MobScript.Step) new MobScript.BuildingStep(
                    building.buildId(), building.action(), shifted(building.location(), delta), building.mode());
            if (step instanceof MobScript.ParticleStep particles) return (MobScript.Step) new MobScript.ParticleStep(
                    particles.particleId(), shifted(particles.location(), delta), particles.offset(),
                    particles.count(), particles.speed());
            if (step instanceof MobScript.ExplosionStep explosion) return (MobScript.Step) new MobScript.ExplosionStep(
                    shifted(explosion.location(), delta), explosion.power());
            if (step instanceof MobScript.SoundStep sound) return (MobScript.Step) new MobScript.SoundStep(
                    sound.soundId(), sound.location() == null ? null : shifted(sound.location(), delta));
            if (step instanceof MobScript.EncounterEndStep end && end.spawn() != null)
                return (MobScript.Step) new MobScript.EncounterEndStep(shifted(end.spawn(), delta));
			if (step instanceof MobScript.FinishInstanceStep finish && finish.spawn() != null)
				return (MobScript.Step) new MobScript.FinishInstanceStep(shifted(finish.spawn(), delta));
            if (!(step instanceof MobScript.SpawnStep spawn)) return step;
            var position = spawn.location();
            MobTemplate.Ai ai = spawn.aiOverride();
            if (ai != null && "target_position".equals(ai.mode()) && ai.target() != null) {
                var target = ai.target();
                ai = new MobTemplate.Ai(ai.mode(), ai.combat(),
                        new MobTemplate.Position(target.x() + delta.getX(), target.y() + delta.getY(),
                                target.z() + delta.getZ()), ai.arrivalRadius(), ai.player(), ai.range(), ai.settings());
            }
            return (MobScript.Step) new MobScript.SpawnStep(spawn.template(),
                    new MobScript.Position(position.x() + delta.getX(), position.y() + delta.getY(),
                            position.z() + delta.getZ()), spawn.offset(), spawn.count(), ai, spawn.tag());
        }).toList();
        return new MobScript(script.id(), steps);
    }

    private static MobScript.Position shifted(MobScript.Position point, BlockPos delta) {
        return new MobScript.Position(point.x() + delta.getX(), point.y() + delta.getY(),
                point.z() + delta.getZ());
    }

    private static String shiftPowerChecks(String condition, BlockPos delta) {
        var pattern = java.util.regex.Pattern.compile("powered\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");
        var matcher = pattern.matcher(condition);
        StringBuilder translated = new StringBuilder();
        while (matcher.find()) {
            String replacement = "powered(" + Math.addExact(Integer.parseInt(matcher.group(1)), delta.getX())
                    + ", " + Math.addExact(Integer.parseInt(matcher.group(2)), delta.getY())
                    + ", " + Math.addExact(Integer.parseInt(matcher.group(3)), delta.getZ()) + ")";
            matcher.appendReplacement(translated, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(translated);
        return translated.toString();
    }

    /** True while an existing pocket is still being built into its workshop. */
    public static boolean preparing(MinecraftServer server, Identifier id) {
        Identifier editor = dimension(server, id);
        return editor != null && InstanceBuilds.building(server, editor.toString());
    }

    public static boolean delete(MinecraftServer server, Identifier id) throws java.io.IOException {
        if (!exists(server, id)) return false;
        if (PocketCaptures.capturing(server, id)) throw new IllegalStateException("This pocket is being saved; try again in a moment");
        if (preparing(server, id)) InstanceBuilds.cancel(server, dimension(server, id).toString());
        Identifier editor = dimension(server, id);
        if (editor != null && PortalService.portals(server).stream()
                .anyMatch(portal -> PortalService.locations(server, portal.id()).values().stream()
                        .anyMatch(location -> location.dimension().equals(editor)))) {
            throw new IllegalStateException("Remove portals placed inside this pocket before deleting it");
        }
        Set<Long> workshopChunks = workshopChunks(server, id);
        Integer slot = PocketEditorData.get(server).pockets().get(id.toString());
        if (PortalTemplateService.template(server, id) != null) PortalTemplateService.delete(server, id);
        ServerLevel level = editor == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, editor));
        if (level != null) {
            for (var player : List.copyOf(level.players())) PortalService.leavePocketEditor(player);
        }
        PocketEditorData.get(server).remove(id);
        // Empty the workshop in the background; its slot is free for a new pocket once that is done.
        if (level != null && slot != null) WorkshopWipes.start(server, slot, level, workshopChunks);
        clearAutoSave(server, id);
        return true;
    }

    /** Every chunk column of a pocket's workshop that may hold blocks: built chunks, the saved area, the entry. */
    private static Set<Long> workshopChunks(MinecraftServer server, Identifier id) {
        PocketEditorData data = PocketEditorData.get(server);
        Set<Long> chunks = new LinkedHashSet<>(data.builtChunks(id));
        List<BlockPos> centres = new java.util.ArrayList<>(List.of(ORIGIN));
        if (data.entry(id) != null) centres.add(data.entry(id));
        for (BlockPos centre : centres)
            for (int cx = (centre.getX() >> 4) - 2; cx <= (centre.getX() >> 4) + 2; cx++)
                for (int cz = (centre.getZ() >> 4) - 2; cz <= (centre.getZ() >> 4) + 2; cz++) chunks.add(ChunkPos.pack(cx, cz));
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
        BlockPos origin = data.captureOrigin(id);
        if (template != null && origin != null) {
            for (int cx = origin.getX() >> 4; cx <= (origin.getX() + template.sizeX() - 1) >> 4; cx++)
                for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + template.sizeZ() - 1) >> 4; cz++)
                    chunks.add(ChunkPos.pack(cx, cz));
        }
        return chunks;
    }

    public static Identifier slotDimension(int slot) {
        return CrazyWorldProgression.id("pocket_editor_" + slot);
    }
}
