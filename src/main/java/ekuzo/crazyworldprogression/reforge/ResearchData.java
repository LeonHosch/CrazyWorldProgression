package ekuzo.crazyworldprogression.reforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Research belongs to the online player's persisted entity data. */
final class ResearchData {
    static final AttachmentType<Player> STORAGE = AttachmentRegistry.create(
            CrazyWorldProgression.id("enchant_research"),
            builder -> builder.persistent(Player.CODEC).copyOnDeath().initializer(() -> Player.EMPTY));

    private ResearchData() { }

    static void initialize() { /* Force attachment registration before player data is decoded. */ }

    static Player player(ServerPlayer player) { return player.getAttachedOrCreate(STORAGE); }

    static void put(ServerPlayer player, Player research) {
        if (!research.equals(player(player))) player.setAttached(STORAGE, research);
    }

    /** points: enchantment ID to research points; focus: the chosen enchantment ID or empty. */
    record Player(Map<String, Integer> points, String focus) {
        static final Player EMPTY = new Player(Map.of(), "");
        static final Codec<Player> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("points", Map.of()).forGetter(Player::points),
                Codec.STRING.optionalFieldOf("focus", "").forGetter(Player::focus)
        ).apply(instance, Player::new));

        Player { points = Map.copyOf(points); }
    }
}
