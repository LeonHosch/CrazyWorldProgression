package ekuzo.crazyworldprogression.titles;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;

/** The title a player chose; title definitions remain advancement resources. */
final class TitleData {
    static final AttachmentType<String> EQUIPPED = AttachmentRegistry.create(
            CrazyWorldProgression.id("equipped_title"),
            builder -> builder.persistent(Codec.STRING).copyOnDeath().initializer(() -> ""));

    private TitleData() { }

    static void initialize() { /* Force attachment registration before player data is decoded. */ }

    static String equipped(ServerPlayer player) { return player.getAttachedOrCreate(EQUIPPED); }

    static void equip(ServerPlayer player, String id) {
        if (!equipped(player).equals(id)) player.setAttached(EQUIPPED, id);
    }
}
