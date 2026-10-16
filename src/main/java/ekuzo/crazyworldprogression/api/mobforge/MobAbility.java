package ekuzo.crazyworldprogression.api.mobforge;

/** One reusable action. A new instance is created for each cast. */
public interface MobAbility {
    default long cooldownTicks() { return 0; }
    default boolean canCast(MobAbilityContext context) { return true; }
    void execute(MobAbilityContext context);
}
