package ekuzo.crazyworldprogression.portals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceEntityRulesTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void entityPlacingItemsAreBlockedButOrdinaryItemsAreNot() {
        for (var item : java.util.List.of(Items.OAK_BOAT, Items.OAK_CHEST_BOAT, Items.BAMBOO_RAFT, Items.MINECART,
                Items.CHEST_MINECART, Items.ARMOR_STAND, Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME, Items.PAINTING,
                Items.END_CRYSTAL, Items.LEAD))
            assertTrue(PortalBuildProtection.placesEntity(item), item.toString());
        for (var item : java.util.List.of(Items.DIAMOND_SWORD, Items.STONE, Items.BOW, Items.TORCH))
            assertFalse(PortalBuildProtection.placesEntity(item), item.toString());
    }

    @Test void onlyEntitiesFromAnotherLeaseAreStale() {
        assertFalse(InstanceEntityLeases.stale(null, "cwp.lease.2"));
        assertFalse(InstanceEntityLeases.stale(null, null));
        assertFalse(InstanceEntityLeases.stale("cwp.lease.2", "cwp.lease.2"));
        assertTrue(InstanceEntityLeases.stale("cwp.lease.1", "cwp.lease.2"));
        assertTrue(InstanceEntityLeases.stale("cwp.lease.1", null));
    }
}
