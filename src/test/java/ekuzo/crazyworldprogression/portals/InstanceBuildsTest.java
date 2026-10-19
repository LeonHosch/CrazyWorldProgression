package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InstanceBuildsTest {
    @Test void progressCountsUpButShows100OnlyWhenDone() {
        assertEquals(0, InstanceBuilds.percent(0, 1_000));
        assertEquals(50, InstanceBuilds.percent(500, 1_000));
        assertEquals(99, InstanceBuilds.percent(1_000, 1_000));
        assertEquals(100, InstanceBuilds.percent(0, 0));
    }

    @Test void buildsGoChunkByChunkKeepingLayerOrderInsideEachChunk() {
        // Template order is layer by layer: x = 0, 20, 0, 20 on layers 0, 0, 1, 1 (chunks 0 and 1).
        int[] x = {0, 20, 0, 20, -5};
        int[] order = InstanceBuilds.chunkOrder(x.length, index -> x[index] >> 4, index -> 0);
        assertArrayEquals(new int[] {4, 0, 2, 1, 3}, order);
        assertArrayEquals(new int[0], InstanceBuilds.chunkOrder(0, index -> 0, index -> 0));
    }
}
