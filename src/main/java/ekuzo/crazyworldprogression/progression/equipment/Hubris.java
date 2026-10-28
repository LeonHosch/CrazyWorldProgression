package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code unlockHubris} stat, the Hubris passive: every {@link #STACK_TICKS} without taking damage adds an Ascension
 * stack (up to
 * {@link #MAX_STACKS}); each stack adds 10 to {@code addAttackDamage} and to {@code addDamageTaken}, so it sums
 * with skill trees and sets. Taking damage, a hit from a party member who heals allies (see AllyHealing), or
 * {@link #IDLE_TICKS} without hitting anything resets the stacks (none build up again until the next hit).
 * Taking a set piece off while stacked turns every Ascension stack into a Downfall stack: the damage taken part only,
 * gone after {@link #DOWNFALL_TICKS} or with the next damage taken.
 */
public final class Hubris {
    public static final String STAT = "unlockHubris";
    public static final int STACK_TICKS = 100;
    public static final int IDLE_TICKS = 400;
    public static final int MAX_STACKS = 10;
    public static final int PER_STACK_PERCENT = 10;
    public static final float OVERHEAL_CAP = 20.0F;
    /** How long overheal golden hearts last (from the latest overheal). */
    public static final int OVERHEAL_TICKS = 200;
    private static final Identifier OVERHEAL = CrazyWorldProgression.id("hubris_overheal");
    /** Overheal golden hearts still granted, and the tick they expire. */
    private static final Map<UUID, Overheal> OVERHEALS = new ConcurrentHashMap<>();
    /** Server tick of the last reset (damage taken, ally hit, idle, or the bonus just switched on). */
    private static final Map<UUID, Long> SINCE = new ConcurrentHashMap<>();
    /** Server tick of the player's last hit on any entity. */
    private static final Map<UUID, Long> LAST_HIT = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Payload> SENT = new ConcurrentHashMap<>();
    public static final int DOWNFALL_TICKS = 400;
    /** Downfall stacks and the tick they fade. */
    private static final Map<UUID, Downfall> DOWNFALL = new ConcurrentHashMap<>();

    private Hubris() { }

    public static void initialize() {
        SkillStatRegistry.registerUnlockStat(STAT, "stat.crazy-world-progression.unlock_hubris");
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DownfallPayload.TYPE, DownfallPayload.CODEC);
        SkillStatRegistry.registerDynamicBonus("addAttackDamage", player -> stacks(player) * PER_STACK_PERCENT);
        SkillStatRegistry.registerDynamicBonus(SkillStatRegistry.DAMAGE_TAKEN,
                player -> (stacks(player) + downfall(player)) * PER_STACK_PERCENT);
        ServerTickEvents.END_SERVER_TICK.register(Hubris::tick);
        // Any hit on any entity counts: melee (armor stands, boats, ... included), projectiles (ProjectileHitMixin),
        // and other damage the player causes.
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (player instanceof ServerPlayer attacker) markHit(attacker);
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (source.getEntity() instanceof ServerPlayer attacker) markHit(attacker);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            clearDownfall(newPlayer);
            reset(newPlayer);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SINCE.remove(handler.player.getUUID());
            LAST_HIT.remove(handler.player.getUUID());
            STACKS.remove(handler.player.getUUID());
            OVERHEALS.remove(handler.player.getUUID());
            SENT.remove(handler.player.getUUID());
            DOWNFALL.remove(handler.player.getUUID());
        });
    }

    /** The player hit an entity: Ascension stays (and keeps building) for another {@link #IDLE_TICKS}. */
    public static void markHit(ServerPlayer player) {
        long now = player.level().getServer().getTickCount();
        LAST_HIT.put(player.getUUID(), now);
        // Send right away, so the display appears with the first hit of a streak.
        sync(player, now);
    }

    public static int stacks(ServerPlayer player) { return STACKS.getOrDefault(player.getUUID(), 0); }

    public static int downfall(ServerPlayer player) {
        Downfall downfall = DOWNFALL.get(player.getUUID());
        return downfall == null ? 0 : downfall.stacks();
    }

    /** The player took damage (after it was applied): Ascension resets and Downfall fades. */
    public static void onDamaged(ServerPlayer player) {
        clearDownfall(player);
        reset(player);
    }

    private static void clearDownfall(ServerPlayer player) {
        if (DOWNFALL.remove(player.getUUID()) == null) return;
        SkillStatRegistry.refresh(player);
        sendDownfall(player, 0, -1);
    }

    private static void sendDownfall(ServerPlayer player, int stacks, int ticks) {
        if (ServerPlayNetworking.canSend(player, DownfallPayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new DownfallPayload(stacks, ticks, DOWNFALL_TICKS));
    }

    private record Downfall(int stacks, long fades) { }

    /**
     * Martyr's Remorse at full stacks: heal above max health becomes absorption (golden hearts), up to
     * {@link #OVERHEAL_CAP}. They last {@link #OVERHEAL_TICKS} after the latest overheal, or until used up.
     */
    public static void overheal(ServerPlayer player, float amount) {
        AttributeInstance max = player.getAttribute(Attributes.MAX_ABSORPTION);
        if (max == null) return;
        if (!max.hasModifier(OVERHEAL)) max.addTransientModifier(new AttributeModifier(OVERHEAL, OVERHEAL_CAP,
                AttributeModifier.Operation.ADD_VALUE));
        float before = player.getAbsorptionAmount();
        player.setAbsorptionAmount(Math.min(before + amount, (float) max.getValue()));
        Overheal previous = OVERHEALS.get(player.getUUID());
        float granted = (previous == null ? 0 : previous.amount()) + player.getAbsorptionAmount() - before;
        OVERHEALS.put(player.getUUID(), new Overheal(Math.min(granted, OVERHEAL_CAP),
                player.level().getServer().getTickCount() + OVERHEAL_TICKS));
    }

    /** Overheal hearts are gone (used up or expired): the expired rest is removed, then the extra room. */
    private static void tickOverheal(ServerPlayer player, long now) {
        AttributeInstance max = player.getAttribute(Attributes.MAX_ABSORPTION);
        if (max == null || !max.hasModifier(OVERHEAL)) {
            OVERHEALS.remove(player.getUUID());
            return;
        }
        Overheal overheal = OVERHEALS.get(player.getUUID());
        boolean expired = overheal == null || now >= overheal.expires();
        if (!expired && player.getAbsorptionAmount() > 0) return;
        if (overheal != null) player.setAbsorptionAmount(Math.max(0, player.getAbsorptionAmount() - overheal.amount()));
        OVERHEALS.remove(player.getUUID());
        max.removeModifier(OVERHEAL);
        player.setAbsorptionAmount(Math.min(player.getAbsorptionAmount(), (float) max.getValue()));
    }

    private record Overheal(float amount, long expires) { }

    /** Took damage, or was hit by a healing ally: the Ascension stacks are gone and the count starts again. */
    public static void reset(ServerPlayer player) {
        SINCE.put(player.getUUID(), (long) player.level().getServer().getTickCount());
        if (stacks(player) > 0) set(player, 0);
        sync(player, player.level().getServer().getTickCount());
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            tickOverheal(player, now);
            particles(player);
            Downfall downfall = DOWNFALL.get(id);
            if (downfall != null && (now >= downfall.fades() || !player.isAlive())) clearDownfall(player);
            if (!player.isAlive() || SkillStatRegistry.getTotal(player, STAT) <= 0) {
                SINCE.remove(id);
                LAST_HIT.remove(id);
                int stacks = stacks(player);
                if (stacks > 0) {
                    // A set piece came off with Ascension up: the stacks turn into Downfall (no dropping them free).
                    if (player.isAlive()) {
                        DOWNFALL.put(id, new Downfall(Math.max(stacks, downfall(player)), now + DOWNFALL_TICKS));
                        sendDownfall(player, downfall(player), DOWNFALL_TICKS);
                    }
                    set(player, 0);
                }
                sync(player, now);
                continue;
            }
            // Out of combat (no hit for IDLE_TICKS): no stacks, and none build up until the next hit.
            if (!inCombat(player, now)) {
                if (stacks(player) > 0) reset(player);
                else SINCE.put(id, now);
                sync(player, now);
                continue;
            }
            long since = SINCE.computeIfAbsent(id, ignored -> now);
            int stacks = (int) Math.min(MAX_STACKS, (now - since) / STACK_TICKS);
            // The gain sound plays on the player's own client only (AscensionHud).
            if (stacks != stacks(player)) set(player, stacks);
            sync(player, now);
        }
    }

    /**
     * What other players see: small yellow flames around someone with Ascension, more with each stack, and dark
     * smoke for Downfall. One stack is one particle per half second, so even 10 stacks stay light.
     */
    private static void particles(ServerPlayer player) {
        if (player.isSpectator() || player.isInvisible()) return;
        ServerLevel level = player.level();
        int ascension = stacks(player), fall = downfall(player);
        double x = player.getX(), y = player.getY() + player.getBbHeight() * 0.5, z = player.getZ();
        if (ascension > 0) {
            level.sendParticles(ParticleTypes.SMALL_FLAME, x, y, z, ascension, 0.35, 0.55, 0.35, 0.01);
            if (ascension >= MAX_STACKS) level.sendParticles(ParticleTypes.WAX_ON, x, y + 0.6, z, 2, 0.4, 0.3, 0.4, 0.0);
        }
        if (fall > 0) {
            level.sendParticles(ParticleTypes.SMOKE, x, y, z, fall, 0.35, 0.55, 0.35, 0.01);
            level.sendParticles(ParticleTypes.ASH, x, y + 0.4, z, Math.max(1, fall / 2), 0.4, 0.4, 0.4, 0.0);
        }
    }

    private static boolean inCombat(ServerPlayer player, long now) {
        return now - LAST_HIT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) <= IDLE_TICKS;
    }

    /** Stores the stacks and re-applies the player's stats, so the stat totals pick them up. */
    private static void set(ServerPlayer player, int stacks) {
        if (stacks == 0) STACKS.remove(player.getUUID());
        else STACKS.put(player.getUUID(), stacks);
        SkillStatRegistry.refresh(player);
    }

    /**
     * Sends the Ascension display when it changed: whether Hubris is active, the stacks, ticks to the next stack and
     * ticks until the out-of-combat reset (-1 when not running).
     */
    private static void sync(ServerPlayer player, long now) {
        boolean active = player.isAlive() && SkillStatRegistry.getTotal(player, STAT) > 0;
        int stacks = stacks(player);
        Long since = SINCE.get(player.getUUID());
        boolean combat = active && inCombat(player, now);
        int next = combat && since != null && stacks < MAX_STACKS ? (int) (STACK_TICKS - (now - since) % STACK_TICKS) : -1;
        int idle = combat ? (int) (IDLE_TICKS - (now - LAST_HIT.get(player.getUUID()))) : -1;
        Payload payload = new Payload(active, stacks, next, STACK_TICKS, idle, IDLE_TICKS);
        Payload previous = SENT.put(player.getUUID(), payload);
        // Both countdowns drop by 10 per update; only resend when one jumps, starts or stops (the client counts).
        if (previous != null && previous.active() == active && previous.stacks() == stacks
                && counted(previous.next(), next) && counted(previous.idle(), idle)) return;
        if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ekuzo.crazyworldprogression.CwpPayloads.send(player, payload);
    }

    private static boolean counted(int before, int after) {
        return before < 0 ? after < 0 : after >= 0 && before - 10 == after;
    }

    /** Downfall display: stacks (0 hides it), ticks until it fades, and the full fade time. */
    public record DownfallPayload(int stacks, int ticks, int total) implements CustomPacketPayload {
        public static final Type<DownfallPayload> TYPE = new Type<>(CrazyWorldProgression.id("downfall"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownfallPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DownfallPayload::stacks, ByteBufCodecs.VAR_INT, DownfallPayload::ticks,
                ByteBufCodecs.VAR_INT, DownfallPayload::total, DownfallPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Ascension display: whether Hubris is active, current stacks, ticks until the next stack and until the
     * out-of-combat reset (-1: not running), and the full length of both.
     */
    public record Payload(boolean active, int stacks, int next, int perStack, int idle, int idleTotal)
            implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("ascension"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Payload::active, ByteBufCodecs.VAR_INT, Payload::stacks,
                ByteBufCodecs.VAR_INT, Payload::next, ByteBufCodecs.VAR_INT, Payload::perStack,
                ByteBufCodecs.VAR_INT, Payload::idle, ByteBufCodecs.VAR_INT, Payload::idleTotal, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
