package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@code unlockStaticCharge} stat (Stormcaller): each damaging hit charges the target with a Static Charge stack.
 * At {@link #DISCHARGE_AT} stacks it discharges: the target and up to {@link #CHAIN_TARGETS} enemies within
 * {@link #RADIUS} blocks take 1 damage per stack plus {@link #DISCHARGE_SHARE} of the hit that completed the charge.
 * A charged target that dies first zaps up to {@link #DEATH_TARGETS} nearby enemies for 1 damage per stack. Stacks
 * fade after {@link #FADE_TICKS} without a new one. Discharge damage counts as the Stormcaller's but never charges
 * anything itself.
 * <p>
 * {@code unlockStaticStorm} (the 6-piece bonus) makes the radius {@link #STORM_RADIUS} and doubles both target
 * counts. Whoever added the latest stack decides which version applies.
 */
public final class StaticCharge {
    public static final String STAT = "unlockStaticCharge";
    /** The chain lightning sound; nearby clients play it with the bolts (ChainLightningRenderer). */
    public static final net.minecraft.sounds.SoundEvent DISCHARGE_SOUND = net.minecraft.core.Registry.register(
            net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT, CrazyWorldProgression.id("static_discharge"),
            net.minecraft.sounds.SoundEvent.createVariableRangeEvent(CrazyWorldProgression.id("static_discharge")));
    public static final String STORM = "unlockStaticStorm";
    public static final double STORM_RADIUS = 6.0;
    public static final int DISCHARGE_AT = 5;
    public static final int CHAIN_TARGETS = 3;
    public static final int DEATH_TARGETS = 2;
    public static final double RADIUS = 4.0;
    public static final int FADE_TICKS = 200;
    public static final float DISCHARGE_SHARE = 0.2F;
    /** Charged entities (weak keys: unloaded or removed entities just disappear). Server thread only. */
    private static final Map<LivingEntity, Charge> CHARGES = new WeakHashMap<>();
    private static boolean discharging;

    private StaticCharge() { }

    private record Charge(int stacks, long lastTick, ServerPlayer source) { }

    public static void initialize() {
        SkillStatRegistry.registerUnlockStat(STAT, "stat.crazy-world-progression.unlock_static_charge");
        SkillStatRegistry.registerUnlockStat(STORM, "stat.crazy-world-progression.unlock_static_storm");
        PayloadTypeRegistry.clientboundPlay().register(ChainPayload.TYPE, ChainPayload.CODEC);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (discharging || taken <= 0 || !entity.isAlive() || !(source.getEntity() instanceof ServerPlayer player)
                    || player == entity || SkillStatRegistry.getTotal(player, STAT) <= 0) return;
            charge(player, entity, taken);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Charge charge = CHARGES.remove(entity);
            if (charge == null || !(entity.level() instanceof ServerLevel level)) return;
            boolean storm = storm(charge.source());
            List<LivingEntity> near = nearbyEnemies(level, entity, charge.source(),
                    storm ? DEATH_TARGETS * 2 : DEATH_TARGETS, storm ? STORM_RADIUS : RADIUS);
            if (near.isEmpty()) return;
            showChain(entity, near);
            for (LivingEntity next : near) zap(level, charge.source(), next, charge.stacks());
        });
        ServerTickEvents.END_SERVER_TICK.register(StaticCharge::tick);
    }

    private static void charge(ServerPlayer player, LivingEntity target, float damage) {
        long now = player.level().getServer().getTickCount();
        Charge before = CHARGES.get(target);
        int stacks = (before == null ? 0 : before.stacks()) + 1;
        if (stacks < DISCHARGE_AT) {
            CHARGES.put(target, new Charge(stacks, now, player));
            return;
        }
        CHARGES.remove(target);
        ServerLevel level = player.level();
        float amount = DISCHARGE_AT + damage * DISCHARGE_SHARE;
        boolean storm = storm(player);
        List<LivingEntity> chained = nearbyEnemies(level, target, player, storm ? CHAIN_TARGETS * 2 : CHAIN_TARGETS,
                storm ? STORM_RADIUS : RADIUS);
        showChain(target, chained);
        zap(level, player, target, amount);
        for (LivingEntity near : chained) zap(level, player, near, amount);
    }

    /**
     * Tells nearby clients to draw the chain lightning (ChainLightningRenderer): entity IDs in chain order, with their
     * hitbox centers as a fallback for entities the client cannot see or that die right away.
     */
    private static void showChain(LivingEntity first, List<LivingEntity> rest) {
        List<Integer> ids = new java.util.ArrayList<>();
        List<Float> points = new java.util.ArrayList<>();
        for (LivingEntity entity : java.util.stream.Stream.concat(java.util.stream.Stream.of(first), rest.stream()).toList()) {
            ids.add(entity.getId());
            points.add((float) entity.getX());
            points.add((float) (entity.getY() + entity.getBbHeight() * 0.5));
            points.add((float) entity.getZ());
        }
        ChainPayload payload = new ChainPayload(ids, points);
        java.util.Set<ServerPlayer> viewers = new java.util.HashSet<>(PlayerLookup.tracking(first));
        if (first instanceof ServerPlayer self) viewers.add(self);
        for (LivingEntity entity : rest) if (entity instanceof ServerPlayer player) viewers.add(player);
        for (ServerPlayer viewer : viewers)
            if (ServerPlayNetworking.canSend(viewer, ChainPayload.TYPE)) ServerPlayNetworking.send(viewer, payload);
    }

    /** One chain lightning: entities in order and their hitbox centers (x, y, z per entity) at the moment it struck. */
    public record ChainPayload(List<Integer> ids, List<Float> points) implements CustomPacketPayload {
        public static final Type<ChainPayload> TYPE = new Type<>(CrazyWorldProgression.id("chain_lightning"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChainPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(16)), ChainPayload::ids,
                ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(48)), ChainPayload::points, ChainPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Discharge damage, credited to the Stormcaller, without charging or chaining further. */
    private static void zap(ServerLevel level, ServerPlayer source, LivingEntity target, float amount) {
        if (!target.isAlive() || amount <= 0) return;
        discharging = true;
        try {
            target.invulnerableTime = 0;
            target.hurtServer(level, level.damageSources().indirectMagic(source, source), amount);
        } finally {
            discharging = false;
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + target.getBbHeight() * 0.5,
                target.getZ(), 12, 0.3, 0.5, 0.3, 0.15);
    }

    /** The closest enemies around {@code center}: hostile mobs and players that are not the source's party. */
    private static List<LivingEntity> nearbyEnemies(ServerLevel level, LivingEntity center, ServerPlayer source, int limit,
                                                    double radius) {
        return level.getEntitiesOfClass(LivingEntity.class, center.getBoundingBox().inflate(radius),
                        near -> near != center && near != source && near.isAlive() && enemy(source, near)
                                && near.distanceToSqr(center) <= radius * radius)
                .stream().sorted(Comparator.comparingDouble(near -> near.distanceToSqr(center))).limit(limit).toList();
    }

    /** The 6-piece version, judged by the Stormcaller who added the latest stack. */
    private static boolean storm(ServerPlayer player) {
        return !player.isRemoved() && SkillStatRegistry.getTotal(player, STORM) > 0;
    }

    private static boolean enemy(ServerPlayer source, LivingEntity entity) {
        if (entity instanceof ArmorStand) return false;
        if (entity instanceof ServerPlayer player)
            return !player.isSpectator() && !player.isCreative() && !AllyHealing.allies(source, player);
        if (entity instanceof OwnableEntity owned && source.equals(owned.getOwner())) return false;
        return entity instanceof Enemy;
    }

    /** Spark particles on charged entities (more per stack) and fading of old charges. */
    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0 || CHARGES.isEmpty()) return;
        long now = server.getTickCount();
        for (Iterator<Map.Entry<LivingEntity, Charge>> iterator = CHARGES.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<LivingEntity, Charge> entry = iterator.next();
            LivingEntity entity = entry.getKey();
            if (!entity.isAlive() || entity.isRemoved() || now - entry.getValue().lastTick() > FADE_TICKS) {
                iterator.remove();
                continue;
            }
            if (entity.level() instanceof ServerLevel level)
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5,
                        entity.getZ(), entry.getValue().stacks(), 0.3, 0.4, 0.3, 0.02);
        }
    }
}
