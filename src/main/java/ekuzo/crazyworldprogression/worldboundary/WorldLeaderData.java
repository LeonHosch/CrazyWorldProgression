package ekuzo.crazyworldprogression.worldboundary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Optional;
import java.util.UUID;

/** The optional world leader, independent of the title a content mod gives the role. */
final class WorldLeaderData extends SavedData {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    static final Codec<WorldLeaderData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.optionalFieldOf("leader").forGetter(data -> Optional.ofNullable(data.leader))
    ).apply(instance, WorldLeaderData::new));
    private static final SavedDataType<WorldLeaderData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("world_leader"), WorldLeaderData::new, CODEC, null);

    private UUID leader;

    private WorldLeaderData() { }

    private WorldLeaderData(Optional<UUID> leader) {
        this.leader = leader.orElse(null);
    }

    static WorldLeaderData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<UUID> leader() { return Optional.ofNullable(leader); }

    void setLeader(UUID player) { leader = player; setDirty(); }

    void clearLeader() { leader = null; setDirty(); }
}
