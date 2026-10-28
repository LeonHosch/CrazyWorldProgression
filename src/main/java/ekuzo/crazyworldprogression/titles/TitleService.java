package ekuzo.crazyworldprogression.titles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads title metadata directly from custom advancement JSON. */
public final class TitleService {
    private TitleService() { }

    public record Definition(Identifier id, String name, String color, Identifier achievementId) { }

    public static Map<String, Definition> definitions(MinecraftServer server) {
        Map<String, Definition> found = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : server.getResourceManager()
                .listResources("advancement", id -> id.getPath().startsWith("advancement/")
                        && id.getPath().endsWith(".json")).entrySet()) {
            Identifier resourceId = entry.getKey();
            String path = resourceId.getPath();
            Identifier achievementId = Identifier.fromNamespaceAndPath(resourceId.getNamespace(),
                    path.substring("advancement/".length(), path.length() - ".json".length()));
            if (server.getAdvancements().get(achievementId) == null) continue;
            try (var reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!json.has("echelon_title")) continue;
                JsonObject title = json.getAsJsonObject("echelon_title");
                Identifier id = Identifier.parse(title.get("id").getAsString());
                String name = title.get("name").getAsString();
                String color = title.get("color").getAsString();
                if (name.isBlank() || name.length() > 48 || !color.matches("#[0-9a-fA-F]{6}"))
                    throw new IllegalArgumentException("title name or color is invalid");
                if (found.putIfAbsent(id.toString(), new Definition(id, name, color, achievementId)) != null)
                    CrazyWorldProgression.LOGGER.warn("Duplicate achievement title ID {} in {}", id, resourceId);
            } catch (IOException | RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Invalid title metadata in {}: {}", resourceId, exception.getMessage());
            }
        }
        return Map.copyOf(found);
    }

    public static boolean unlocked(ServerPlayer player, Definition definition) {
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(definition.achievementId());
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    public static List<Definition> unlocked(ServerPlayer player) {
        List<Definition> result = new ArrayList<>();
        for (Definition definition : definitions(player.level().getServer()).values())
            if (unlocked(player, definition)) result.add(definition);
        result.sort(Comparator.comparing(definition -> definition.id().toString()));
        return result;
    }

    public static Definition equipped(ServerPlayer player) {
        return equipped(player, definitions(player.level().getServer()));
    }

    public static Definition equipped(ServerPlayer player, Map<String, Definition> definitions) {
        Definition definition = definitions.get(TitleData.equipped(player));
        return definition != null && unlocked(player, definition) ? definition : null;
    }

    public static void equip(ServerPlayer player, Identifier id) {
        Definition definition = definitions(player.level().getServer()).get(id.toString());
        if (definition == null) throw new IllegalArgumentException("Unknown achievement title " + id);
        if (!unlocked(player, definition)) throw new IllegalArgumentException("Achievement not unlocked: " + definition.achievementId());
        TitleData.equip(player, id.toString());
        TitleNetworking.broadcast(player.level().getServer());
    }

    public static void clear(ServerPlayer player) {
        TitleData.equip(player, "");
        TitleNetworking.broadcast(player.level().getServer());
    }
}
