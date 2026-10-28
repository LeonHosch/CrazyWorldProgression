package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Life steal and the blood pact stats:
 * <ul>
 *   <li>{@code addLifeSteal}: heals the player by that percent of the damage they deal (after the target's armor).</li>
 *   <li>{@code unlockBloodPact}: every other heal hurts the player instead, and natural food healing is cut to a third
 *       (LivingEntityHealMixin, FoodDataHealMixin). Life steal itself heals normally.</li>
 *   <li>{@code unlockBloodLust}: Bloodletter's Lust, one stack below 30% health, two below 20%, three below 10%;
 *       each stack is +10% attack damage and +5% life steal.</li>
 * </ul>
 */
public final class BloodPact {
    public static final String PACT = "unlockBloodPact";
    public static final String LUST = "unlockBloodLust";
    public static final int LUST_DAMAGE = 10, LUST_LIFE_STEAL = 5;
    /** Share of natural food healing left under the pact (same saturation cost per tick). */
    public static final float FOOD_HEALING = 1.0F / 3.0F;
    private static final float[] LUST_THRESHOLDS = {0.30F, 0.20F, 0.10F};
    private static final ThreadLocal<Boolean> ALLOWED = ThreadLocal.withInitial(() -> false);
    private static final Map<UUID, Integer> LUST_STACKS = new ConcurrentHashMap<>();
    private static final DustParticleOptions BLOOD = new DustParticleOptions(0x8A0A12, 1.0F);

    private BloodPact() { }

    public static void initialize() {
        SkillStatRegistry.registerUnlockStat(PACT, "stat.crazy-world-progression.unlock_blood_pact");
        SkillStatRegistry.registerUnlockStat(LUST, "stat.crazy-world-progression.unlock_blood_lust");
        SkillStatRegistry.registerDynamicBonus("addAttackDamage", player -> lust(player) * LUST_DAMAGE);
        SkillStatRegistry.registerDynamicBonus(SkillStatRegistry.LIFE_STEAL, player -> lust(player) * LUST_LIFE_STEAL);
        PayloadTypeRegistry.clientboundPlay().register(LustPayload.TYPE, LustPayload.CODEC);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getEntity() instanceof ServerPlayer attacker) || attacker == entity || taken <= 0) return;
            double percent = SkillStatRegistry.getTotal(attacker, SkillStatRegistry.LIFE_STEAL);
            if (percent > 0 && attacker.isAlive()) healAllowed(attacker, (float) (taken * percent / 100.0));
        });
        ServerTickEvents.END_SERVER_TICK.register(BloodPact::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LUST_STACKS.remove(handler.player.getUUID()));
    }

    /** A heal that the blood pact lets through (life steal, halved food healing). */
    public static void healAllowed(ServerPlayer player, float amount) {
        ALLOWED.set(true);
        try { player.heal(amount); }
        finally { ALLOWED.set(false); }
    }

    /** For LivingEntityHealMixin: true when the heal must hurt the player instead. */
    public static boolean reversesHeal(ServerPlayer player) {
        return !ALLOWED.get() && SkillStatRegistry.getTotal(player, PACT) > 0;
    }

    /** Natural food healing (FoodDataHealMixin): cut to {@link #FOOD_HEALING} under the pact. */
    public static void foodHeal(ServerPlayer player, float amount) {
        if (SkillStatRegistry.getTotal(player, PACT) > 0) healAllowed(player, amount * FOOD_HEALING);
        else player.heal(amount);
    }

    public static int lust(ServerPlayer player) { return LUST_STACKS.getOrDefault(player.getUUID(), 0); }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;
        boolean particles = server.getTickCount() % 10 == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int stacks = 0;
            if (player.isAlive() && SkillStatRegistry.getTotal(player, LUST) > 0) {
                float health = player.getHealth() / player.getMaxHealth();
                for (float threshold : LUST_THRESHOLDS) if (health < threshold) stacks++;
            }
            if (stacks != lust(player)) {
                if (stacks == 0) LUST_STACKS.remove(player.getUUID());
                else LUST_STACKS.put(player.getUUID(), stacks);
                SkillStatRegistry.refresh(player);
                if (ServerPlayNetworking.canSend(player, LustPayload.TYPE))
                    ekuzo.crazyworldprogression.CwpPayloads.send(player, new LustPayload(stacks));
            }
            if (particles && stacks > 0 && !player.isInvisible() && !player.isSpectator())
                player.level().sendParticles(BLOOD, player.getX(), player.getY() + player.getBbHeight() * 0.5,
                        player.getZ(), stacks, 0.3, 0.5, 0.3, 0.0);
        }
    }

    /** Bloodletter's Lust display: current stacks (0 hides it). */
    public record LustPayload(int stacks) implements CustomPacketPayload {
        public static final Type<LustPayload> TYPE = new Type<>(CrazyWorldProgression.id("blood_lust"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LustPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LustPayload::stacks, LustPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
