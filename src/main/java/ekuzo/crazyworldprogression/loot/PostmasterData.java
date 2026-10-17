package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persisted per-player item storage. Empty stacks and all item components round-trip. */
final class PostmasterData extends SavedData {
    static final Codec<PostmasterData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, ItemStack.OPTIONAL_CODEC.listOf())
                    .optionalFieldOf("players", Map.of()).forGetter(data -> data.players)
    ).apply(instance, PostmasterData::new));
    private static final SavedDataType<PostmasterData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("postmaster"), PostmasterData::new, CODEC, null);
    private final Map<String, List<ItemStack>> players = new LinkedHashMap<>();

    PostmasterData() { }
    private PostmasterData(Map<String, List<ItemStack>> players) {
        players.forEach((id, items) -> this.players.put(id, copy(items)));
    }
    static PostmasterData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    List<ItemStack> slots(UUID playerId) {
        List<ItemStack> result = new ArrayList<>(PostmasterService.SLOTS);
        List<ItemStack> stored = players.getOrDefault(playerId.toString(), List.of());
        for (int i = 0; i < PostmasterService.SLOTS; i++)
            result.add(i < stored.size() ? stored.get(i).copy() : ItemStack.EMPTY);
        return result;
    }
    void save(UUID playerId, List<ItemStack> slots) {
        if (slots.size() != PostmasterService.SLOTS) throw new IllegalArgumentException("Invalid postmaster size");
        players.put(playerId.toString(), copy(slots));
        setDirty();
    }
    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }
}
