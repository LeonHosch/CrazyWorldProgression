# Cooking

Two cooking stations open the same kind of window: three ingredient slots, one slot for a bowl or coal, a dish slot and a **Cook** button.

- **Stew pot**: right-click any cauldron with an empty hand (buckets and bottles keep their vanilla use). The window always opens, but stews only cook when the cauldron is **full of water** and a **lit campfire** (normal or soul) is **directly below** it. The window says what is missing. Each stew uses one bowl.
- **Smoker**: right-click a smoker. Sneak-right-click still opens the vanilla smoker. Each dish uses one coal or charcoal.

Admins add recipes in the admin panel under **Moderate → Cooking**: the station, the dish and its amount, one to three ingredients (any order; add the same item twice to need two of it, one per slot), and a **cooking level**. Players below the level (the `addCookingLevel` stat from skill trees and sets) cannot cook that recipe. Custom food items can use the same recipes once they exist.

Code: [CookingService](../src/main/java/ekuzo/crazyworldprogression/cooking/CookingService.java), [CookingNetworking](../src/main/java/ekuzo/crazyworldprogression/cooking/CookingNetworking.java), [AdminCookingEditor](../src/main/java/ekuzo/crazyworldprogression/admin/AdminCookingEditor.java). Looks: `ReforgeScreenStyle.stewPot()` and `smoker()` (default `KitchenStyle`).
