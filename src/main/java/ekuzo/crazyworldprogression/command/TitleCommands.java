package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import ekuzo.crazyworldprogression.titles.TitleService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Players can select titles defined by completed achievements. */
public final class TitleCommands {
    private TitleCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("titles")
                .executes(context -> list(context.getSource().getPlayerOrException()))
                .then(Commands.literal("list").executes(context -> list(context.getSource().getPlayerOrException())))
                .then(Commands.literal("clear").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    TitleService.clear(player);
                    player.sendSystemMessage(Component.literal("Title unequipped."));
                    return 1;
                }))
                .then(Commands.literal("equip")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            try {
                                TitleService.equip(player, Identifier.parse(StringArgumentType.getString(context, "id")));
                                player.sendSystemMessage(Component.literal("Title equipped."));
                                return 1;
                            } catch (IllegalArgumentException exception) {
                                player.sendSystemMessage(Component.literal(exception.getMessage()));
                                return 0;
                            }
                        }))));
    }
    private static int list(ServerPlayer player) {
        var definitions = TitleService.unlocked(player);
        if (definitions.isEmpty()) {
            player.sendSystemMessage(Component.literal("No achievement titles unlocked yet."));
            return 0;
        }
        TitleService.Definition equipped = TitleService.equipped(player);
        player.sendSystemMessage(Component.literal("Unlocked titles (/titles equip <id>):"));
        for (TitleService.Definition definition : definitions) {
            int color = Integer.parseInt(definition.color().substring(1), 16);
            player.sendSystemMessage(Component.literal((equipped != null && equipped.id().equals(definition.id()) ? "* " : "  ")
                    + definition.name() + " (" + definition.id() + ")").withColor(color));
        }
        return definitions.size();
    }
}
