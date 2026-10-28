package ekuzo.crazyworldprogression.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.UUID;

/**
 * Chest locks: chests, trapped chests and barrels remember who placed them and who may open them: Public (everyone),
 * Private (the owner), Friends (the owner and their friends) or Clan (the members of the clan it was locked to; it
 * stays with that clan when the owner leaves or is kicked). They start Public. The owner (and for a clan chest the
 * clan's officers, co-leaders and leader) changes the mode with the lock button in the chest window (ChestLockNetworking), and is
 * the only one who can break a locked one. Limits (LockRegistry): 5 Private and Friends chests per player, clan chests
 * by members and clan tier. Hoppers only pull out of a locked chest when the player who placed the hopper may open it;
 * hopper minecarts never do. Operators bypass locks.
 */
public final class ChestLocks {
    public enum Mode {
        PRIVATE("Private"), FRIENDS("Friends"), CLAN("Clan"), PUBLIC("Public");

        private final String label;

        Mode(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** Who placed it, its mode, and for Clan the clan it is locked to. */
    public record Lock(UUID owner, Mode mode, Optional<UUID> clan) {
        static final Codec<Lock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Lock::owner),
                Codec.STRING.xmap(Mode::valueOf, Mode::name).optionalFieldOf("mode", Mode.PRIVATE).forGetter(Lock::mode),
                UUIDUtil.CODEC.optionalFieldOf("clan").forGetter(Lock::clan)
        ).apply(instance, Lock::new));
    }

    /** On chests and barrels the lock; on hoppers only the owner (for pulling out of locked chests). */
    public static final AttachmentType<Lock> LOCK = AttachmentRegistry.create(CrazyWorldProgression.id("chest_lock"),
            builder -> builder.persistent(Lock.CODEC));

    private ChestLocks() { }

    public static void initialize() {
        ChestLockNetworking.initialize();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            BlockEntity entity = level.getBlockEntity(pos);
            if (!lockable(entity)) return InteractionResult.PASS;
            if (!canAccess(serverPlayer, level, pos)) {
                Lock blocking = blockingLock(serverPlayer, level, pos);
                serverPlayer.sendOverlayMessage(Component.literal("Locked (" + describe(serverPlayer.level().getServer(), blocking)
                        + ")").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            ChestLockNetworking.sendInfo(serverPlayer, level, pos);
            ekuzo.crazyworldprogression.storage.StorageNetworking.sendFilterInfo(serverPlayer, level, pos);
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !lockable(entity)) return true;
            Lock lock = lockAt(level, pos);
            if (lock == null || lock.mode() == Mode.PUBLIC || canManage(serverPlayer, lock)) return true;
            serverPlayer.sendOverlayMessage(Component.literal("Only its owner can break this chest").withStyle(ChatFormatting.RED));
            return false;
        });
    }

    public static boolean lockable(BlockEntity entity) { return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity; }

    public static Lock lockAt(Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity == null ? null : entity.getAttached(LOCK);
    }

    private static void set(Level level, BlockPos pos, Lock lock) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return;
        entity.setAttached(LOCK, lock);
        entity.setChanged();
        if (!(entity instanceof HopperBlockEntity) && level.getServer() != null) LockRegistry.get(level.getServer()).put(level, pos, lock);
    }

    /** The other half of a double chest, or null. */
    private static BlockPos otherHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)
                || state.getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return null;
        return ChestBlock.getConnectedBlockPos(pos, state);
    }

    /** A lock on this block or its other half that keeps the player out, or null. */
    private static Lock blockingLock(ServerPlayer player, Level level, BlockPos pos) {
        Lock lock = lockAt(level, pos);
        if (lock != null && !allowed(player.level().getServer(), player.getUUID(), lock) && !op(player)) return lock;
        BlockPos other = otherHalf(level, pos);
        Lock otherLock = other == null ? null : lockAt(level, other);
        if (otherLock != null && !allowed(player.level().getServer(), player.getUUID(), otherLock) && !op(player)) return otherLock;
        return null;
    }

    public static boolean canAccess(ServerPlayer player, Level level, BlockPos pos) { return blockingLock(player, level, pos) == null; }

    private static boolean op(ServerPlayer player) {
        return player.level().getServer().getPlayerList().isOp(player.nameAndId());
    }

    /** Whether this player may open a container with this lock. */
    public static boolean allowed(MinecraftServer server, UUID player, Lock lock) {
        SocialData data = SocialData.get(server);
        return switch (lock.mode()) {
            case PUBLIC -> true;
            case PRIVATE -> lock.owner().equals(player);
            case FRIENDS -> lock.owner().equals(player) || data.friends(lock.owner(), player);
            case CLAN -> {
                SocialData.Clan clan = data.clan(lock.clan().orElse(null));
                // A disbanded clan's chests go back to their owner.
                yield clan == null ? lock.owner().equals(player) : clan.members.contains(player);
            }
        };
    }

    /**
     * The owner, or for a clan chest the clan's officers, co-leaders and leader (and operators), may change the mode and
     * break it; plain members only open clan chests.
     */
    private static boolean canManage(ServerPlayer player, Lock lock) {
        if (op(player)) return true;
        if (lock.mode() == Mode.CLAN) {
            SocialData.Clan clan = SocialData.get(player.level().getServer()).clan(lock.clan().orElse(null));
            if (clan != null) return clan.chestKeeper(player.getUUID());
        }
        return lock.owner().equals(player.getUUID());
    }

    /** Next mode: Private, Friends, Clan (when the player is in a clan), Public. Both halves of a double chest change. */
    /**
     * Changes a chest's mode (both halves of a double chest), if the player may and the limits allow. Throws with the
     * reason otherwise. Whoever changes a clan chest to another mode becomes its owner.
     */
    static Lock setMode(ServerPlayer player, Level level, BlockPos pos, Mode mode) {
        MinecraftServer server = player.level().getServer();
        Lock lock = lockAt(level, pos);
        if (lock == null) lock = new Lock(player.getUUID(), Mode.PUBLIC, Optional.empty());
        if (!canManage(player, lock)) throw new IllegalArgumentException("Only the chest's owner can change its lock");
        if (lock.mode() == mode) return lock;
        SocialData.Clan clan = SocialData.get(server).clanOf(player.getUUID());
        UUID owner = lock.mode() == Mode.CLAN ? player.getUUID() : lock.owner();
        LockRegistry registry = LockRegistry.get(server);
        boolean personalNow = lock.owner().equals(owner) && (lock.mode() == Mode.PRIVATE || lock.mode() == Mode.FRIENDS);
        if ((mode == Mode.PRIVATE || mode == Mode.FRIENDS) && !personalNow
                && registry.personal(server, owner) >= LockRegistry.PLAYER_LIMIT)
            throw new IllegalArgumentException("You already have " + LockRegistry.PLAYER_LIMIT + " Private or Friends chests");
        if (mode == Mode.CLAN) {
            if (clan == null) throw new IllegalArgumentException("Join a clan to lock chests to it");
            if (!clan.chestKeeper(player.getUUID())) throw new IllegalArgumentException("Only officers and up can make clan chests");
            if (registry.clan(server, clan.id()) >= LockRegistry.clanLimit(clan))
                throw new IllegalArgumentException("Your clan already has " + LockRegistry.clanLimit(clan) + " clan chests");
        }
        Lock updated = new Lock(owner, mode, mode == Mode.CLAN ? Optional.of(clan.id()) : Optional.empty());
        set(level, pos, updated);
        BlockPos other = otherHalf(level, pos);
        if (other != null) set(level, other, updated);
        return updated;
    }

    /** Whether this player may change the lock of the chest at this position. */
    static boolean mayManage(ServerPlayer player, Level level, BlockPos pos) {
        Lock lock = lockAt(level, pos);
        return lock == null || canManage(player, lock);
    }

    public static String describe(MinecraftServer server, Lock lock) {
        if (lock == null) return "Public";
        SocialData data = SocialData.get(server);
        return switch (lock.mode()) {
            case PUBLIC -> "Public";
            case PRIVATE -> "Private, " + data.name(lock.owner());
            case FRIENDS -> "Friends of " + data.name(lock.owner());
            case CLAN -> {
                SocialData.Clan clan = data.clan(lock.clan().orElse(null));
                yield clan == null ? "Clan (disbanded), " + data.name(lock.owner()) : "Clan [" + clan.tag() + "]";
            }
        };
    }

    /**
     * A block was placed by a player: chests and barrels start Public, owned by them (a chest joining a neighbour's half
     * takes that half's lock, and breaks again when the player may not open it); hoppers remember who placed them.
     */
    public static void placed(Level level, BlockPos pos, LivingEntity placer) {
        if (level.isClientSide() || !(placer instanceof ServerPlayer player)) return;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof HopperBlockEntity) {
            set(level, pos, new Lock(player.getUUID(), Mode.PRIVATE, Optional.empty()));
            return;
        }
        if (!lockable(entity)) return;
        BlockPos other = otherHalf(level, pos);
        Lock neighbour = other == null ? null : lockAt(level, other);
        if (neighbour != null) {
            if (!allowed(player.level().getServer(), player.getUUID(), neighbour) && !op(player)) {
                level.destroyBlock(pos, true, player);
                player.sendOverlayMessage(Component.literal("You can't attach a chest to one you can't open").withStyle(ChatFormatting.RED));
                return;
            }
            set(level, pos, neighbour);
            return;
        }
        set(level, pos, new Lock(player.getUUID(), Mode.PUBLIC, Optional.empty()));
    }

    /** Whether a hopper may pull items out of the container at this position. */
    public static boolean hopperMayPull(Level level, Object hopper, BlockPos source) {
        if (level.isClientSide() || !(level.getServer() instanceof MinecraftServer server)) return true;
        Lock lock = lockAt(level, source);
        BlockPos other = otherHalf(level, source);
        Lock otherLock = other == null ? null : lockAt(level, other);
        if ((lock == null || lock.mode() == Mode.PUBLIC) && (otherLock == null || otherLock.mode() == Mode.PUBLIC)) return true;
        if (!(hopper instanceof HopperBlockEntity block) || block.getAttached(LOCK) == null) return false;
        UUID owner = block.getAttached(LOCK).owner();
        return (lock == null || allowed(server, owner, lock)) && (otherLock == null || allowed(server, owner, otherLock));
    }

    /** Whether a player may open it (for commands and other mods). */
    public static boolean mayOpen(Player player, Level level, BlockPos pos) {
        return !(player instanceof ServerPlayer serverPlayer) || canAccess(serverPlayer, level, pos);
    }
}
