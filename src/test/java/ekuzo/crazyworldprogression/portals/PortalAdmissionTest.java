package ekuzo.crazyworldprogression.portals;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalAdmissionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static PortalDefinition definition() {
        return new PortalDefinition(Identifier.parse("test:portal"), Identifier.parse("minecraft:overworld"),
                PortalDefinition.Shape.RECTANGLE, PortalDefinition.Plane.XY, 0, 64, 0, 4, 6,
                Identifier.parse("test:template"), 120, 0xAA55FF, "Test", "", 2,
                PortalDefinition.EntryPolicy.REPEATABLE, PortalDefinition.Schedule.always())
                .withAdmission(new PortalDefinition.Admission(PortalDefinition.Mode.PRIVATE, 3));
    }

    @Test void privateSettingsRoundTripAndSurviveEdits() {
        PortalDefinition portal = definition();
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, portal).getOrThrow().getAsJsonObject();
        assertEquals(portal, PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(portal.admission(), portal.withColor(0).withLifetime(300).withTemplate(
                Identifier.parse("test:other")).admission());
        assertThrows(IllegalArgumentException.class,
                () -> new PortalDefinition.Admission(PortalDefinition.Mode.PRIVATE, 0));
    }

    @Test void oldPortalAndLeaseDataRemainReadable() {
        JsonObject portal = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition())
                .getOrThrow().getAsJsonObject();
        portal.remove("access_mode");
        portal.remove("max_group_size");
        assertEquals(PortalDefinition.Mode.PUBLIC,
                PortalDefinition.CODEC.parse(JsonOps.INSTANCE, portal).getOrThrow().admission().mode());

        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("test:portal"),
                Identifier.parse("test:template"), Identifier.parse("cwp:slot"),
                1, 1000, 0, 64, 0, 10, 10, 10, List.of("00000000-0000-0000-0000-000000000001"));
        JsonObject lease = PortalData.StoredInstance.CODEC.encodeStart(JsonOps.INSTANCE, instance)
                .getOrThrow().getAsJsonObject();
        assertEquals(instance.admittedPlayers(), PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, lease)
                .getOrThrow().admittedPlayers());
        lease.remove("admitted_players");
        assertTrue(PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, lease)
                .getOrThrow().admittedPlayers().isEmpty());

        JsonObject oldSave = new JsonObject();
        JsonObject leases = new JsonObject();
        leases.add("test:portal", lease);
        oldSave.add("instances", leases);
        PortalData migrated = PortalData.CODEC.parse(JsonOps.INSTANCE, oldSave).getOrThrow();
        assertTrue(migrated.instances().containsKey("cwp:slot"));
    }

    @Test void twoGroupsCanLeaseDifferentSlotsForOnePortal() {
        PortalData data = new PortalData();
        PortalData.StoredInstance first = new PortalData.StoredInstance(Identifier.parse("test:portal"),
                Identifier.parse("test:template"), Identifier.parse("cwp:slot_one"),
                1, 1000, 0, 64, 0, 10, 10, 10, List.of("first"));
        PortalData.StoredInstance second = new PortalData.StoredInstance(first.portalId(), first.templateId(),
                Identifier.parse("cwp:slot_two"), 2, 1000, 0, 64, 0, 10, 10, 10, List.of("second"));
        data.putInstance(first);
        data.putInstance(second);
        assertEquals(2, data.instances().size());
        data.removeInstance(first.slotDimension());
        assertEquals(second, data.instances().get(second.slotDimension().toString()));
    }

    @Test void groupMembersMustBeNearFinitePortalSurface() {
        PortalDefinition portal = definition();
        assertTrue(portal.isNear(new Vec3(22, 64, 0), 20));
        assertFalse(portal.isNear(new Vec3(22.1, 64, 0), 20));
        assertFalse(portal.isNear(new Vec3(17, 81, 0), 20));
        assertTrue(portal.isNear(new Vec3(0, 64, 19.9), 20));
        assertFalse(portal.isNear(new Vec3(0, 64, 20.1), 20));
    }
}
