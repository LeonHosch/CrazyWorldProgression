package ekuzo.crazyworldprogression.farming;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.skills.Proficiency;
import ekuzo.crazyworldprogression.skills.Skills;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Eggs and milk are collected by hand, with a cooldown per animal. Chickens never lay eggs on the ground
 * (ChickenEggMixin): a player clicks an adult chicken with an empty hand to take its egg, which cracks or gets a quality
 * like produce. Cows (and mooshrooms) can only be milked once per {@link #MILK_COOLDOWN}. Both give farming XP.
 */
public final class AnimalProduce {
    /** Ticks between eggs from one chicken, and between milkings of one cow (5 minutes each). */
    public static final long EGG_COOLDOWN = 6000;
    public static final long MILK_COOLDOWN = 6000;
    /** The game time when the animal can give produce again. */
    private static final AttachmentType<Long> READY_AT = AttachmentRegistry.create(
            CrazyWorldProgression.id("produce_ready_at"), builder -> builder.persistent(Codec.LONG));

    private AnimalProduce() { }

    public static void initialize() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level)
                    || hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (entity instanceof Chicken chicken && held.isEmpty() && !chicken.isBaby() && !chicken.isChickenJockey())
                return collectEgg(serverPlayer, level, chicken);
            if (entity instanceof AbstractCow cow && held.is(Items.BUCKET) && !cow.isBaby())
                return milk(serverPlayer, level, cow);
            return InteractionResult.PASS;
        });
    }

    private static InteractionResult collectEgg(ServerPlayer player, ServerLevel level, Chicken chicken) {
        if (waiting(player, level, chicken, "This chicken has no egg yet")) return InteractionResult.FAIL;
        chicken.setAttached(READY_AT, level.getGameTime() + EGG_COOLDOWN);
        chicken.dropFromGiftLootTable(level, BuiltInLootTables.CHICKEN_LAY, (ignored, stack) -> {
            ItemStack egg = ProduceQuality.collectEgg(player, stack);
            if (!egg.isEmpty() && !player.getInventory().add(egg)) player.drop(egg, false);
        });
        chicken.playSound(SoundEvents.CHICKEN_EGG, 1.0F, (chicken.getRandom().nextFloat() - chicken.getRandom().nextFloat()) * 0.2F + 1.0F);
        return InteractionResult.SUCCESS;
    }

    /** Lets vanilla fill the bucket when the cow is ready, starting its cooldown. */
    private static InteractionResult milk(ServerPlayer player, ServerLevel level, AbstractCow cow) {
        if (waiting(player, level, cow, "This cow needs time before it can be milked again")) {
            player.containerMenu.broadcastFullState();
            return InteractionResult.FAIL;
        }
        cow.setAttached(READY_AT, level.getGameTime() + MILK_COOLDOWN);
        // Milk has no quality: XP as Fine.
        Skills.add(player, Skills.Skill.FARMING, Skills.xpFor(Proficiency.NOVICE, Skills.FINE));
        return InteractionResult.PASS;
    }

    /** True (and tells the player how long) while the animal's cooldown runs. */
    private static boolean waiting(ServerPlayer player, ServerLevel level, Entity animal, String message) {
        long left = animal.getAttachedOrElse(READY_AT, 0L) - level.getGameTime();
        if (left <= 0) return false;
        long seconds = (left + 19) / 20;
        String time = seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
        player.sendOverlayMessage(Component.literal(message + " (" + time + ")").withStyle(ChatFormatting.YELLOW));
        return true;
    }
}
