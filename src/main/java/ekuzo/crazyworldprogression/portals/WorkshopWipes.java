package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.cleanup.TickBudget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * Resets the workshop of a deleted pocket so its slot can be given to a new pocket.
 * <ul>
 *   <li>Right away: the workshop's chunks are emptied in the background (whole sections at a time, lowest
 *       priority), then the slot is free again.</li>
 *   <li>On the next server start: the folders of workshops no pocket uses are deleted before any world loads.
 *       That gives the disk space back (Minecraft never shrinks region files) and also resets workshops whose
 *       background reset was interrupted, or that were deleted before this existed.</li>
 * </ul>
 */
public final class WorkshopWipes {
    private static final String MARKER = "cwp-workshop-wipes.txt";
    private static final Map<MinecraftServer, Map<Integer, Wipe>> WIPES = new WeakHashMap<>();
    /** Slots whose folders were deleted at startup, freed once the saved data is loaded. */
    private static final Map<MinecraftServer, Set<Integer>> DELETED_AT_START = new WeakHashMap<>();

    private WorkshopWipes() { }

    private static final class Wipe {
        final ServerLevel level;
        final long[] columns;
        long next;

        Wipe(ServerLevel level, long[] columns) { this.level = level; this.columns = columns; }
    }

    static void initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(WorkshopWipes::deleteMarkedFolders);
        ServerLifecycleEvents.SERVER_STARTED.register(WorkshopWipes::freeDeletedSlots);
        ServerLifecycleEvents.SERVER_STOPPING.register(WorkshopWipes::markUnusedFolders);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WIPES.remove(server);
            DELETED_AT_START.remove(server);
        });
    }

    /** Starts emptying a deleted pocket's workshop; its slot stays taken until that is done. */
    static void start(MinecraftServer server, int slot, ServerLevel level, Collection<Long> columns) {
        PortalTemplateService.discardAllEntities(level);
        long[] packed = columns.stream().mapToLong(Long::longValue).distinct().toArray();
        WIPES.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(slot, new Wipe(level, packed));
        CrazyWorldProgression.LOGGER.info("Resetting pocket workshop {} ({} chunks)", slot, packed.length);
    }

    public static int count(MinecraftServer server) {
        Map<Integer, Wipe> wipes = WIPES.get(server);
        return wipes == null ? 0 : wipes.size();
    }

    static boolean wiping(MinecraftServer server, int slot) {
        Map<Integer, Wipe> wipes = WIPES.get(server);
        return wipes != null && wipes.containsKey(slot);
    }

    /** Called from the portal tick after instance cleanup, at the same lowest priority. */
    static void tick(MinecraftServer server) {
        Map<Integer, Wipe> wipes = WIPES.get(server);
        if (wipes == null || wipes.isEmpty() || !TickBudget.available(server, TickBudget.Priority.CLEANUP)) return;
        var entry = wipes.entrySet().iterator().next();
        Wipe wipe = entry.getValue();
        int slot = entry.getKey();
        try {
            wipe.next = PortalTemplateService.clearColumns(wipe.level, "pocket_editor_" + slot, wipe.columns, wipe.next);
        } catch (RuntimeException exception) {
            // The folder is deleted on the next start anyway, so the slot just stays taken until then.
            CrazyWorldProgression.LOGGER.error("Resetting pocket workshop {} failed", slot, exception);
            wipes.remove(slot);
            return;
        }
        if (wipe.next >= 0) return;
        wipes.remove(slot);
        PortalTemplateService.discardAllEntities(wipe.level);
        PocketEditorData.get(server).freeSlot(slot);
        CrazyWorldProgression.LOGGER.info("Pocket workshop {} is empty and free again", slot);
    }

    private static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    static Path folder(MinecraftServer server, int slot) {
        return root(server).resolve("dimensions").resolve(CrazyWorldProgression.MOD_ID).resolve("pocket_editor_" + slot);
    }

    /** Remembers which workshop folders no pocket uses, so the next start can delete them. */
    private static void markUnusedFolders(MinecraftServer server) {
        try {
            PocketEditorData data = PocketEditorData.get(server);
            List<String> slots = new ArrayList<>();
            for (int slot = 1; slot <= PocketEditorService.MAX_POCKETS; slot++) {
                if (data.pocketForSlot(slot) == null && Files.isDirectory(folder(server, slot)))
                    slots.add(Integer.toString(slot));
            }
            Path marker = root(server).resolve(MARKER);
            if (slots.isEmpty()) Files.deleteIfExists(marker);
            else Files.write(marker, slots);
        } catch (IOException | RuntimeException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not note unused pocket workshops", exception);
        }
    }

    /** Before any world loads: delete the folders of the workshops noted at the last shutdown. */
    private static void deleteMarkedFolders(MinecraftServer server) {
        Path marker = root(server).resolve(MARKER);
        if (!Files.isRegularFile(marker)) return;
        Set<Integer> deleted = new HashSet<>();
        try {
            for (String line : Files.readAllLines(marker)) {
                int slot;
                try { slot = Integer.parseInt(line.trim()); }
                catch (NumberFormatException exception) { continue; }
                if (slot < 1 || slot > PocketEditorService.MAX_POCKETS) continue;
                Path folder = folder(server, slot);
                if (Files.isDirectory(folder)) deleteTree(folder);
                deleted.add(slot);
            }
            Files.delete(marker);
            if (!deleted.isEmpty()) CrazyWorldProgression.LOGGER.info("Deleted unused pocket workshops {}", deleted);
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not delete unused pocket workshops", exception);
        }
        DELETED_AT_START.put(server, deleted);
    }

    private static void freeDeletedSlots(MinecraftServer server) {
        Set<Integer> deleted = DELETED_AT_START.remove(server);
        if (deleted == null) return;
        PocketEditorData data = PocketEditorData.get(server);
        for (int slot : deleted) data.freeSlot(slot);
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
