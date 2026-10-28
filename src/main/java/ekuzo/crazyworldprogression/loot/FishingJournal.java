package ekuzo.crazyworldprogression.loot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The loot journal (item ID still {@code fishing_journal}): one book for everything that has a loot pool attached,
 * sorted into categories, and in each category the things that have one:
 * <ul>
 *   <li><b>Fishing</b>: each fishing location (biome or pocket) with the fish its rules can give, grouped by when they
 *       bite ("weather|time|liquid", such as "rain|night|"). Fish above the player's fishing level are locked.</li>
 *   <li><b>Mobs</b>: every MobForge mob template with a loot pool.</li>
 *   <li><b>Dungeons</b> and <b>Raids</b>: every pocket portal (by its instance mode) whose pocket scripts roll a loot
 *       pool or spawn mobs that have one; grouped into the completion rewards and each mob's drops.</li>
 *   <li><b>Events</b>: every other MobForge script that rolls a loot pool.</li>
 * </ul>
 * Under "Collectibles" it lists every registered <b>mount</b> and <b>pet</b> (CWP Collectibles): unlocked ones with
 * their rarity and stats, where the player selects them; locked ones as "???".
 * Items the player has got show as themselves (fish they caught, or an item a pool gave them); others are hidden and
 * the client draws its category's grey symbol. An entry is named "???" until the player got at least one of its
 * items. The server never sends what a hidden item is, nor a hidden entry's name.
 * <p>
 * Also home of the {@code addFishingLevel} stat (fishing proficiency, from skill tree nodes): an entry's level in a
 * loot pool is the proficiency needed to catch it, and fishing catches skip entries above the player's proficiency.
 * The Fishing level (Skills) makes fish bite sooner on average.
 */
