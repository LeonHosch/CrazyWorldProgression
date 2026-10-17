package ekuzo.crazyworldprogression.buildings;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildingDataTest {
    @Test void savedBuildingMetadataSurvivesWorldSave() {
        BuildingData data = new BuildingData();
        Identifier id = Identifier.parse("cwp:arena_wall");
        data.put(new BuildingData.Building(id, 5, 3, 2, "builder"));
        Identifier slot = Identifier.parse("cwp:portal_instance_1");
        BuildingData.Placement placement = new BuildingData.Placement(id, 12345L, 5, 3, 2);
        data.addPlacement(slot, placement);
        BuildingData restored = BuildingData.CODEC.parse(JsonOps.INSTANCE,
                BuildingData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
        assertEquals(new BuildingData.Building(id, 5, 3, 2, "builder"),
                restored.values().get(id.toString()));
        assertEquals(java.util.List.of(placement), restored.placements(slot));
        restored.removePlacement(slot, placement.origin());
        assertTrue(restored.placements(slot).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                new BuildingData.Building(id, 1000, 1000, 1000, "builder"));
    }
}
