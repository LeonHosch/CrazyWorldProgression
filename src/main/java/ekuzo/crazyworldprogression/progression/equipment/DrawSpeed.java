package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code addDrawSpeed} stat: bows draw and crossbows load that many percent faster (+20 means the same power in
 * 1/1.2 of the time). Both sides need it, since the client animates the pull, so the server sends each player their
 * own value whenever their stats refresh (BowItemDrawMixin, CrossbowItemDrawMixin, client UseDurationDrawMixin).
 */
public final class DrawSpeed {
    public static final String STAT = "addDrawSpeed";
    private static final Map<UUID, Float> SENT = new ConcurrentHashMap<>();
    /** The local player's draw speed in percent (client side; set from the server). */
    public static volatile float clientPercent;
    /** Client side: whether an entity is the local player (other players' draw speed is unknown there). */
    public static volatile java.util.function.Predicate<LivingEntity> localPlayer = entity -> false;

    private DrawSpeed() { }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        SkillStatRegistry.registerPercentStat(STAT, "stat.crazy-world-progression.draw_speed", (player, value) -> {
            float percent = (float) value;
            Float before = SENT.put(player.getUUID(), percent);
            if ((before == null || before != percent) && ServerPlayNetworking.canSend(player, Payload.TYPE))
                ServerPlayNetworking.send(player, new Payload(percent));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.player.getUUID()));
    }

    /** How many times faster the entity draws: 1 normally, 1.2 at +20%. */
    public static float multiplier(LivingEntity entity) {
        double percent;
        if (entity instanceof ServerPlayer player) percent = SkillStatRegistry.getTotal(player, STAT);
        else if (entity instanceof Player && entity.level().isClientSide() && localPlayer.test(entity)) percent = clientPercent;
        else return 1.0F;
        return (float) Math.max(0.1, 1.0 + percent / 100.0);
    }

    public record Payload(float percent) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("draw_speed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Payload::percent, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
