# Crossbreeding

Players crossbreed seeds at a **composter**: right-click it with an empty hand (sneaking or holding an item keeps the vanilla behavior, and a full composter still gives its bone meal). The window has two seed slots and one result slot.

Admins set up recipes in the admin panel under **Moderate → Crossbreeding**. Each recipe is one pair of seeds (either order) with:

- **Result** and **Result amount**.
- **Farming level**: the `addFarmingLevel` stat a player needs. Below it they cannot crossbreed into it, plant its seed, or harvest the crop. Breaking the crop anyway destroys it without drops. A seed that is the result of a recipe also needs that level to be used as an input ("analysed").
- **Failure chance while unknown**: until a player has bred this result once, the window shows "Unknown result" with its success chance, never the result itself, and a failure uses up the seeds and costs. After the first success the result is known to that player and always works.
- **Costs**: items and player currencies used up per crossbreed, on top of one of each seed. Every pair has its own costs.

Recipes are never sent to clients. When the seeds in the window change, the server works out what the player may see and sends only that, so unknown results stay secret. Every crossbreed is checked again on the server with real values.

Code: [CrossbreedingService](../src/main/java/ekuzo/crazyworldprogression/farming/CrossbreedingService.java), [FarmingLevels](../src/main/java/ekuzo/crazyworldprogression/farming/FarmingLevels.java), [AdminCrossbreedingEditor](../src/main/java/ekuzo/crazyworldprogression/admin/AdminCrossbreedingEditor.java). The window's look is `ReforgeScreenStyle.crossbreeding()` (default `GreenhouseStyle`).
