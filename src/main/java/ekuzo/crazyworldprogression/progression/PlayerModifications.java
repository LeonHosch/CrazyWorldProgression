package ekuzo.crazyworldprogression.progression;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

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

    private static final double FALL_DAMAGE_MULTIPLIER = 2.0;
    private static final Identifier FALL_DAMAGE_MODIFIER_ID =
            CrazyWorldProgression.id("fall_damage_modifier");

    private static final double BURNING_TIME_MULTIPLIER = 0.5;
    private static final Identifier BURNING_TIME_MODIFIER_ID =
            CrazyWorldProgression.id("movement_speed_modifier");

    public static void initialize() {
        ServerPlayerEvents.JOIN.register(player -> {
            AttributeInstance blockBreakSpeed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
            AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            AttributeInstance fallDamage = player.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER);
            AttributeInstance burningTime = player.getAttribute(Attributes.BURNING_TIME);

            if (blockBreakSpeed != null) {
                blockBreakSpeed.addOrUpdateTransientModifier(new AttributeModifier(
                        MINING_SPEED_MODIFIER_ID,
                        MINING_SPEED_MULTIPLIER - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                ));
            }

            if (maxHealth != null) {
                maxHealth.addOrUpdateTransientModifier(new AttributeModifier(
                        HEALTH_MODIFIER_ID,
                        HEALTH_MODIFIER,
                        AttributeModifier.Operation.ADD_VALUE
                ));
            }

            if (movementSpeed != null) {
                movementSpeed.addOrUpdateTransientModifier(new AttributeModifier(
                        MOVEMENT_SPEED_MODIFIER_ID,
                        MOVEMENT_SPEED_MULTIPLIER - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                ));
            }

            if (fallDamage != null) {
                fallDamage.addOrUpdateTransientModifier(new AttributeModifier(
                        FALL_DAMAGE_MODIFIER_ID,
                        FALL_DAMAGE_MULTIPLIER - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                ));
            }

            if (burningTime != null) {
                burningTime.addOrUpdateTransientModifier(new AttributeModifier(
                        BURNING_TIME_MODIFIER_ID,
                        BURNING_TIME_MULTIPLIER - 1.0,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                ));
            }
        });
    }
}
