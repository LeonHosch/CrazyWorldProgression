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
        BuildingData.Placement placement = new BuildingData.Placement(id, 12345L, 5, 3, 2, false);
        BuildingData.Placement replaced = new BuildingData.Placement(id, 678L, 5, 3, 2, true);
        data.addPlacement(slot, placement);
        data.addPlacement(slot, replaced);
        BuildingData restored = BuildingData.CODEC.parse(JsonOps.INSTANCE,
                BuildingData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
        assertEquals(new BuildingData.Building(id, 5, 3, 2, "builder"),
                restored.values().get(id.toString()));
        assertEquals(java.util.List.of(placement, replaced), restored.placements(slot));
        restored.removePlacement(slot, placement.origin());
        restored.removePlacement(slot, replaced.origin());
        assertTrue(restored.placements(slot).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                new BuildingData.Building(id, 1000, 1000, 1000, "builder"));
    }

    @Test void placementsSavedBeforePlacementModesClearTheirWholeBox() {
        var json = com.google.gson.JsonParser.parseString(
                "{\"id\":\"cwp:wall\",\"origin\":1,\"size_x\":1,\"size_y\":1,\"size_z\":1}");
        assertTrue(BuildingData.Placement.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().replace());
    }
}
