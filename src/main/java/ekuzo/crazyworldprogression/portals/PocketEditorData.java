package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent pocket-to-authoring-dimension assignments. Retired slots are never reassigned. */
final class PocketEditorData extends SavedData {
    private static final Codec<PocketEditorData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("pockets", Map.of())
                    .forGetter(data -> data.pockets),
            Codec.INT.listOf().optionalFieldOf("used_slots", List.of()).forGetter(data -> data.usedSlots),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("entry_points", Map.of())
                    .forGetter(data -> data.entryPoints),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("capture_origins", Map.of())
                    .forGetter(data -> data.captureOrigins),
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("mob_scripts", Map.of())
                    .forGetter(data -> data.mobScripts),
            Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).optionalFieldOf("built_chunks", Map.of())
                    .forGetter(data -> data.builtChunks)
    ).apply(instance, PocketEditorData::new));
    private static final SavedDataType<PocketEditorData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("pocket_editors"), PocketEditorData::new, CODEC, null);

    private final Map<String, Integer> pockets = new LinkedHashMap<>();
    private final Map<String, Long> entryPoints = new LinkedHashMap<>();
    private final Map<String, Long> captureOrigins = new LinkedHashMap<>();
    private final Map<String, List<String>> mobScripts = new LinkedHashMap<>();
    private final Map<String, List<Long>> builtChunks = new LinkedHashMap<>();
    private List<Integer> usedSlots = List.of();

    private PocketEditorData() {
    }

    private PocketEditorData(Map<String, Integer> pockets, List<Integer> usedSlots,
                             Map<String, Long> entryPoints, Map<String, Long> captureOrigins,
                             Map<String, List<String>> mobScripts, Map<String, List<Long>> builtChunks) {
        this.pockets.putAll(pockets);
        this.usedSlots = List.copyOf(usedSlots);
        this.entryPoints.putAll(entryPoints);
        this.captureOrigins.putAll(captureOrigins);
        mobScripts.forEach((id, scripts) -> this.mobScripts.put(id, List.copyOf(scripts)));
        builtChunks.forEach((id, chunks) -> this.builtChunks.put(id, List.copyOf(chunks)));
    }

    static PocketEditorData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Map<String, Integer> pockets() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pockets));
    }

    Identifier pocketForSlot(int slot) {
        for (var entry : pockets.entrySet()) {
            if (entry.getValue() == slot) return Identifier.parse(entry.getKey());
        }
        return null;
    }

    List<Integer> usedSlots() {
        return usedSlots;
    }

    BlockPos entry(Identifier id) {
        Long packed = entryPoints.get(id.toString());
        return packed == null ? null : BlockPos.of(packed);
    }

    void setEntry(Identifier id, BlockPos position) {
        entryPoints.put(id.toString(), position.asLong());
        setDirty();
    }

    BlockPos captureOrigin(Identifier id) {
        Long packed = captureOrigins.get(id.toString());
        return packed == null ? null : BlockPos.of(packed);
    }

    void setCaptureOrigin(Identifier id, BlockPos position) {
        captureOrigins.put(id.toString(), position.asLong());
        setDirty();
    }

    List<String> scripts(Identifier id) {
        return mobScripts.getOrDefault(id.toString(), List.of());
    }

    List<Long> builtChunks(Identifier id) {
        return builtChunks.getOrDefault(id.toString(), List.of());
    }

    void noteBuiltChunk(Identifier id, long chunk) {
        var previous = builtChunks(id);
        if (previous.contains(chunk)) return;
        var updated = new java.util.ArrayList<>(previous);
        updated.add(chunk);
        builtChunks.put(id.toString(), List.copyOf(updated));
        setDirty();
    }

    boolean attachScript(Identifier id, String scriptId) {
        var updated = new java.util.ArrayList<>(scripts(id));
        if (updated.contains(scriptId)) return false;
        updated.add(scriptId);
        mobScripts.put(id.toString(), List.copyOf(updated));
        setDirty();
        return true;
    }

    boolean removeScript(Identifier id, String scriptId) {
        var updated = new java.util.ArrayList<>(scripts(id));
        if (!updated.remove(scriptId)) return false;
        if (updated.isEmpty()) mobScripts.remove(id.toString());
        else mobScripts.put(id.toString(), List.copyOf(updated));
        setDirty();
        return true;
    }

    void assign(Identifier id, int slot) {
        pockets.put(id.toString(), slot);
        if (!usedSlots.contains(slot)) {
            var updated = new java.util.ArrayList<>(usedSlots);
            updated.add(slot);
            usedSlots = List.copyOf(updated);
        }
        setDirty();
    }

    boolean remove(Identifier id) {
        if (pockets.remove(id.toString()) == null) return false;
        entryPoints.remove(id.toString());
        captureOrigins.remove(id.toString());
        mobScripts.remove(id.toString());
        builtChunks.remove(id.toString());
        setDirty();
        return true;
    }
}
