package ekuzo.crazyworldprogression.collectibles;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each player's selected pet beside them: spawns it when missing (after selecting, joining, respawning or
 * changing dimension), and removes it when they pick another, clear their pet, become a spectator or leave.
 */
public final class PetService {
    private static final Map<UUID, WeakReference<CwpPet>> PETS = new ConcurrentHashMap<>();

    private PetService() { }

    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) for (ServerPlayer player : server.getPlayerList().getPlayers()) refresh(player);
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> remove(handler.player.getUUID()));
    }

    /** The player's pet entity, or null. */
    public static CwpPet pet(ServerPlayer player) {
        WeakReference<CwpPet> reference = PETS.get(player.getUUID());
        CwpPet pet = reference == null ? null : reference.get();
        return pet == null || pet.isRemoved() ? null : pet;
    }

    static void refresh(ServerPlayer player) {
        Collectibles.PetDefinition definition = Collectibles.pet(Identifier.tryParse(
                String.valueOf(Collectibles.selected(player, Collectibles.PET))));
        CwpPet pet = pet(player);
        boolean wanted = definition != null && player.isAlive() && !player.isSpectator()
                && Collectibles.unlocked(player, Collectibles.PET, definition.id());
        if (pet != null && (!wanted || pet.getType() != definition.type() || pet.level() != player.level())) {
            pet.discard();
            pet = null;
        }
        if (!wanted || pet != null) {
            if (!wanted) PETS.remove(player.getUUID());
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        CwpPet created = definition.type().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (created == null) return;
        created.setOwner(player.getUUID());
        placeBeside(created, player);
        level.addFreshEntity(created);
        PETS.put(player.getUUID(), new WeakReference<>(created));
    }

    private static void remove(UUID player) {
        WeakReference<CwpPet> reference = PETS.remove(player);
        CwpPet pet = reference == null ? null : reference.get();
        if (pet != null) pet.discard();
    }

    /** Puts the pet a little behind the player. */
    static void placeBeside(CwpPet pet, ServerPlayer player) {
        Vec3 behind = Vec3.directionFromRotation(0, player.getYRot()).scale(-1.5);
        pet.snapTo(player.getX() + behind.x, player.getY(), player.getZ() + behind.z, player.getYRot(), 0);
        pet.getNavigation().stop();
    }
}
