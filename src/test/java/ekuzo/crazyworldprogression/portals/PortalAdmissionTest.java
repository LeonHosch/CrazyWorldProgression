package ekuzo.crazyworldprogression.portals;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

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

    @Test void raidModeAndAttemptEntryRoundTrip() {
        PortalDefinition raid = definition().withRaidMode(true)
                .withEntryPolicy(PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT);
        JsonObject encoded = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, raid).getOrThrow().getAsJsonObject();
        assertEquals(raid, PortalDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertTrue(raid.withAllowBuilding(false).raidMode());
        assertThrows(IllegalArgumentException.class, () -> raid.withAdmission(
                new PortalDefinition.Admission(PortalDefinition.Mode.PUBLIC, 2)
                        .withInstanceMode(PortalDefinition.InstanceMode.RAID)));
    }

    @Test void instanceModesRoundTripAndOldSavesKeepTheirBehaviour() {
        for (PortalDefinition.InstanceMode mode : PortalDefinition.InstanceMode.values()) {
            PortalDefinition portal = definition().withInstanceMode(mode);
            JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, portal).getOrThrow().getAsJsonObject();
            assertEquals(mode, PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().instanceMode());
        }
        JsonObject old = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition()).getOrThrow().getAsJsonObject();
        old.remove("instance_mode");
        old.addProperty("raid_mode", false);
        assertEquals(PortalDefinition.InstanceMode.DUNGEON,
                PortalDefinition.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().instanceMode(), "old private portal");
        old.addProperty("raid_mode", true);
        assertEquals(PortalDefinition.InstanceMode.RAID,
                PortalDefinition.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().instanceMode(), "old raid portal");
        assertEquals(PortalDefinition.InstanceMode.NO_REVIVE, definition().withRaidMode(true)
                .withAdmission(definition().admission().withAccess(PortalDefinition.Mode.PUBLIC, 3)).instanceMode(),
                "going public drops Dungeon and Raid");
        assertEquals(PortalDefinition.InstanceMode.PLAYER_REVIVE, PortalDefinition.InstanceMode.parse("Player Revive"));
    }

    @Test void privateSettingsRoundTripAndSurviveEdits() {
        PortalDefinition portal = definition().withAllowBuilding(false)
                .withTemplate(PortalDefinition.EXIT_TEMPLATE)
                .withEnvironment(new PortalDefinition.Environment(PortalDefinition.Daytime.MIDNIGHT,
                        false, PortalDefinition.Weather.THUNDER, false));
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, portal).getOrThrow().getAsJsonObject();
        assertEquals(portal, PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(portal.admission(), portal.withColor(0).withLifetime(300).withTemplate(
                Identifier.parse("test:other")).admission());
        assertThrows(IllegalArgumentException.class,
                () -> new PortalDefinition.Admission(PortalDefinition.Mode.PRIVATE, 0));
    }

    @Test void keepInventoryDefaultsOnAndSurvivesPortalEdits() {
        PortalDefinition unsafe = definition().withKeepInventory(false);
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, unsafe).getOrThrow().getAsJsonObject();
        assertFalse(PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().keepInventory());
        assertFalse(unsafe.withColor(0x123456).withRaidMode(true).keepInventory());
        json.remove("keep_inventory");
        assertTrue(PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().keepInventory());
    }

    @Test void publicPlayerVisibilityDefaultsOnAndSurvivesPortalEdits() {
        PortalDefinition portal = definition().withAdmission(PortalDefinition.Admission.publicPortal())
                .withShowOtherPlayers(false);
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, portal).getOrThrow().getAsJsonObject();
        assertFalse(PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().showOtherPlayers());
        assertFalse(portal.withColor(0x123456).withKeepInventory(false).showOtherPlayers());
        json.remove("show_other_players");
        assertTrue(PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().showOtherPlayers());
    }

    @Test void oldPortalAndLeaseDataRemainReadable() {
        JsonObject portal = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition())
                .getOrThrow().getAsJsonObject();
        portal.remove("access_mode");
        portal.remove("max_group_size");
        portal.remove("allow_building");
        portal.remove("destination_mode");
        PortalDefinition migratedPortal = PortalDefinition.CODEC.parse(JsonOps.INSTANCE, portal).getOrThrow();
        assertEquals(PortalDefinition.Mode.PUBLIC, migratedPortal.admission().mode());
        assertTrue(migratedPortal.allowBuilding());
        assertTrue(migratedPortal.baseActive());
        assertFalse(migratedPortal.isExit());
        assertEquals(PortalDefinition.Environment.defaults(), migratedPortal.environment());

        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("test:portal"),
                Identifier.parse("test:template"), Identifier.parse("cwp:slot"),
                1, 1000, 0, 64, 0, 10, 10, 10, List.of("00000000-0000-0000-0000-000000000001"));
        JsonObject lease = PortalData.StoredInstance.CODEC.encodeStart(JsonOps.INSTANCE, instance)
                .getOrThrow().getAsJsonObject();
        assertEquals(instance.admittedPlayers(), PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, lease)
                .getOrThrow().admittedPlayers());
        lease.remove("admitted_players");
        lease.remove("visitors");
        assertTrue(PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, lease)
                .getOrThrow().admittedPlayers().isEmpty());

        JsonObject oldSave = new JsonObject();
        JsonObject leases = new JsonObject();
        leases.add("test:portal", lease);
        oldSave.add("instances", leases);
        PortalData migrated = PortalData.CODEC.parse(JsonOps.INSTANCE, oldSave).getOrThrow();
        assertTrue(migrated.instances().containsKey("cwp:slot"));
    }

    @Test void portalLocationsRoundTripAndLegacyPlacementMigrates() {
        PortalDefinition portal = definition();
        PortalData data = new PortalData();
        data.putPortal(portal);
        data.putLocation(portal.id(), "north", new PortalData.PortalLocation(
                Identifier.parse("minecraft:overworld"), 12, 70, 24));
        data.putLocation(portal.id(), "south", new PortalData.PortalLocation(
                Identifier.parse("minecraft:the_nether"), -8, 80, 3));
        JsonObject saved = PortalData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        PortalData restored = PortalData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(2, restored.locations(portal.id()).size());
        assertEquals(portal, restored.portals().get(portal.id().toString()));
        restored.removeLocation(portal.id(), "north");
        assertEquals(1, restored.locations(portal.id()).size());

        saved.remove("locations");
        PortalData legacy = PortalData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(new PortalData.PortalLocation(portal.dimension(), portal.centerX(),
                portal.centerY(), portal.centerZ()), legacy.locations(portal.id()).get("original"));
    }

    @Test void legacyDestinationBecomesExitLink() {
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition())
                .getOrThrow().getAsJsonObject();
        json.addProperty("destination_mode", "return");
        PortalDefinition migrated = PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertTrue(migrated.isExit());
        assertEquals(PortalDefinition.EXIT_TEMPLATE, migrated.templateId());
        assertEquals(PortalDefinition.Destination.POCKET, migrated.admission().destination());
    }

    @Test void onlyPublicPortalsCanHaveNoExpiry() {
        assertThrows(IllegalArgumentException.class, () -> definition().withLifetime(0));
        PortalDefinition publicPortal = definition().withAdmission(
                definition().admission().withAccess(PortalDefinition.Mode.PUBLIC, 3)).withLifetime(0);
        assertEquals(0, publicPortal.lifetimeSeconds());
        assertThrows(IllegalArgumentException.class, () -> publicPortal.withAdmission(
                publicPortal.admission().withAccess(PortalDefinition.Mode.PRIVATE, 3)));
    }

    @Test void removingBothTimeSettingsAllowsEntryAnyTime() {
        PortalDefinition.Schedule schedule = PortalDefinition.Schedule.always()
                .withCalendar(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(10, 0),
                        ZoneId.of("UTC"))
                .withPeriodic(3600, 900, Instant.parse("2026-01-05T09:00:00Z"));
        Instant outside = Instant.parse("2026-01-06T23:00:00Z");
        assertFalse(schedule.allows(outside));
        PortalDefinition.Schedule unrestricted = schedule.withoutCalendar().withoutPeriodic();
        assertTrue(unrestricted.allows(outside));
        JsonObject json = PortalDefinition.Schedule.CODEC.encodeStart(JsonOps.INSTANCE, unrestricted)
                .getOrThrow().getAsJsonObject();
        assertFalse(PortalDefinition.Schedule.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow().calendarEnabled());
    }

    @Test void instanceVisitorsPersistForReentry() {
        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("test:portal"),
                Identifier.parse("test:template"), Identifier.parse("cwp:slot"),
                1, 1000, 0, 64, 0, 10, 10, 10, List.of());
        java.util.UUID player = java.util.UUID.randomUUID();
        PortalData.StoredInstance visited = instance.withVisitor(player).withVisitor(player);
        assertEquals(List.of(player.toString()), visited.visitors());
        assertEquals(visited, PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE,
                PortalData.StoredInstance.CODEC.encodeStart(JsonOps.INSTANCE, visited).getOrThrow()).getOrThrow());
        assertEquals(visited.visitors(), visited.expiringAt(5).visitors());
    }

    @Test void editorExitProjectsIntoItsPocketInstance() {
        PortalDefinition editorPortal = definition().movedTo(Identifier.parse("cwp:pocket_editor_1"),
                new Vec3(15.5, 70, -5.5)).withTemplate(PortalDefinition.EXIT_TEMPLATE);
        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("test:source"),
                Identifier.parse("test:template"), Identifier.parse("cwp:instance_1"),
                1, 1000, 100, 64, 200, 10, 10, 10, List.of());
        PortalDefinition projected = PortalService.projectPortal(editorPortal, new BlockPos(10, 60, -10), instance);
        assertEquals(instance.slotDimension(), projected.dimension());
        assertEquals(105.5, projected.centerX());
        assertEquals(74, projected.centerY());
        assertEquals(204.5, projected.centerZ());
        assertTrue(projected.isExit());
    }

    @Test void coordinateLinkRoundTripsAndMovesWithPocketProjection() {
        PortalDefinition editorPortal = definition().movedTo(Identifier.parse("cwp:pocket_editor_1"),
                        new Vec3(15.5, 70, -5.5))
                .withTemplate(PortalDefinition.COORDINATES_TEMPLATE)
                .withCoordinateTarget(new PortalDefinition.CoordinateTarget(18.5, 71, -2.5));
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, editorPortal)
                .getOrThrow().getAsJsonObject();
        PortalDefinition restored = PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(editorPortal, restored);
        assertTrue(restored.isCoordinates());
        assertEquals(editorPortal.coordinateTarget(), editorPortal.movedTo(editorPortal.dimension(),
                new Vec3(25.5, 70, -5.5)).coordinateTarget());

        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("test:source"),
                Identifier.parse("test:template"), Identifier.parse("cwp:instance_1"),
                1, 1000, 100, 64, 200, 10, 10, 10, List.of());
        PortalDefinition projected = PortalService.projectPortal(restored, new BlockPos(10, 60, -10), instance);
        assertEquals(instance.slotDimension(), projected.dimension());
        assertEquals(new PortalDefinition.CoordinateTarget(108.5, 75, 207.5),
                projected.coordinateTarget().orElseThrow());
    }

    @Test void portalPresetCanHaveNoInitialPlacement() {
        PortalDefinition preset = definition().withBaseActive(false);
        JsonObject json = PortalDefinition.CODEC.encodeStart(JsonOps.INSTANCE, preset).getOrThrow().getAsJsonObject();
        assertEquals(preset, PortalDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertFalse(preset.baseActive());
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
