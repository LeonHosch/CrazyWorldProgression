package ekuzo.crazyworldprogression.progression;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerPlayer;

public class PlayerModifications {

    private static final double MINING_SPEED_MULTIPLIER = 2.0;
    private static final Identifier MINING_SPEED_MODIFIER_ID =
            CrazyWorldProgression.id("mining_speed_multiplier");

    private static final double HEALTH_MODIFIER = -4.0;
    private static final Identifier HEALTH_MODIFIER_ID =
            CrazyWorldProgression.id("health_modifier");

    private static final double MOVEMENT_SPEED_MULTIPLIER = 1.4;
    private static final Identifier MOVEMENT_SPEED_MODIFIER_ID =
            CrazyWorldProgression.id("movement_speed_modifier");


    // Register player lifecycle callbacks that restore all custom attributes.
    public static void initialize() {
        ServerPlayerEvents.JOIN.register(PlayerModifications::applyAll);

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            applyAll(newPlayer);
        });
    }

    // Apply every configured attribute modification to one player.
    private static void applyAll(ServerPlayer player) {
        applyBlockBreakSpeed(player);
        applyMovementSpeed(player);
        applyMaxHealth(player);
    }

    // Apply the configured block-breaking speed multiplier.
    private static void applyBlockBreakSpeed(ServerPlayer player) {
        AttributeInstance blockBreakSpeed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);

        if (blockBreakSpeed != null) {
            blockBreakSpeed.addOrUpdateTransientModifier(new AttributeModifier(
                    MINING_SPEED_MODIFIER_ID,
                    MINING_SPEED_MULTIPLIER - 1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
    }

    // Apply the configured movement speed multiplier.
    private static void applyMovementSpeed(ServerPlayer player) {
        AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);

        if (movementSpeed != null) {
            movementSpeed.addOrUpdateTransientModifier(new AttributeModifier(
                    MOVEMENT_SPEED_MODIFIER_ID,
                    MOVEMENT_SPEED_MULTIPLIER - 1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
    }

    // Apply the configured maximum-health adjustment.
    private static void applyMaxHealth(ServerPlayer player) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);

        if (maxHealth != null) {
            maxHealth.addOrUpdateTransientModifier(new AttributeModifier(
                    HEALTH_MODIFIER_ID,
                    HEALTH_MODIFIER,
                    AttributeModifier.Operation.ADD_VALUE
            ));
        }
    }
}