public final class FishingJournal {
    public static final String LEVEL_STAT = "addFishingLevel";
    /** Each Fishing level shortens the wait for a bite by this many ticks (1 s), up to 10 s (FishingHookLevelMixin). */
    public static final int BITE_TICKS_PER_LEVEL = 20;
    public static final int MAX_BITE_TICKS = 200;
    private static final Gson GSON = new Gson();
    /** What each online player's client has cached: content revision, caught count and fishing level. */
    private static final Map<java.util.UUID, String> SENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Item IDs the player has ever caught by fishing. */
    public static final AttachmentType<List<String>> CAUGHT = AttachmentRegistry.create(
            CrazyWorldProgression.id("fishing_caught"),
            builder -> builder.persistent(Codec.STRING.listOf()).copyOnDeath().initializer(List::of));
    /** Items the player has got from each loot pool (pool ID to item IDs). */
    public static final AttachmentType<Map<String, List<String>>> LOOTED = AttachmentRegistry.create(
            CrazyWorldProgression.id("looted"), builder -> builder.persistent(
                    Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())).copyOnDeath().initializer(Map::of));
    public static final Item ITEM = register();

    private FishingJournal() { }

    private static Item register() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, CrazyWorldProgression.id("fishing_journal"));
        return Registry.register(BuiltInRegistries.ITEM, key, new JournalItem(new Item.Properties().stacksTo(1).setId(key)));
    }

    public static void initialize() {
        SkillStatRegistry.registerNumberStat(LEVEL_STAT, "stat.crazy-world-progression.fishing_level",
                ekuzo.crazyworldprogression.skills.Proficiency.NOVICE, true,
                value -> { }, (player, value) -> { });
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> SENT.remove(handler.player.getUUID()));
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace("tools_and_utilities")))
                .register(output -> output.insertAfter(Items.FISHING_ROD, ITEM));
    }

    public static int level(ServerPlayer player) { return ekuzo.crazyworldprogression.skills.Proficiency.of(player, LEVEL_STAT); }

    /** Remembers the caught items (called with every custom fishing catch). */
    public static void record(ServerPlayer player, List<ItemStack> caught) {
        Set<String> known = new java.util.LinkedHashSet<>(player.getAttachedOrCreate(CAUGHT));
        boolean changed = false;
        for (ItemStack stack : caught)
            if (!stack.isEmpty()) changed |= known.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (changed) player.setAttached(CAUGHT, List.copyOf(known));
    }

    /** Remembers the items a loot pool roll gave the player (called with every roll). */
    public static void recordLoot(ServerPlayer player, Identifier pool, List<LootPoolData.Entry> rewards) {
        Map<String, List<String>> all = new java.util.HashMap<>(player.getAttachedOrCreate(LOOTED));
        Set<String> known = new java.util.LinkedHashSet<>(all.getOrDefault(pool.toString(), List.of()));
        boolean changed = false;
        for (LootPoolData.Entry reward : rewards) if (reward.kind().equals("item")) changed |= known.add(reward.target().toString());
        if (!changed) return;
        all.put(pool.toString(), List.copyOf(known));
        player.setAttached(LOOTED, Map.copyOf(all));
    }

    /** Remembers the items a pool's vanilla entry gave the player. */
    public static void recordLootItems(ServerPlayer player, Identifier pool, List<ItemStack> items) {
        Map<String, List<String>> all = new java.util.HashMap<>(player.getAttachedOrCreate(LOOTED));
        Set<String> known = new java.util.LinkedHashSet<>(all.getOrDefault(pool.toString(), List.of()));
        boolean changed = false;
        for (ItemStack stack : items)
            if (!stack.isEmpty()) changed |= known.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (!changed) return;
        all.put(pool.toString(), List.copyOf(known));
        player.setAttached(LOOTED, Map.copyOf(all));
    }

    /**
     * Opens the journal for the player with what they are allowed to see. The client keeps the last journal it got,
     * so when nothing changed since only an empty "open your copy" message is sent.
     */
    public static void open(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Payload.TYPE)) return;
        String json = GSON.toJson(snapshot(player));
        String key = Integer.toHexString(json.hashCode()) + ":" + json.length();
        if (key.equals(SENT.get(player.getUUID()))) {
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new Payload(""));
            return;
        }
        SENT.put(player.getUUID(), key);
        ekuzo.crazyworldprogression.CwpPayloads.send(player, new Payload(json));
    }

    static JsonObject snapshot(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        int level = level(player);
        Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
        Map<String, List<String>> looted = player.getAttachedOrCreate(LOOTED);
        JsonObject root = new JsonObject();
        root.addProperty("level", level);
        JsonArray categories = new JsonArray();
        add(categories, "fishing", fishing(player, server, pools, level));
        add(categories, "mobs", mobs(server, pools, looted));
        JsonArray dungeons = new JsonArray(), raids = new JsonArray();
        instances(server, pools, looted, dungeons, raids);
        add(categories, "dungeons", dungeons);
        add(categories, "raids", raids);
        add(categories, "events", events(server, pools, looted));
        add(categories, "mounts", collectibles(player, ekuzo.crazyworldprogression.collectibles.Collectibles.MOUNT));
        add(categories, "pets", collectibles(player, ekuzo.crazyworldprogression.collectibles.Collectibles.PET));
        root.add("categories", categories);
        return root;
    }

    private static void add(JsonArray categories, String id, JsonArray entries) {
        if (entries.isEmpty()) return;
        JsonObject category = new JsonObject();
        category.addProperty("id", id);
        category.add("entries", entries);
        categories.add(category);
    }

    /** Fishing locations, each with its fish and when they bite. */
    private static JsonArray fishing(ServerPlayer player, MinecraftServer server, Map<String, LootPoolData.Pool> pools, int level) {
        Set<String> caught = new HashSet<>(player.getAttachedOrCreate(CAUGHT));
        JsonArray locations = new JsonArray();
        for (String location : FishingLootService.locations(server)) {
            // Each item once, with the lowest level any of its entries needs and every condition it bites under.
            Map<String, Info> fish = new LinkedHashMap<>();
            for (FishingLootData.Rule rule : FishingLootService.rulesAt(server, location))
                collect(rule.pool(), pools, fish, condition(rule), new HashSet<>(), 0);
            if (fish.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", location);
            entry.addProperty("biome", server.registryAccess().lookupOrThrow(Registries.BIOME)
                    .get(ResourceKey.create(Registries.BIOME, Identifier.parse(location))).isPresent());
            JsonArray list = new JsonArray();
            List<Map.Entry<String, Info>> sorted = new ArrayList<>(fish.entrySet());
            sorted.sort(java.util.Comparator.comparingInt(item -> item.getValue().level));
            for (Map.Entry<String, Info> item : sorted) {
                JsonObject slot = new JsonObject();
                if (caught.contains(item.getKey())) {
                    slot.addProperty("state", "caught");
                    slot.addProperty("item", item.getKey());
                } else if (item.getValue().level > level) {
                    slot.addProperty("state", "locked");
                    slot.addProperty("level", item.getValue().level);
                } else slot.addProperty("state", "unknown");
                JsonArray when = new JsonArray();
                // A fish that bites without conditions in one rule needs none at all.
                Set<String> conditions = item.getValue().conditions.contains(ALWAYS)
                        ? Set.of(ALWAYS) : item.getValue().conditions;
                conditions.forEach(when::add);
                slot.add("when", when);
                list.add(slot);
            }
            entry.add("fish", list);
            if (!anyFound(list)) {
                entry.addProperty("id", "");
                entry.addProperty("biome", false);
                entry.addProperty("hidden", true);
            }
            locations.add(entry);
        }
        return locations;
    }

    /** Mob templates with a loot pool. */
    private static JsonArray mobs(MinecraftServer server, Map<String, LootPoolData.Pool> pools, Map<String, List<String>> looted) {
        JsonArray entries = new JsonArray();
        // Every mob's attached pool (MobLoot), named by the game's translation of the mob.
        MobLoot.attached(server).forEach((type, poolId) -> {
            var entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(type)).orElse(null);
            Identifier pool = Identifier.tryParse(poolId);
            if (entityType == null || pool == null) return;
            JsonArray groups = new JsonArray();
            group(groups, "", List.of(pool), pools, looted);
            if (groups.isEmpty()) return;
            JsonObject entry = entry(entityType.getDescription().getString(), groups);
            if (!entry.get("name").getAsString().equals("???")) entry.addProperty("key", entityType.getDescriptionId());
            entries.add(entry);
        });
        for (var template : ekuzo.crazyworldprogression.mobforge.MobForge.TEMPLATES.all()) {
            Identifier pool = template.loot().lootPool() == null ? null : Identifier.tryParse(template.loot().lootPool());
            if (pool == null) continue;
            JsonArray groups = new JsonArray();
            group(groups, "", List.of(pool), pools, looted);
            if (!groups.isEmpty()) entries.add(entry(mobName(template), groups));
        }
        return entries;
    }

    /** Pocket portals: dungeons, or raids by their instance mode. One entry per pocket. */
    private static void instances(MinecraftServer server, Map<String, LootPoolData.Pool> pools,
                                  Map<String, List<String>> looted, JsonArray dungeons, JsonArray raids) {
        Set<Identifier> seen = new HashSet<>();
        for (var portal : ekuzo.crazyworldprogression.portals.PortalService.portals(server)) {
            if (portal.isExit() || portal.isCoordinates() || !seen.add(portal.templateId())) continue;
            List<String> scripts = ekuzo.crazyworldprogression.portals.PocketEditorService.scripts(server, portal.templateId());
            List<Identifier> rewards = new ArrayList<>();
            Map<String, Identifier> mobs = new LinkedHashMap<>();
            for (String scriptId : scripts) scriptSources(scriptId, rewards, mobs);
            JsonArray groups = new JsonArray();
            group(groups, "Rewards", rewards, pools, looted);
            mobs.forEach((name, pool) -> group(groups, name, List.of(pool), pools, looted));
            if (groups.isEmpty()) continue;
            String name = portal.displayName() == null || portal.displayName().isBlank()
                    ? pretty(portal.templateId().getPath()) : portal.displayName();
            (portal.raidMode() ? raids : dungeons).add(entry(name, groups));
        }
    }

    /** Scripts not attached to any pocket that roll a loot pool. */
    private static JsonArray events(MinecraftServer server, Map<String, LootPoolData.Pool> pools, Map<String, List<String>> looted) {
        Set<String> attached = new HashSet<>();
        for (Identifier pocket : ekuzo.crazyworldprogression.portals.PocketEditorService.pockets(server))
            attached.addAll(ekuzo.crazyworldprogression.portals.PocketEditorService.scripts(server, pocket));
        JsonArray entries = new JsonArray();
        for (var script : ekuzo.crazyworldprogression.mobforge.MobForge.SCRIPTS.all()) {
            if (attached.contains(script.id())) continue;
            List<Identifier> rewards = new ArrayList<>();
            scriptSources(script.id(), rewards, new LinkedHashMap<>());
            JsonArray groups = new JsonArray();
            group(groups, "", rewards, pools, looted);
            if (!groups.isEmpty()) entries.add(entry(pretty(script.id()), groups));
        }
        return entries;
    }

    /** The loot pools a script rolls, and the mobs (by name) with loot pools it spawns. */
    private static void scriptSources(String scriptId, List<Identifier> rewards, Map<String, Identifier> mobs) {
        var script = ekuzo.crazyworldprogression.mobforge.MobForge.SCRIPTS.get(scriptId).orElse(null);
        if (script == null) return;
        for (var step : script.steps()) {
            String template = null;
            if (step instanceof ekuzo.crazyworldprogression.mobforge.script.MobScript.LootPoolStep loot) {
                if (!rewards.contains(loot.lootPoolId())) rewards.add(loot.lootPoolId());
            } else if (step instanceof ekuzo.crazyworldprogression.mobforge.script.MobScript.SpawnStep spawn) template = spawn.template();
            else if (step instanceof ekuzo.crazyworldprogression.mobforge.script.MobScript.SpawnMobsStep spawn
                    && spawn.mobId().getNamespace().equals(ekuzo.crazyworldprogression.mobforge.MobForge.MOD_ID)) template = spawn.mobId().getPath();
            if (template == null) continue;
            var mob = ekuzo.crazyworldprogression.mobforge.MobForge.TEMPLATES.get(template).orElse(null);
            Identifier pool = mob == null || mob.loot().lootPool() == null ? null : Identifier.tryParse(mob.loot().lootPool());
            if (pool != null) mobs.putIfAbsent(mobName(mob), pool);
        }
    }

    /** One group of items from the given pools; found when any of those pools gave it to the player. */
    private static void group(JsonArray groups, String label, List<Identifier> sources, Map<String, LootPoolData.Pool> pools,
                              Map<String, List<String>> looted) {
        Map<String, Info> items = new LinkedHashMap<>();
        Set<String> found = new HashSet<>();
        for (Identifier source : sources) {
            collect(source, pools, items, ALWAYS, new HashSet<>(), 0);
            found.addAll(looted.getOrDefault(source.toString(), List.of()));
        }
        if (items.isEmpty()) return;
        JsonArray list = new JsonArray();
        for (String item : items.keySet()) {
            JsonObject slot = new JsonObject();
            if (found.contains(item)) {
                slot.addProperty("state", "caught");
                slot.addProperty("item", item);
            } else slot.addProperty("state", "unknown");
            list.add(slot);
        }
        JsonObject group = new JsonObject();
        group.addProperty("label", label);
        group.add("items", list);
        groups.add(group);
    }

    /** Mounts or pets: unlocked ones with name, rarity, entity and stats; locked ones only as "???". */
    private static JsonArray collectibles(ServerPlayer player, String kind) {
        JsonArray entries = new JsonArray();
        String selected = ekuzo.crazyworldprogression.collectibles.Collectibles.selected(player, kind);
        List<Object> definitions = new ArrayList<>(kind.equals("mount")
                ? ekuzo.crazyworldprogression.collectibles.Collectibles.mounts()
                : ekuzo.crazyworldprogression.collectibles.Collectibles.pets());
        for (Object definition : definitions) {
            Identifier id;
            String name;
            ekuzo.crazyworldprogression.collectibles.Rarity rarity;
            net.minecraft.world.entity.EntityType<?> type;
            JsonArray stats = new JsonArray();
            if (definition instanceof ekuzo.crazyworldprogression.collectibles.Collectibles.MountDefinition mount) {
                id = mount.id();
                name = mount.name();
                rarity = mount.rarity();
                type = mount.type();
                // Blocks per second at full gallop, and the height a full jump reaches (the horse formulas).
                stats.add(String.format(java.util.Locale.ROOT, "Speed|%.1f blocks/s", mount.speed() * 43.17));
                double j = mount.jumpStrength();
                double height = -0.1817584952 * j * j * j + 3.689713992 * j * j + 2.128599134 * j - 0.343930367;
                stats.add(String.format(java.util.Locale.ROOT, "Jump|%.1f blocks", height));
            } else {
                var pet = (ekuzo.crazyworldprogression.collectibles.Collectibles.PetDefinition) definition;
                id = pet.id();
                name = pet.name();
                rarity = pet.rarity();
                type = pet.type();
            }
            boolean unlocked = ekuzo.crazyworldprogression.collectibles.Collectibles.unlocked(player, kind, id);
            JsonObject entry = new JsonObject();
            entry.addProperty("unlocked", unlocked);
            if (unlocked) {
                entry.addProperty("id", id.toString());
                entry.addProperty("name", name);
                entry.addProperty("rarity", rarity.label());
                entry.addProperty("color", rarity.rgb());
                entry.addProperty("entity", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
                entry.addProperty("selected", id.toString().equals(selected));
                entry.add("stats", stats);
            } else entry.addProperty("name", "???");
            entries.add(entry);
        }
        return entries;
    }

    /** Whether the player got any item of a list (state "caught"). */
    private static boolean anyFound(JsonArray items) {
        for (var item : items) if (item.getAsJsonObject().get("state").getAsString().equals("caught")) return true;
        return false;
    }

    /** An entry is "???" until the player got at least one of its items. */
    private static JsonObject entry(String name, JsonArray groups) {
        boolean found = false;
        for (var group : groups) found |= anyFound(group.getAsJsonObject().getAsJsonArray("items"));
        JsonObject entry = new JsonObject();
        entry.addProperty("name", found ? name : "???");
        entry.add("groups", groups);
        return entry;
    }

    private static String mobName(ekuzo.crazyworldprogression.mobforge.template.MobTemplate template) {
        return template.name() == null || template.name().isBlank() ? pretty(template.id()) : template.name();
    }

    /** "frost_king" becomes "Frost King". */
    static String pretty(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.replace('/', ' ').replace('_', ' ').replace('-', ' ').split(" ")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(java.util.Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }

    private static final String ALWAYS = "any|any|";

    /** Lowest level any entry of the item needs, and the condition keys of every rule that can give it. */
    private static final class Info {
        int level = Integer.MAX_VALUE;
        final Set<String> conditions = new java.util.LinkedHashSet<>();
    }

    /** "weather|time|liquid"; water (the normal case) is left empty. */
    private static String condition(FishingLootData.Rule rule) {
        String liquid = rule.liquid().equals("minecraft:water") ? "" : rule.liquid();
        return rule.weather() + "|" + rule.time() + "|" + liquid;
    }

    private static void collect(Identifier id, Map<String, LootPoolData.Pool> pools, Map<String, Info> fish,
                                String condition, Set<String> seen, int depth) {
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool == null || depth > LootPoolService.MAX_DEPTH || !seen.add(id.toString())) return;
        for (LootPoolData.Entry entry : pool.entries()) {
            if (entry.kind().equals("pool")) collect(entry.target(), pools, fish, condition, seen, depth + 1);
            else if (entry.kind().equals(MobLoot.VANILLA) && entry.chance() > 0) {
                for (String item : MobLoot.tableItems(entry.target())) {
                    Info info = fish.computeIfAbsent(item, ignored -> new Info());
                    info.level = Math.min(info.level, 0);
                    info.conditions.add(condition);
                }
            } else if (entry.kind().equals("item") && entry.chance() > 0) {
                Info info = fish.computeIfAbsent(entry.target().toString(), ignored -> new Info());
                info.level = Math.min(info.level, entry.level());
                info.conditions.add(condition);
            }
        }
    }

    /** Right-click to open the journal. */
    private static final class JournalItem extends Item {
        JournalItem(Properties properties) { super(properties); }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResult.SUCCESS;
        }
    }

    /** The journal contents as JSON (see {@link #snapshot}), or empty: open the copy the client already has. */
    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("fishing_journal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
