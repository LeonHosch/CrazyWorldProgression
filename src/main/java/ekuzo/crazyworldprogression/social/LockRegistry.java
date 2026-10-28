package ekuzo.crazyworldprogression.social;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Every locked (non-Public) chest, by position, so limits can be counted without loading chunks: each player may have
 * {@link #PLAYER_LIMIT} Private and Friends chests together, and a clan {@link #CLAN_PER_MEMBER} per member by tier.
 * Entries whose chest is gone are dropped when their chunk is loaded and counted.
 */
public final class LockRegistry extends SavedData {
    public static final int PLAYER_LIMIT = 5;
    /** Clan chests per member at clan tier 1 to 5. */
    public static final int[] CLAN_PER_MEMBER = {2, 3, 4, 5, 6};

    static final Codec<LockRegistry> CODEC = Codec.unboundedMap(Codec.STRING, ChestLocks.Lock.CODEC)
            .xmap(LockRegistry::new, registry -> registry.locks);
    private static final SavedDataType<LockRegistry> TYPE = new SavedDataType<>(CrazyWorldProgression.id("chest_locks"),
            LockRegistry::new, CODEC, null);

    /** "dimension|x|y|z" to the lock there. */
    private final Map<String, ChestLocks.Lock> locks = new HashMap<>();

    private LockRegistry() { }

    private LockRegistry(Map<String, ChestLocks.Lock> locks) { this.locks.putAll(locks); }

    static LockRegistry get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** Records a chest's lock (Public ones are not counted, so they are left out). */
    void put(Level level, BlockPos pos, ChestLocks.Lock lock) {
        String key = key(level, pos);
        if (lock == null || lock.mode() == ChestLocks.Mode.PUBLIC) {
            if (locks.remove(key) != null) setDirty();
        } else {
            locks.put(key, lock);
            setDirty();
        }
    }

    /** Private and Friends chests of a player. */
    int personal(MinecraftServer server, UUID player) {
        clean(server);
        return (int) locks.values().stream().filter(lock -> lock.owner().equals(player)
                && (lock.mode() == ChestLocks.Mode.PRIVATE || lock.mode() == ChestLocks.Mode.FRIENDS)).count();
    }

    /** Chests locked to a clan. */
    int clan(MinecraftServer server, UUID clan) {
        clean(server);
        return (int) locks.values().stream().filter(lock -> lock.mode() == ChestLocks.Mode.CLAN
                && lock.clan().map(clan::equals).orElse(false)).count();
    }

    public static int clanLimit(SocialData.Clan clan) { return clan.members.size() * CLAN_PER_MEMBER[clan.tier() - 1]; }

    /** Drops entries whose chest is loaded and no longer has that lock (broken, blown up, ...). */
    private void clean(MinecraftServer server) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, ChestLocks.Lock>> it = locks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ChestLocks.Lock> entry = it.next();
            String[] parts = entry.getKey().split("\\|");
            Identifier dimension = Identifier.tryParse(parts[0]);
            ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) continue;
            BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            if (!level.isLoaded(pos)) continue;
            if (!entry.getValue().equals(ChestLocks.lockAt(level, pos))) {
                it.remove();
                changed = true;
            }
        }
        if (changed) setDirty();
    }
}
