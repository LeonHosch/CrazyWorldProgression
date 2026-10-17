package ekuzo.crazyworldprogression.portals;

import net.minecraft.core.BlockPos;

/**
 * Many light checks sent to the light thread as one task. Passed to LevelLightEngine.checkBlock like a single
 * position (one in the chunk the checks belong to); LevelLightEngineBatchMixin then checks every position.
 */
public final class LightBatch extends BlockPos {
    private final long[] positions;

    LightBatch(int chunkX, int chunkZ, long[] positions) {
        super(chunkX << 4, 0, chunkZ << 4);
        this.positions = positions;
    }

    public long[] positions() { return positions; }
}
