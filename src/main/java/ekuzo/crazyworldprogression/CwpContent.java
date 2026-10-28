package ekuzo.crazyworldprogression;

import java.util.List;

/**
 * Everything CWP adds to the game's registries or saves on players, chests and items (items, menus, effects, sounds,
 * item data and attachments). These classes register their content when they are first loaded, so loading them at
 * startup keeps all of it known even while its part is switched off: a world that used a switched-off part still
 * loads its items and saved data, and switching the part on again brings everything back.
 */
final class CwpContent {
    private static final List<String> HOLDERS = List.of(
            // Items, menus, effects, sounds and item data.
            "cooking.CookingMenu", "divine.DivineProtectionMenu", "farming.CrossbreedingMenu", "farming.ProduceQuality",
            "fletching.Fletching", "fletching.FletchingMenu", "loot.FishingJournal", "loot.PostmasterMenu",
            "progression.equipment.StaticCharge", "reforge.ReforgeMenu", "reforge.StudyMenu", "smithing.ScrollChestMenu",
            "smithing.SmithingMenu", "storage.ItemLocks",
            // Saved data on players, chests and animals.
            "accessory.Accessories", "accessory.BuffFood", "ammo.AmmoPouch", "collectibles.Collectibles",
            "farming.AnimalProduce", "farming.CrossbreedingService", "reforge.ResearchData", "skills.Skills",
            "smithing.ChestQuality", "social.ChestLocks", "storage.ChestFilters", "storage.Quicksort", "titles.TitleData");

    private CwpContent() { }

    static void register() {
        for (String holder : HOLDERS) {
            try {
                Class.forName("ekuzo.crazyworldprogression." + holder, true, CwpContent.class.getClassLoader());
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("CWP content class is missing: " + holder, exception);
            }
        }
    }
}
