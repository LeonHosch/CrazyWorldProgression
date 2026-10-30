package ekuzo.crazyworldprogression.collectibles;

/** A collectible drawn as a vanilla mob (mini pets, vanilla-look mounts); the client renders it with MiniPetRenderer. */
public interface MobLook {
    MiniPet.Look look();
}
