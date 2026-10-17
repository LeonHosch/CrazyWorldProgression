package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidSnapshotsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var item : List.of(Items.DIAMOND, Items.STONE, Items.GOLD_INGOT, Items.DIAMOND_SWORD)) {
            var holder = BuiltInRegistries.ITEM.get(BuiltInRegistries.ITEM.getKey(item)).orElseThrow();
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, item == Items.DIAMOND_SWORD ? 1 : 64).build());
        }
    }

    private static List<ItemStack> slots(int size) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    @Test void restoringPutsItemsBackInTheirSlotsAndRehousesWhatWasThere() {
        List<ItemStack> snapshot = slots(41);
        snapshot.set(0, new ItemStack(Items.DIAMOND_SWORD));
        snapshot.set(5, new ItemStack(Items.DIAMOND, 12));
        List<ItemStack> current = slots(41);
        current.set(0, new ItemStack(Items.STONE, 30));
        current.set(1, new ItemStack(Items.GOLD_INGOT, 3));
        List<ItemStack> displaced = RaidSnapshots.restore(current, snapshot);
        assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND_SWORD), current.get(0)));
        assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND, 12), current.get(5)));
        assertEquals(1, displaced.size());
        assertTrue(RaidSnapshots.fillEmpty(current, displaced).isEmpty());
        assertTrue(current.stream().anyMatch(stack -> ItemStack.matches(stack, new ItemStack(Items.STONE, 30))),
                "the stone that was in the way is still in the inventory");
        assertTrue(ItemStack.matches(new ItemStack(Items.GOLD_INGOT, 3), current.get(1)));
    }

    @Test void whatFitsNowhereIsReturnedNotDeleted() {
        List<ItemStack> full = slots(41);
        for (int i = 0; i < 36; i++) full.set(i, new ItemStack(Items.STONE, 64));
        List<ItemStack> leftover = RaidSnapshots.fillEmpty(full, List.of(new ItemStack(Items.DIAMOND, 5)));
        assertEquals(1, leftover.size());
        assertEquals(5, leftover.getFirst().getCount());
    }

    @Test void storedInventoriesSurviveSaving() {
        List<ItemStack> items = slots(41);
        items.set(3, new ItemStack(Items.DIAMOND, 7));
        var snapshot = new RaidSnapshots.Snapshot("crazy-world-progression:portal_instance_1", 1.5, 64, -2.5, items);
        var encoded = RaidSnapshots.SNAPSHOT_CODEC.encodeStart(JsonOps.INSTANCE, snapshot).getOrThrow();
        var restored = RaidSnapshots.SNAPSHOT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(snapshot.slot(), restored.slot());
        assertTrue(ItemStack.matches(items.get(3), restored.items().get(3)));
        assertEquals(41, restored.items().size());
    }

    private static final String PLAYER = "00000000-0000-0000-0000-000000000042";
    private static final String SLOT = "crazy-world-progression:portal_instance_1";
    private static final RaidData.Spot MARKER = new RaidData.Spot(4.5, 64, 2.5);

    private static RaidSnapshots.Logout logout(long instanceCreated) {
        List<ItemStack> items = slots(41);
        items.set(0, new ItemStack(Items.DIAMOND_SWORD));
        return new RaidSnapshots.Logout(SLOT, instanceCreated, "Leon", items);
    }

    @Test void loggingOutThenWipingPutsTheInventoryInAnOrbAndClearsItOnLogin() {
        RaidSnapshots data = new RaidSnapshots();
        data.recordLogout(PLAYER, logout(10L));
        var orb = data.wipeOffline(PLAYER, SLOT, 10L, MARKER);
        assertTrue(orb != null && ItemStack.matches(new ItemStack(Items.DIAMOND_SWORD), orb.items().getFirst()));
        assertEquals(RaidSnapshots.Return.CLEAR_ORB_WAITING, data.returning(PLAYER));
        // Logging in again later changes nothing: the items now exist only in the orb.
        assertEquals(RaidSnapshots.Return.NOTHING, data.returning(PLAYER));
    }

    @Test void comingBackBeforeAWipeDiscardsTheLogoutSnapshot() {
        RaidSnapshots data = new RaidSnapshots();
        data.recordLogout(PLAYER, logout(10L));
        assertEquals(RaidSnapshots.Return.NOTHING, data.returning(PLAYER));
        assertEquals(null, data.wipeOffline(PLAYER, SLOT, 10L, MARKER), "no orb from an outdated snapshot");
        assertEquals(RaidSnapshots.Return.NOTHING, data.returning(PLAYER));
    }

    @Test void aSecondWipeWhileOfflineDestroysTheOrbAndTheInventoryIsStillCleared() {
        RaidSnapshots data = new RaidSnapshots();
        data.recordLogout(PLAYER, logout(10L));
        data.wipeOffline(PLAYER, SLOT, 10L, MARKER);
        assertEquals(null, data.wipeOffline(PLAYER, SLOT, 10L, MARKER));
        assertEquals(RaidSnapshots.Return.CLEAR_ORB_LOST, data.returning(PLAYER));
    }

    @Test void aSnapshotFromAnEarlierInstanceInTheSameSlotIsIgnored() {
        RaidSnapshots data = new RaidSnapshots();
        data.recordLogout(PLAYER, logout(10L));
        assertEquals(null, data.wipeOffline(PLAYER, SLOT, 99L, MARKER));
    }
}
