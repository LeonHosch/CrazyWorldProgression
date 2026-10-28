package ekuzo.crazyworldprogression.loot;

import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.portals.PortalService;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Selects fishing pools and turns their item rewards into normal bobber catches. */
public final class FishingLootService {
    private FishingLootService() { }

    public static List<FishingLootData.Rule> all(MinecraftServer server) {
        return FishingLootData.get(server).rules();
    }

    public static FishingLootData.Rule get(MinecraftServer server, Identifier id) {
        return all(server).stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    public static List<String> locations(MinecraftServer server) {
        return FishingLootData.get(server).locations();
    }

    public static List<FishingLootData.Rule> rulesAt(MinecraftServer server, String location) {
        return all(server).stream().filter(rule -> rule.biome().equals(location)).toList();
    }

    public static void createLocation(MinecraftServer server, Identifier location) {
        String value = location.toString();
        if (locations(server).contains(value))
            throw new IllegalArgumentException("Biome or pocket already exists: " + location);
        if (!PocketEditorService.exists(server, location)
                && server.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, location)).isEmpty())
            throw new IllegalArgumentException("Unknown biome or pocket " + location);
        FishingLootData.get(server).addLocation(value);
    }

    public static void deleteLocation(MinecraftServer server, String location) {
        if (!locations(server).contains(location))
            throw new IllegalArgumentException("Unknown biome or pocket " + location);
        FishingLootData.get(server).removeLocation(location);
    }

    public static void save(MinecraftServer server, FishingLootData.Rule rule) {
        if (all(server).size() >= 256 && get(server, rule.id()) == null)
            throw new IllegalArgumentException("At most 256 fishing rules are allowed");
        if (LootPoolService.get(server, rule.pool()) == null)
            throw new IllegalArgumentException("Unknown loot pool " + rule.pool());
        validateSelector(rule.biome(), "biome");
        validateSelector(rule.liquid(), "liquid");
        if (!rule.biome().isEmpty() && !rule.biome().startsWith("#")
                && !PocketEditorService.exists(server, Identifier.parse(rule.biome()))
                && server.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, Identifier.parse(rule.biome()))).isEmpty())
            throw new IllegalArgumentException("Unknown biome or pocket " + rule.biome());
        if (!List.of("any", "clear", "rain", "thunder").contains(rule.weather()))
            throw new IllegalArgumentException("Weather must be any, clear, rain, or thunder");
        if (!List.of("any", "day", "night").contains(rule.time()))
            throw new IllegalArgumentException("Time must be any, day, or night");
        if (rule.weight() < 1 || rule.weight() > 10000)
            throw new IllegalArgumentException("Ruleset weight must be between 1 and 10000");
        FishingLootData.get(server).put(rule);
    }

    public static void delete(MinecraftServer server, Identifier id) {
        FishingLootData.get(server).remove(id);
    }

    private static void validateSelector(String value, String label) {
        if (value.startsWith("#")) throw new IllegalArgumentException("Fishing rules no longer use # tags");
        if (value.length() > 128 || (!value.isEmpty() && Identifier.tryParse(value) == null))
            throw new IllegalArgumentException("Invalid " + label + " ID: " + value);
        if (!value.isEmpty() && !value.startsWith("#")) {
            Identifier id = Identifier.parse(value);
            if (label.equals("liquid") && BuiltInRegistries.FLUID.getOptional(id).isEmpty())
                throw new IllegalArgumentException("Unknown fluid " + id);
        }
    }

    /** Pure selection function, also usable by content mods with their own fishing mechanics. */
    public static List<FishingLootData.Rule> matching(List<FishingLootData.Rule> rules,
            Identifier biome, Predicate<Identifier> biomeTag, Identifier liquid, Predicate<Identifier> liquidTag,
            String weather, String time) {
        return matching(rules, biome, biomeTag, null, liquid, liquidTag, weather, time);
    }

    public static List<FishingLootData.Rule> matching(List<FishingLootData.Rule> rules,
            Identifier biome, Predicate<Identifier> biomeTag, Identifier pocket,
            Identifier liquid, Predicate<Identifier> liquidTag, String weather, String time) {
        List<FishingLootData.Rule> result = new ArrayList<>();
        for (FishingLootData.Rule rule : rules) {
            if ((selectorMatches(rule.biome(), biome, biomeTag)
                    || pocketMatches(rule.biome(), pocket))
                    && selectorMatches(rule.liquid(), liquid, liquidTag)
                    && weatherMatches(rule.weather(), weather)
                    && (rule.time().equals("any") || rule.time().equals(time))) result.add(rule);
        }
        if (pocket != null && result.stream().anyMatch(rule -> rule.biome().equals(pocket.toString())))
            return result.stream().filter(rule -> rule.biome().equals(pocket.toString())).toList();
        return result;
    }

    /** Rain rules also bite in thunderstorms (it rains then too); thunder rules only in thunderstorms. */
    static boolean weatherMatches(String rule, String weather) {
        return rule.equals("any") || rule.equals(weather) || (rule.equals("rain") && weather.equals("thunder"));
    }

    private static boolean pocketMatches(String selector, Identifier pocket) {
        return pocket != null && !selector.isEmpty() && !selector.startsWith("#")
                && selector.equals(pocket.toString());
    }

    /** The current pocket in either its workshop or a running instance. */
    public static Identifier pocketAt(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Identifier dimension = level.dimension().identifier();
        Identifier workshop = PocketEditorService.pocketForDimension(server, dimension);
        if (workshop != null) return workshop;
        return PortalService.activeInstances(server).stream()
                .filter(instance -> instance.slotDimension().equals(dimension))
                .map(PortalService.ActiveInstance::templateId).findFirst().orElse(null);
    }

    private static boolean selectorMatches(String selector, Identifier id, Predicate<Identifier> tag) {
        if (selector.isEmpty()) return true;
        if (selector.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(selector.substring(1));
            return tagId != null && tag.test(tagId);
        }
        return id != null && selector.equals(id.toString());
    }

    /** Picks one of the matching rules. Weights are relative among the rules that match this catch. */
    public static FishingLootData.Rule choose(List<FishingLootData.Rule> rules, LootPoolService.Roller random) {
        long total = 0;
        for (FishingLootData.Rule rule : rules) if (rule.weight() > 0) total += rule.weight();
        if (total == 0 || total > Integer.MAX_VALUE) return null;
        int roll = random.nextInt((int) total);
        for (FishingLootData.Rule rule : rules) {
            if (rule.weight() <= 0) continue;
            roll -= rule.weight();
            if (roll < 0) return rule;
        }
        return null;
    }

    /** Returns null to retain vanilla loot. An empty list is a valid custom catch. */
    public static ObjectArrayList<ItemStack> catchAt(ServerLevel level, ServerPlayer player,
            BlockPos pos, FluidState fluid) {
        MinecraftServer server = level.getServer();
        List<FishingLootData.Rule> rules = all(server);
        if (rules.isEmpty()) return null;
        Holder<Biome> biome = level.getBiome(pos);
        Identifier biomeId = biome.unwrapKey().map(key -> key.identifier()).orElse(null);
        Identifier fluidId = fluid.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(fluid.getType());
        // Vanilla flowing fluids use separate registry IDs. Treat them as their source liquid.
        if (fluid.is(FluidTags.WATER)) fluidId = Identifier.withDefaultNamespace("water");
        else if (fluid.is(FluidTags.LAVA)) fluidId = Identifier.withDefaultNamespace("lava");
        String weather = level.isRainingAt(pos) ? (level.isThundering() ? "thunder" : "rain") : "clear";
        long dayTick = Math.floorMod(level.getDefaultClockTime(), 24000L);
        String time = dayTick >= 13000L && dayTick < 23000L ? "night" : "day";
        List<FishingLootData.Rule> matches = matching(rules, biomeId,
                id -> biome.is(TagKey.create(Registries.BIOME, id)), pocketAt(level), fluidId,
                id -> fluid.is(TagKey.create(Registries.FLUID, id)), weather, time);
        Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
        FishingLootData.Rule selected = choose(matches.stream()
                .filter(rule -> pools.containsKey(rule.pool().toString())).toList(), player.getRandom()::nextInt);
        if (selected == null) return null;
        ObjectArrayList<ItemStack> catchItems = new ObjectArrayList<>();
        // Fish above the player's fishing level cannot bite yet.
        int fishingLevel = FishingJournal.level(player);
        // The rank each caught item needed, for its fishing XP.
        Map<Item, Integer> ranks = new java.util.HashMap<>();
        for (LootPoolData.Entry reward : LootPoolService.pick(selected.pool(), pools, player.getRandom()::nextInt,
                entry -> entry.level() <= fishingLevel)) {
                int amount = reward.min() + player.getRandom().nextInt(reward.max() - reward.min() + 1);
                if (reward.kind().equals("currency")) {
                    CurrencyService.credit(server, player.getUUID(), reward.target(), amount);
                    continue;
                }
                Item item = BuiltInRegistries.ITEM.getOptional(reward.target()).orElse(null);
                if (item == null) continue;
                ranks.merge(item, reward.level(), Math::max);
                if (reward.showInChat()) server.getPlayerList().broadcastSystemMessage(
                        LootPoolService.announcement(player, new ItemStack(item, amount)), false);
                while (amount > 0) {
                    int count = Math.min(amount, Math.max(1, item.getDefaultMaxStackSize()));
                    ItemStack stack = new ItemStack(item, count);
                    catchItems.add(stack);
                    amount -= count;
                }
            }
        // Caught fish get a quality from the fishing score, or slip off the hook (other catches stay).
        List<ItemStack> graded = ekuzo.crazyworldprogression.farming.ProduceQuality.harvest(player, catchItems,
                ekuzo.crazyworldprogression.farming.ProduceQuality.FISH);
        catchItems = new ObjectArrayList<>(graded);
        FishingJournal.record(player, catchItems);
        awardXp(player, catchItems, ranks);
        return catchItems;
    }

    /**
     * Fishing XP for a catch: per item, the rank its pool entry needs (vanilla catches: Novice) times its quality
     * ({@link ekuzo.crazyworldprogression.skills.Skills#xpFor}); items without a quality, such as treasure, count as
     * Fine. Fish that slipped off the hook give nothing.
     */
    public static void awardXp(ServerPlayer player, List<ItemStack> caught, Map<Item, Integer> ranks) {
        long xp = 0;
        for (ItemStack stack : caught) {
            if (stack.isEmpty()) continue;
            var quality = ekuzo.crazyworldprogression.farming.ProduceQuality.get(stack);
            xp += (long) stack.getCount() * ekuzo.crazyworldprogression.skills.Skills.xpFor(
                    ranks.getOrDefault(stack.getItem(), 0),
                    quality == null ? ekuzo.crazyworldprogression.skills.Skills.FINE : quality.ordinal());
        }
        ekuzo.crazyworldprogression.skills.Skills.add(player, ekuzo.crazyworldprogression.skills.Skills.Skill.FISHING,
                (int) Math.min(Integer.MAX_VALUE, xp));
    }
}
