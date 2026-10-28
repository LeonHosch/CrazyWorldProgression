package ekuzo.crazyworldprogression.collectibles;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Summoning and riding mounts, and the combat rules for riding:
 * <ul>
 *   <li>The mount key summons the selected mount under the player, or sends it away while riding it.</li>
 *   <li>Damage while riding does nothing special, unless it leaves the player below {@link #KNOCK_OFF_HEALTH} of their
 *       max health: then they are knocked off the mount and stunned for {@link #STUN_TICKS} ticks.</li>
 *   <li>After taking any damage a player cannot mount anything (mounts, horses, boats, ...) for
 *       {@link #NO_MOUNT_TICKS} ticks.</li>
 * </ul>
 */
public final class MountService {
    public static final double KNOCK_OFF_HEALTH = 0.6;
    public static final int STUN_TICKS = 40;
    public static final int NO_MOUNT_TICKS = 200;
    private static final Identifier STUN = CrazyWorldProgression.id("mount_stun");
    /** Game time of each player's last hit, and when their stun ends. */
    private static final Map<UUID, Long> LAST_HIT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STUNNED_UNTIL = new ConcurrentHashMap<>();

    private MountService() { }

    static void initialize() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player) || damageTaken <= 0 || !player.isAlive()) return;
            LAST_HIT.put(player.getUUID(), player.level().getGameTime());
            if (player.getVehicle() instanceof CwpMount && player.getHealth() < player.getMaxHealth() * KNOCK_OFF_HEALTH)
                knockOff(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(MountService::tick);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_HIT.remove(handler.player.getUUID());
            if (STUNNED_UNTIL.remove(handler.player.getUUID()) != null) unstun(handler.player);
            if (handler.player.getVehicle() instanceof CwpMount mount) mount.discard();
        });
    }

    /** Whether the player may mount now; tells them how long to wait when not. */
    public static boolean canMount(ServerPlayer player, boolean tell) {
        long since = player.level().getGameTime() - LAST_HIT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
        boolean stunned = STUNNED_UNTIL.containsKey(player.getUUID());
        if (since >= NO_MOUNT_TICKS && !stunned) return true;
        if (tell) player.sendOverlayMessage(Component.literal("You can't mount for "
                + Math.max(1, (NO_MOUNT_TICKS - since + 19) / 20) + "s after being hit").withStyle(ChatFormatting.RED));
        return false;
    }

    /** The mount key: summon the selected mount and ride it, or send it away while riding it. */
    static void toggle(ServerPlayer player) {
        if (player.getVehicle() instanceof CwpMount mount) {
            player.stopRiding();
            mount.discard();
            return;
        }
        if (player.isSpectator() || !player.isAlive()) return;
        Collectibles.MountDefinition definition = Collectibles.mount(Identifier.tryParse(
                String.valueOf(Collectibles.selected(player, Collectibles.MOUNT))));
        if (definition == null || !Collectibles.unlocked(player, Collectibles.MOUNT, definition.id())) {
            player.sendOverlayMessage(Component.literal("Select a mount in your Loot Journal first").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (player.isPassenger()) return;
        if (!canMount(player, true)) return;
        ServerLevel level = (ServerLevel) player.level();
        CwpMount mount = definition.type().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mount == null) return;
        mount.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0);
        mount.setUp(player.getUUID(), definition);
        level.addFreshEntity(mount);
        level.sendParticles(ParticleTypes.POOF, mount.getX(), mount.getY() + 0.8, mount.getZ(), 12, 0.6, 0.4, 0.6, 0.02);
        player.startRiding(mount);
    }

    private static void knockOff(ServerPlayer player) {
        CwpMount mount = (CwpMount) player.getVehicle();
        player.stopRiding();
        mount.discard();
        STUNNED_UNTIL.put(player.getUUID(), player.level().getGameTime() + STUN_TICKS);
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        AttributeModifier stop = new AttributeModifier(STUN, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        if (speed != null && !speed.hasModifier(STUN)) speed.addTransientModifier(stop);
        if (jump != null && !jump.hasModifier(STUN)) jump.addTransientModifier(stop);
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getEyeY() + 0.3, player.getZ(), 12, 0.3, 0.1, 0.3, 0.1);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 0.7F);
        player.sendOverlayMessage(Component.literal("Knocked off your mount!").withStyle(ChatFormatting.RED));
    }

    private static void tick(MinecraftServer server) {
        STUNNED_UNTIL.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            if (player.level().getGameTime() < entry.getValue()) return false;
            unstun(player);
            return true;
        });
    }

    private static void unstun(ServerPlayer player) {
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (speed != null) speed.removeModifier(STUN);
        if (jump != null) jump.removeModifier(STUN);
    }

    public static boolean stunned(ServerPlayer player) { return STUNNED_UNTIL.containsKey(player.getUUID()); }
}
