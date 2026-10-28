package ekuzo.crazyworldprogression.config;

import java.util.List;

/**
 * The parts of CWP that can be switched off in config/crazy-world-progression.yml. A switched-off part does nothing
 * (no events, commands, screens, admin pages or mixins), but its items, menus and saved data stay registered so
 * worlds that used it still load. Mixin names are relative to their mixin config's package.
 * <p>
 * Plain Java on purpose: the mixin plugin reads it before Minecraft is loaded.
 */
public enum CwpModule {
    SKILL_TREES("skill_trees", "YAML skill trees, the skill tree screen and /skilltree."),
    CHARACTER_SHEET("character_sheet", "The character sheet beside the inventory.",
            "client.InventoryScreenCharacterSheetMixin", "client.RecipeBookScreenCharacterSheetMixin"),
    SKILLS("skills", "Skill levels (mining, fishing, cooking, ...) gained by doing, and their passives."),
    EQUIPMENT("equipment", "Equipment sets, protection stats and set effects (ally healing, hubris, blood pact, static charge, draw speed).",
            "PlayerAllyHealMixin", "EnchantmentProtectionMixin", "BowItemDrawMixin", "CrossbowItemDrawMixin",
            "LivingEntityHealMixin", "FoodDataHealMixin", "ProjectileHitMixin", "client.UseDurationDrawMixin"),
    SMITHING("smithing", "Craftsmanship: item quality from crafting, the smithing table, repairs and durability.",
            "RecipeQualityMixin", "RecipeQualityMixin$Single", "RecipeQualityMixin$Shaped", "RecipeQualityMixin$Shapeless",
            "ItemStackDurabilityMixin", "BrokenItemEnchantmentMixin", "AnvilOneEnchantmentMixin"),
    REFORGE("reforge", "Reforging and research (study) of items."),
    FLETCHING("fletching", "Arrow crafting at the fletching table and the special arrows' effects.",
            "ArrowEffectsMixin", "LivingEntityBodkinMixin"),
    AMMO("ammo", "Ammo slots and switching ammo (R).", "PlayerAmmoMixin"),
    ACCESSORIES("accessories", "Accessory slots and the buff food slot.",
            "InventoryMenuAccessoryMixin", "InventoryDropAccessoriesMixin", "client.InventoryScreenAccessoryMixin"),
    FARMING("farming", "Farming levels, produce quality, crossbreeding and animal produce (milking, egg collecting).",
            "ChickenEggMixin", "ItemEntityEggQualityMixin", "HopperProduceMixin"),
    COOKING("cooking", "Cooking dishes and food quality.", "FoodQualityMixin"),
    FISHING("fishing", "Fishing loot, the fishing journal and fishing levels.",
            "FishingHookLevelMixin", "FishingHookLootMixin"),
    MOB_LOOT("mob_loot", "Loot pools for mob drops (given straight to the killer).", "LivingEntityNoLootDropMixin"),
    STORAGE("storage", "Chest qualities and scrolling chests, chest filters, quicksort, sort buttons and item locks.",
            "ChestQualityMixin", "ChestPlacedQualityMixin", "ChestSingleMixin", "ChestDoubleMenuMixin", "ChestFilterMixin",
            "AbstractContainerMenuLockMixin", "OffhandSwapLockMixin", "ServerPlayerLockDropMixin",
            "client.ChestRendererQualityMixin", "client.ChestRenderStateQualityMixin", "client.AbstractContainerScreenLockMixin"),
    SOCIAL("social", "Friends, clans, clan chat, clan missions and chest locks.", "HopperLockMixin", "BlockPlacedLockMixin"),
    COLLECTIBLES("collectibles", "Collectibles and mounts.", "EntityMountCooldownMixin"),
    DIVINE_PROTECTION("divine_protection", "Divine protection (keeping items on death).", "InventoryDivineProtectionMixin"),
    RESPAWN("respawn", "Respawn feedback and instant respawn.", "client.ClientRespawnSoundMixin", "client.GuiInstantRespawnMixin"),
    TITLES("titles", "Player titles above heads and in the tab list.",
            "client.AvatarRenderStateTitleMixin", "client.AvatarTitleMixin", "client.PlayerTabTitleMixin"),
    CURRENCY("currency", "Currency wallets, /balance and currency commands."),
    POSTMASTER("postmaster", "The postmaster: rewards waiting for players."),
    RANDOM_EVENTS("random_events", "Random world events."),
    WORLD_BOUNDARY("world_boundary", "The advanced world boundary, area restrictions and bonus areas.",
            "BonusAreaSpawnRulesMixin", "BonusAreaPatrolMixin", "BonusAreaPhantomMixin", "BonusAreaCreeperMixin"),
    PORTALS("portals", "Portals, pockets and their workshops, instances, raids, parties, buildings and waypoints.",
            "InstanceFireSpreadMixin", "InstanceMobSpawnMixin", "InstanceRandomTickMixin", "PocketBuildTrackingMixin",
            "PocketWeatherMixin", "LevelLightEngineBatchMixin", "client.EntityVisibilityMixin"),
    MOBFORGE("mobforge", "MobForge: custom mobs, their AI and encounter scripts.", "EntityNoVehicleMixin");

    private final String key;
    private final String description;
    private final List<String> mixins;

    CwpModule(String key, String description, String... mixins) {
        this.key = key;
        this.description = description;
        this.mixins = List.of(mixins);
    }

    /** The name in the config file. */
    public String key() { return key; }

    public String description() { return description; }

    /** Mixins (relative class names) that only apply while this part is on. */
    public List<String> mixins() { return mixins; }

    /** True while this part is switched on. */
    public boolean enabled() { return CwpConfig.get().enabled(this); }

    public static CwpModule byKey(String key) {
        for (CwpModule module : values()) if (module.key.equals(key)) return module;
        return null;
    }
}
