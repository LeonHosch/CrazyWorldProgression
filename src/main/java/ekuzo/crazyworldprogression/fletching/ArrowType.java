package ekuzo.crazyworldprogression.fletching;

import java.util.List;

/**
 * The fletched arrows and what they do on top of a normal arrow (FletchedArrowItem, ArrowEffectsMixin). Each starts
 * with a default fletching recipe (FletchingData): four normal arrows plus its materials make four of it, at the rank
 * shown here; admins change recipes and ranks in the admin panel.
 */
public enum ArrowType {
    /** Normal damage, bleeds the target. */
    BROADHEAD("broadhead_arrow", "Broadhead Arrow", 3, List.of("minecraft:flint:1")),
    /** Slows the target briefly. */
    BARBED("barbed_arrow", "Barbed Arrow", 1, List.of("minecraft:iron_nugget:2")),
    /** Ignores part of the target's armor. */
    BODKIN("bodkin_arrow", "Bodkin Arrow", 2, List.of("minecraft:iron_ingot:1")),
    /** More damage, but flies slower. */
    HEAVY("heavy_arrow", "Heavy Arrow", 2, List.of("minecraft:copper_ingot:2")),
    /** Passes through enemies. */
    PIERCING("piercing_arrow", "Piercing Arrow", 3, List.of("minecraft:amethyst_shard:1", "minecraft:iron_ingot:1")),
    /** Pulls the target right in front of the shooter, unless it resists knockback. */
    GRAPPLING("grappling_arrow", "Grappling Arrow", 4, List.of("minecraft:lead:1", "minecraft:string:2")),
    /** Stuns the target briefly. */
    DISRUPTION("disruption_arrow", "Disruption Arrow", 4, List.of("minecraft:slime_ball:1", "minecraft:redstone:2")),
    /** Less damage, but explodes where it hits. */
    EXPLOSIVE("explosive_arrow", "Explosive Arrow", 5, List.of("minecraft:gunpowder:2", "minecraft:fire_charge:1"));

    /** Bleeding from a broadhead: ticks. */
    public static final int BLEED_TICKS = 100;
    /**
     * Bleeding damage per second: a base plus a share of the target's max health, capped (a 20 HP zombie takes 1.4,
     * 100 HP takes 3, anything from 200 HP takes 5).
     */
    public static final double BLEED_BASE = 1.0, BLEED_SHARE = 0.02, BLEED_MAX = 5.0;
    /** Slowness II from a barbed arrow: ticks. */
    public static final int BARBED_SLOW_TICKS = 40;
    /** Stun from a disruption arrow: ticks. */
    public static final int STUN_TICKS = 5;
    /** Share of the target's armor and toughness a bodkin ignores. */
    public static final float BODKIN_ARMOR_IGNORED = 0.5F;
    /** Heavy arrows: base damage (a normal arrow has 2; the slower flight takes some of it back) and launch speed multiplier. */
    public static final double HEAVY_DAMAGE = 4.0;
    public static final double HEAVY_SPEED = 0.7;
    /** Enemies a piercing arrow passes through. */
    public static final byte PIERCE_LEVEL = 2;
    /** How far in front of the shooter a grappling arrow puts its target (blocks, plus half its width). */
    public static final double GRAPPLE_DISTANCE = 1.5;
    /**
     * Explosive arrows: base damage, explosion radius (no blocks are broken) and the blast's damage at its center
     * (less further out, before armor). Arrow and blast together stay below a heavy arrow's hit.
     */
    public static final double EXPLOSIVE_DAMAGE = 1.0;
    public static final float EXPLOSION_RADIUS = 1.5F;
    public static final float EXPLOSION_DAMAGE = 8.0F;

    private final String path;
    private final String label;
    private final int defaultRank;
    /** Default extra materials as "item:amount", besides four normal arrows. */
    private final List<String> defaultMaterials;

    ArrowType(String path, String label, int defaultRank, List<String> defaultMaterials) {
        this.path = path;
        this.label = label;
        this.defaultRank = defaultRank;
        this.defaultMaterials = defaultMaterials;
    }

    public String path() { return path; }

    public String label() { return label; }

    public int defaultRank() { return defaultRank; }

    public List<String> defaultMaterials() { return defaultMaterials; }
}
