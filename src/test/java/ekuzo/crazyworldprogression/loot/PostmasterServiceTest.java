package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostmasterServiceTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var item : List.of(Items.DIAMOND, Items.STONE, Items.GOLD_INGOT, Items.DIAMOND_SWORD)) {
            var holder = BuiltInRegistries.ITEM.get(BuiltInRegistries.ITEM.getKey(item)).orElseThrow();
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.builder()
                    .set(DataComponents.MAX_STACK_SIZE, item == Items.DIAMOND_SWORD ? 1 : 64).build());
        }
    }

    @Test void twentySlotsMergeStacksAndReturnOverflow() {
        List<ItemStack> slots = new ArrayList<>();
        slots.add(new ItemStack(Items.DIAMOND, 60));
        for (int i = 1; i < 19; i++) slots.add(new ItemStack(Items.STONE, 64));
        slots.add(ItemStack.EMPTY);
        ItemStack original = new ItemStack(Items.DIAMOND, 10);
        ItemStack remainder = PostmasterService.insert(slots, original);
        assertTrue(remainder.isEmpty());
        assertEquals(10, original.getCount());
        assertEquals(64, slots.getFirst().getCount());
        assertEquals(6, slots.getLast().getCount());
        assertEquals(7, PostmasterService.insert(slots, new ItemStack(Items.GOLD_INGOT, 7)).getCount());
        assertEquals(20, slots.size());
    }

    @Test void namedItemsAndSlotPositionsSurviveSaveCodec() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000123");
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Example blade"));
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < PostmasterService.SLOTS; i++) slots.add(ItemStack.EMPTY);
        slots.set(18, named);
        PostmasterData data = new PostmasterData();
        data.save(player, slots);
        var encoded = PostmasterData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        PostmasterData restored = PostmasterData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(ItemStack.matches(named, restored.slots(player).get(18)));
        assertTrue(restored.slots(player).get(19).isEmpty());
    }

    @Test void inventoryRoomCountsOnlyWhatReallyFits() {
        List<ItemStack> inventory = new ArrayList<>();
        inventory.add(new ItemStack(Items.DIAMOND, 60));
        inventory.add(new ItemStack(Items.STONE, 64));
        inventory.add(new ItemStack(Items.DIAMOND_SWORD));
        for (int i = 3; i < 36; i++) inventory.add(new ItemStack(Items.STONE, 64));
        assertEquals(4, LootPoolService.roomFor(inventory, new ItemStack(Items.DIAMOND, 10)));
        assertEquals(0, LootPoolService.roomFor(inventory, new ItemStack(Items.GOLD_INGOT, 5)));
        assertEquals(0, LootPoolService.roomFor(inventory, new ItemStack(Items.DIAMOND_SWORD)));
        inventory.set(5, ItemStack.EMPTY);
        assertEquals(64, LootPoolService.roomFor(inventory, new ItemStack(Items.GOLD_INGOT, 5)));
        assertEquals(1, LootPoolService.roomFor(inventory, new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test void chatAnnouncementIsOffUnlessSaved() {
        var entry = new LootPoolData.Entry("item", net.minecraft.resources.Identifier.parse("minecraft:diamond"),
                10000, 1, 1, 1);
        assertEquals(false, entry.showInChat());
        var json = LootPoolData.Entry.CODEC.encodeStart(JsonOps.INSTANCE, entry).getOrThrow().getAsJsonObject();
        json.remove("show_in_chat");
        assertEquals(false, LootPoolData.Entry.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().showInChat());
    }
}
