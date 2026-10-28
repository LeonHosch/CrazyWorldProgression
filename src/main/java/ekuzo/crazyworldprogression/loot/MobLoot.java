package ekuzo.crazyworldprogression.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.farming.ProduceQuality;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillMultiplierService;
import ekuzo.crazyworldprogression.skills.Skills;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Mob drops go through loot pools: no mob drops its loot table on the ground any more. Instead, when a player kills a
 * mob, the loot pool attached to its entity type is rolled for them (items go to their inventory, or the postmaster),
 * with their mob drop multiplier, meat quality, and the Fighting level's chance of a second roll.
 *
 * <p>On server start every mob gets a generated pool {@code crazy-world-progression:mob/<namespace>/<mob>}, once,
 * holding one "vanilla" entry: its loot table ({@code entities/<mob>}, from any mod), rolled as vanilla does with the
 * real kill (Looting, fire, a sheep's color, a creeper killed by a skeleton), but given to the killer. A mob without a
 * loot table gets an empty pool. After that the pools are ordinary loot pools to edit in the admin panel (Loot pools,
 * "Mob drops" tab), where other rewards can go next to the vanilla entry, a mob can get another pool, or its pool can be
 * reset. MobForge mobs roll their template's pool, plus their entity type's pool when the template keeps vanilla loot.
 *
 * <p>Mobs killed without a player drop nothing. Equipment a mob wore still drops as before.
 */
public final class MobLoot {
    public static final String PREFIX = "mob/";
    /** Pool entry kind that rolls a vanilla loot table (its target) with the mob kill being rolled for. */
    public static final String VANILLA = "vanilla";

    /** The kill whose pool is being rolled, so a vanilla entry can roll its loot table with it. */
    private static final ThreadLocal<Kill> KILL = new ThreadLocal<>();
    /** The items each entity loot table can give, for the book; read on start and on /reload. */
    private static volatile Map<Identifier, List<String>> tableItems = Map.of();

    private record Kill(LivingEntity entity, DamageSource source) { }

    private MobLoot() { }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadTableItems(server);
            ensureDefaults(server);
            migrateToVanillaEntries(server);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
            if (success) loadTableItems(server);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Player || !(entity.level().getServer() instanceof MinecraftServer server)) return;
            ServerPlayer player = source.getEntity() instanceof ServerPlayer direct ? direct
                    : entity.getLastHurtByPlayer() instanceof ServerPlayer recent ? recent : null;
            if (player == null) return;
            boolean forged = MobForgeEntityData.template(entity).isPresent();
            if (forged && !ekuzo.crazyworldprogression.mobforge.MobForge.TEMPLATES.get(MobForgeEntityData.template(entity).get())
                    .map(template -> template.loot().dropVanillaLoot()).orElse(false)) return;
            Identifier pool = pool(server, entity.getType());
            if (pool == null || LootPoolService.get(server, pool) == null) return;
            KILL.set(new Kill(entity, source));
            try {
                UnaryOperator<ItemStack> adjust = adjust(player);
                LootPoolService.roll(server, player, pool, adjust);
                if (Skills.bonusRoll(player, Skills.Skill.FIGHTING)) LootPoolService.roll(server, player, pool, adjust);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Could not roll mob loot pool {}: {}", pool, exception.getMessage());
            } finally {
                KILL.remove();
            }
        });
    }

    /** Per kill: the player's mob drop multiplier on every item, and one meat quality (or ruined meat) for the kill. */
    static UnaryOperator<ItemStack> adjust(ServerPlayer player) {
        double multiplier = SkillMultiplierService.mobDropMultiplier(player);
        UnaryOperator<ItemStack> meat = ProduceQuality.butcherAdjust(player);
        return stack -> {
            List<ItemStack> copies = new ArrayList<>();
            SkillMultiplierService.appendMultiplied(player.getRandom(), copies::add, stack, multiplier);
            int count = copies.stream().mapToInt(ItemStack::getCount).sum();
            if (count <= 0) return ItemStack.EMPTY;
            return meat.apply(stack.copyWithCount(count));
        };
    }

    /**
     * Rolls a vanilla loot table for the mob kill being rolled, as vanilla would on its death (with the killer as the
     * player); empty outside a mob kill, such as a vanilla entry in a fishing pool.
     */
    static List<ItemStack> rollVanilla(ServerPlayer player, Identifier table) {
        Kill kill = KILL.get();
        if (kill == null || !(kill.entity().level() instanceof ServerLevel level)) return List.of();
        LivingEntity entity = kill.entity();
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, kill.source())
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, kill.source().getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, kill.source().getDirectEntity())
                .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.ENTITY);
        List<ItemStack> drops = new ArrayList<>();
        level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, table))
                .getRandomItems(params, player.getRandom().nextLong(), drops::add);
        // Smelted drops (a burning cow's beef) come out of the furnace recipe as Miserable, like vanilla cooking;
        // here they are meat from a kill, so they get the butchering quality instead.
        for (ItemStack drop : drops) drop.remove(ekuzo.crazyworldprogression.farming.ProduceQuality.QUALITY);
        return drops;
    }

    /** The items a loot table can give (for the book), or none when it is not an entity loot table. */
    public static List<String> tableItems(Identifier table) { return tableItems.getOrDefault(table, List.of()); }

    /** A mob's own loot table ID: {@code <namespace>:entities/<mob>}. */
    public static Identifier lootTable(Identifier entityType) {
        return Identifier.fromNamespaceAndPath(entityType.getNamespace(), "entities/" + entityType.getPath());
    }

    /** The pool attached to an entity type, or null. */
    public static Identifier pool(MinecraftServer server, EntityType<?> type) {
        String value = Data.get(server).attached.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        return value == null ? null : Identifier.tryParse(value);
    }

    /** Every mob with an attached pool: entity type ID to pool ID. */
    public static Map<String, String> attached(MinecraftServer server) { return Map.copyOf(Data.get(server).attached); }

    /** Attaches another pool to a mob (null detaches it: the mob drops nothing). */
    public static void attach(MinecraftServer server, Identifier entityType, Identifier pool) {
        if (BuiltInRegistries.ENTITY_TYPE.getOptional(entityType).isEmpty()) throw new IllegalArgumentException("Unknown mob " + entityType);
        if (pool != null && LootPoolService.get(server, pool) == null) throw new IllegalArgumentException("Unknown loot pool " + pool);
        Data data = Data.get(server);
        if (pool == null) data.attached.remove(entityType.toString());
        else data.attached.put(entityType.toString(), pool.toString());
        data.setDirty();
    }

    /** Rebuilds a mob's generated pool (dropping edits: only its vanilla entry is left) and attaches it again. */
    public static Identifier reset(MinecraftServer server, Identifier entityType) {
        Identifier id = generatedId(entityType);
        if (LootPoolService.get(server, id) != null) {
            for (LootPoolData.Pool parent : LootPoolService.parents(LootPoolService.all(server), id))
                throw new IllegalArgumentException("The pool is nested in " + parent.id());
            LootPoolService.delete(server, id);
        }
        if (!generate(server, entityType)) throw new IllegalArgumentException(entityType + " is not a mob");
        attach(server, entityType, id);
        return id;
    }

    public static Identifier generatedId(Identifier entityType) {
        return CrazyWorldProgression.id(PREFIX + entityType.getNamespace() + "/" + entityType.getPath());
    }

    /** Generates the pools of mobs that never had one (a deleted pool is not brought back). */
    private static void ensureDefaults(MinecraftServer server) {
        Data data = Data.get(server);
        int created = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (data.generated.contains(id.toString())) continue;
            try {
                if (generate(server, id)) {
                    data.attached.put(id.toString(), generatedId(id).toString());
                    created++;
                }
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Could not create the loot pool of {}: {}", id, exception.getMessage());
            }
            data.generated.add(id.toString());
            data.setDirty();
        }
        if (created > 0) CrazyWorldProgression.LOGGER.info("Created {} mob loot pool(s) with their vanilla drops", created);
    }

    /**
     * A mob's generated pool: its vanilla loot table as one entry (empty when it has none); false when it is not a mob
     * (no loot table and a misc entity, such as a boat or an arrow).
     */
    private static boolean generate(MinecraftServer server, Identifier entityType) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entityType);
        JsonObject table = table(server, lootTable(entityType));
        if (table == null && type.getCategory() == net.minecraft.world.entity.MobCategory.MISC) return false;
        String name = type.getDescription().getString();
        LootPoolService.save(server, new LootPoolData.Pool(generatedId(entityType), name + " drops", "all",
                table == null ? List.of() : List.of(vanillaEntry(entityType))));
        return true;
    }

    private static LootPoolData.Entry vanillaEntry(Identifier entityType) {
        return new LootPoolData.Entry(VANILLA, lootTable(entityType), 10000, 1, 1, 1);
    }

    /**
     * Pools generated before vanilla entries held a copy of the loot table's drops. Once per world, every such pool
     * that was not edited since is switched to the vanilla entry (with its "Pick one" nested pools removed); edited
     * ones are kept and logged, to reset by hand.
     */
    private static void migrateToVanillaEntries(MinecraftServer server) {
        Data data = Data.get(server);
        if (data.vanillaEntries) return;
        int migrated = 0;
        List<String> edited = new ArrayList<>();
        Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
        for (Map.Entry<String, String> attached : Map.copyOf(data.attached).entrySet()) {
            Identifier type = Identifier.tryParse(attached.getKey());
            if (type == null || !attached.getValue().equals(generatedId(type).toString())) continue;
            LootPoolData.Pool pool = pools.get(attached.getValue());
            JsonObject table = table(server, lootTable(type));
            if (pool == null || table == null || pool.entries().stream().anyMatch(entry -> entry.kind().equals(VANILLA))) continue;
            try {
                List<LootPoolData.Pool> nested = new ArrayList<>();
                List<LootPoolData.Entry> copied = convertTable(server, table, pool.id(), nested, 0);
                boolean unchanged = copied.equals(pool.entries())
                        && nested.stream().allMatch(value -> value.equals(pools.get(value.id().toString())));
                if (!unchanged) {
                    edited.add(type.toString());
                    continue;
                }
                LootPoolService.save(server, new LootPoolData.Pool(pool.id(), pool.name(), pool.mode(), List.of(vanillaEntry(type))));
                for (LootPoolData.Pool value : nested) LootPoolService.deleteIfUnused(server, value.id());
                migrated++;
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Could not switch the loot pool of {} to its vanilla drops: {}", type, exception.getMessage());
            }
        }
        data.vanillaEntries = true;
        data.setDirty();
        CrazyWorldProgression.LOGGER.info("Switched {} mob loot pool(s) to their vanilla drops", migrated);
        if (!edited.isEmpty()) CrazyWorldProgression.LOGGER.info(
                "Kept {} edited mob loot pool(s) as they are; use \"Reset to vanilla\" in the admin panel to switch them: {}",
                edited.size(), edited);
    }

    /** Every item each entity loot table (and the tables it references) can give. */
    private static void loadTableItems(MinecraftServer server) {
        Map<Identifier, List<String>> result = new LinkedHashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier table = lootTable(BuiltInRegistries.ENTITY_TYPE.getKey(type));
            JsonObject json = table(server, table);
            if (json == null) continue;
            Set<String> items = new LinkedHashSet<>();
            try {
                items(server, json, items, 0);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Could not read the loot table {}: {}", table, exception.getMessage());
            }
            result.put(table, List.copyOf(items));
        }
        tableItems = Map.copyOf(result);
    }

    private static void items(MinecraftServer server, JsonObject table, Set<String> result, int depth) {
        if (depth > 3 || !table.has("pools")) return;
        for (JsonElement pool : table.getAsJsonArray("pools"))
            for (JsonElement entry : pool.getAsJsonObject().getAsJsonArray("entries"))
                entryItems(server, entry.getAsJsonObject(), result, depth);
    }

    private static void entryItems(MinecraftServer server, JsonObject entry, Set<String> result, int depth) {
        String type = entry.get("type").getAsString();
        if (entry.has("children"))
            for (JsonElement child : entry.getAsJsonArray("children")) entryItems(server, child.getAsJsonObject(), result, depth);
        if (type.equals("minecraft:item")) {
            Identifier item = Identifier.tryParse(entry.get("name").getAsString());
            if (item != null && BuiltInRegistries.ITEM.getOptional(item).isPresent()) result.add(item.toString());
        } else if (type.equals("minecraft:loot_table") && entry.get("value").isJsonPrimitive()) {
            JsonObject referenced = table(server, Identifier.parse(entry.get("value").getAsString()));
            if (referenced != null) items(server, referenced, result, depth + 1);
        }
    }

    private static JsonObject table(MinecraftServer server, Identifier table) {
        Identifier file = Identifier.fromNamespaceAndPath(table.getNamespace(), "loot_table/" + table.getPath() + ".json");
        var resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) return null;
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            return null;
        }
    }

    // ---------------------------------------------------------------- old loot table copies (to recognize them)

    /**
     * How pools used to copy a loot table: one "all" pool's entries, one per vanilla pool (a nested pick-one pool when
     * it has several drops). Only used to tell unedited old pools apart; must stay as it was.
     */
    private static List<LootPoolData.Entry> convertTable(MinecraftServer server, JsonObject table, Identifier owner,
                                                         List<LootPoolData.Pool> nested, int depth) {
        List<LootPoolData.Entry> result = new ArrayList<>();
        if (depth > 3 || !table.has("pools")) return result;
        for (JsonElement element : table.getAsJsonArray("pools")) {
            JsonObject pool = element.getAsJsonObject();
            Double chance = chance(pool.getAsJsonArray("conditions"));
            if (chance == null) continue;
            int rolls = Math.max(1, Math.min(8, (int) Math.round(expected(pool.get("rolls")))));
            // Each candidate: an item entry (with its own chance and amount) and its weight.
            List<LootPoolData.Entry> candidates = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            int emptyWeight = 0;
            for (JsonElement entryElement : pool.getAsJsonArray("entries")) {
                JsonObject entry = resolve(entryElement.getAsJsonObject());
                if (entry == null) continue;
                int weight = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
                String type = entry.get("type").getAsString();
                if (type.equals("minecraft:empty")) {
                    emptyWeight += weight;
                    continue;
                }
                Double entryChance = chance(entry.getAsJsonArray("conditions"));
                if (entryChance == null) continue;
                if (type.equals("minecraft:item")) {
                    LootPoolData.Entry item = item(entry, entryChance);
                    if (item != null) {
                        candidates.add(item);
                        weights.add(weight);
                    }
                } else if (type.equals("minecraft:loot_table") && entry.get("value").isJsonPrimitive()) {
                    JsonObject referenced = table(server, Identifier.parse(entry.get("value").getAsString()));
                    if (referenced == null) continue;
                    List<LootPoolData.Entry> inner = convertTable(server, referenced, owner, nested, depth + 1);
                    if (inner.size() == 1) {
                        LootPoolData.Entry only = inner.getFirst();
                        candidates.add(withChance(only, only.chance() * entryChance));
                        weights.add(weight);
                    }
                }
            }
            if (candidates.isEmpty()) continue;
            if (candidates.size() == 1 && emptyWeight == 0) {
                LootPoolData.Entry only = candidates.getFirst();
                for (int roll = 0; roll < rolls; roll++) result.add(withChance(only, only.chance() * chance));
                continue;
            }
            // Several drops: a nested pool that picks one of them, by their weights.
            int total = weights.stream().mapToInt(Integer::intValue).sum();
            List<LootPoolData.Entry> shares = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                LootPoolData.Entry candidate = candidates.get(index);
                shares.add(withChance(candidate, Math.max(1, Math.round(10000.0 * weights.get(index) / total))));
            }
            Map<String, LootPoolData.Pool> taken = new LinkedHashMap<>();
            nested.forEach(pool1 -> taken.put(pool1.id().toString(), pool1));
            Identifier nestedId = LootPoolService.nestedId(taken, owner);
            nested.add(new LootPoolData.Pool(nestedId, "Pick one", "one", shares));
            // Odds that one of them drops at all: the pool's chance, less the empty entries' share and each drop's own chance.
            double pick = 0;
            for (int index = 0; index < candidates.size(); index++)
                pick += candidates.get(index).chance() / 10000.0 * weights.get(index);
            pick /= total + emptyWeight;
            int nestedChance = (int) Math.max(1, Math.round(10000 * chance * pick));
            for (int roll = 0; roll < rolls; roll++)
                result.add(new LootPoolData.Entry("pool", nestedId, nestedChance, 1, 1, 1));
        }
        return result;
    }

    /** An "alternatives" entry is replaced by its first child (for a sheep: white wool); other entries stay. */
    private static JsonObject resolve(JsonObject entry) {
        if (!entry.get("type").getAsString().equals("minecraft:alternatives")) return entry;
        JsonArray children = entry.getAsJsonArray("children");
        if (children == null || children.isEmpty()) return null;
        JsonObject first = children.get(0).getAsJsonObject().deepCopy();
        first.remove("conditions");
        return first;
    }

    /** An item drop: the chance it drops at least one and its amount range (from set_count). */
    private static LootPoolData.Entry item(JsonObject entry, double chance) {
        Identifier item = Identifier.tryParse(entry.get("name").getAsString());
        if (item == null || BuiltInRegistries.ITEM.getOptional(item).isEmpty()) return null;
        Map<Integer, Double> counts = Map.of(1, 1.0);
        if (entry.has("functions")) for (JsonElement function : entry.getAsJsonArray("functions")) {
            JsonObject value = function.getAsJsonObject();
            if (value.get("function").getAsString().equals("minecraft:set_count") && !value.has("conditions"))
                counts = distribution(value.get("count"));
        }
        double some = counts.entrySet().stream().filter(count -> count.getKey() >= 1).mapToDouble(Map.Entry::getValue).sum();
        int min = counts.keySet().stream().filter(count -> count >= 1).min(Integer::compare).orElse(0);
        int max = counts.keySet().stream().filter(count -> count >= 1).max(Integer::compare).orElse(0);
        if (some <= 0 || min < 1) return null;
        return new LootPoolData.Entry("item", item, (int) Math.max(1, Math.round(10000 * chance * some)), 1, min,
                Math.min(2304, max));
    }

    private static LootPoolData.Entry withChance(LootPoolData.Entry entry, double chance) {
        return new LootPoolData.Entry(entry.kind(), entry.target(), (int) Math.max(1, Math.min(10000, Math.round(chance))),
                entry.weight(), entry.min(), entry.max());
    }

    /**
     * The combined chance of a pool's or entry's conditions: killed_by_player always holds (only player kills roll),
     * random chances multiply; null when a condition needs something else (how the mob looks or died).
     */
    private static Double chance(JsonArray conditions) {
        double chance = 1.0;
        if (conditions == null) return chance;
        for (JsonElement element : conditions) {
            JsonObject condition = element.getAsJsonObject();
            switch (condition.get("condition").getAsString()) {
                case "minecraft:killed_by_player" -> { }
                case "minecraft:random_chance" -> chance *= expected(condition.get("chance"));
                case "minecraft:random_chance_with_enchanted_bonus" -> chance *= condition.has("unenchanted_chance")
                        ? condition.get("unenchanted_chance").getAsDouble() : 1.0;
                default -> { return null; }
            }
        }
        return chance;
    }

    /** A number provider's average. */
    private static double expected(JsonElement provider) {
        if (provider == null) return 1.0;
        if (provider.isJsonPrimitive()) return provider.getAsDouble();
        JsonObject object = provider.getAsJsonObject();
        if (object.has("value")) return object.get("value").getAsDouble();
        if (object.has("n") && object.has("p")) return expected(object.get("n")) * expected(object.get("p"));
        if (object.has("min") && object.has("max")) return (expected(object.get("min")) + expected(object.get("max"))) / 2;
        return 1.0;
    }

    /** The whole-number outcomes of a count provider and their odds (constant, uniform, binomial). */
    private static Map<Integer, Double> distribution(JsonElement provider) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        if (provider.isJsonPrimitive()) {
            result.put((int) Math.floor(provider.getAsDouble()), 1.0);
            return result;
        }
        JsonObject object = provider.getAsJsonObject();
        String type = object.has("type") ? object.get("type").getAsString() : "minecraft:uniform";
        switch (type) {
            case "minecraft:constant" -> result.put((int) Math.floor(object.get("value").getAsDouble()), 1.0);
            case "minecraft:binomial" -> {
                int n = (int) expected(object.get("n"));
                double p = expected(object.get("p"));
                for (int k = 0; k <= n; k++) result.put(k, choose(n, k) * Math.pow(p, k) * Math.pow(1 - p, n - k));
            }
            default -> {
                int min = (int) Math.floor(expected(object.get("min"))), max = (int) Math.floor(expected(object.get("max")));
                if (max < min) max = min;
                for (int value = min; value <= max; value++) result.put(value, 1.0 / (max - min + 1));
            }
        }
        return result;
    }

    private static double choose(int n, int k) {
        double result = 1;
        for (int i = 1; i <= k; i++) result = result * (n - k + i) / i;
        return result;
    }

    // ---------------------------------------------------------------- storage

    /**
     * Which pool each mob uses, which mobs already got a generated pool (so deleted pools stay deleted), and whether
     * old copied pools were switched to vanilla entries.
     */
    static final class Data extends SavedData {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("attached", Map.of()).forGetter(data -> data.attached),
                Codec.STRING.listOf().optionalFieldOf("generated", List.of()).forGetter(data -> List.copyOf(data.generated)),
                Codec.BOOL.optionalFieldOf("vanilla_entries", false).forGetter(data -> data.vanillaEntries)
        ).apply(instance, Data::new));
        private static final SavedDataType<Data> TYPE = new SavedDataType<>(CrazyWorldProgression.id("mob_loot"), Data::new, CODEC, null);
        final Map<String, String> attached = new LinkedHashMap<>();
        final Set<String> generated = new LinkedHashSet<>();
        boolean vanillaEntries;

        Data() { }

        private Data(Map<String, String> attached, List<String> generated, boolean vanillaEntries) {
            this.attached.putAll(attached);
            this.generated.addAll(generated);
            this.vanillaEntries = vanillaEntries;
        }

        static Data get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    }

    /** Whether a pool is a generated mob pool (kept off the main loot pool list). */
    public static boolean isMobPool(Identifier id) {
        return id.getNamespace().equals(CrazyWorldProgression.MOD_ID) && id.getPath().startsWith(PREFIX);
    }
}
