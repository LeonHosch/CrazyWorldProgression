package ekuzo.crazyworldprogression.client;

/** The chest quality carried on a chest's render state (-1: none, Normal and older chests look vanilla). */
public interface ChestQualityStateHolder {
    int cwp$quality();

    void cwp$setQuality(int quality);
}
