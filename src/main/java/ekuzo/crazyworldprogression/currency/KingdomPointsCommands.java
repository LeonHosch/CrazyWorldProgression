package ekuzo.crazyworldprogression.currency;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;

public class KingdomPointsCommands {

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            register(dispatcher);
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var baseCommand = Commands.literal("kingdompoints")
                // Player self check: /kingdompoints or /kingdompoints balance
                .executes(KingdomPointsCommands::checkSelf)
                .then(Commands.literal("balance")
                        .executes(KingdomPointsCommands::checkSelf)
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(KingdomPointsCommands::checkOther)
                        )
                )
                // Admin give: /kingdompoints give <targets> <amount>
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(KingdomPointsCommands::givePoints)
                                )
                        )
                )
                // Admin take: /kingdompoints take <targets> <amount>
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(KingdomPointsCommands::takePoints)
                                )
                        )
                )
                // Admin set: /kingdompoints set <targets> <amount>
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(KingdomPointsCommands::setPoints)
                                )
                        )
                );

        dispatcher.register(baseCommand);

        // Alias: /kp
        var aliasCommand = Commands.literal("kp")
                .executes(KingdomPointsCommands::checkSelf)
                .then(Commands.literal("balance")
                        .executes(KingdomPointsCommands::checkSelf)
                        .then(Commands.argument("target", GameProfileArgument.gameProfile())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(KingdomPointsCommands::checkOther)
                        )
                )
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(KingdomPointsCommands::givePoints)
                                )
                        )
                )
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(KingdomPointsCommands::takePoints)
                                )
                        )
                )
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(KingdomPointsCommands::setPoints)
                                )
                        )
                );

        dispatcher.register(aliasCommand);
    }

    private static int checkSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        KingdomPointsData data = KingdomPointsData.get(source.getServer());
        int current = data.getPoints(player.getUUID());

        source.sendSuccess(() -> Component.literal("You have ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" KingdomPoints.").withStyle(ChatFormatting.GOLD)), false);

        return current;
    }

    private static int checkOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "target");
        KingdomPointsData data = KingdomPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int current = data.getPoints(profile.id());
            source.sendSuccess(() -> Component.literal(profile.name() + " has ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.valueOf(current)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" KingdomPoints.").withStyle(ChatFormatting.GOLD)), false);
            return current;
        }
        return 0;
    }

    private static int givePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        KingdomPointsData data = KingdomPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int newBalance = data.addPoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Gave ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" KingdomPoints to " + profile.name() + ". (New balance: " + newBalance + ")")
                            .withStyle(ChatFormatting.GREEN)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal("You received ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(" KingdomPoints! (Total: " + newBalance + ")").withStyle(ChatFormatting.GOLD)));
            }
        }

        return profiles.size();
    }

    private static int takePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        KingdomPointsData data = KingdomPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            int newBalance = data.removePoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Took ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" KingdomPoints from " + profile.name() + ". (New balance: " + newBalance + ")")
                            .withStyle(ChatFormatting.RED)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal(amount + " KingdomPoints were removed from your balance. (Total: " + newBalance + ")")
                        .withStyle(ChatFormatting.RED));
            }
        }

        return profiles.size();
    }

    private static int setPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        KingdomPointsData data = KingdomPointsData.get(source.getServer());

        for (NameAndId profile : profiles) {
            data.setPoints(profile.id(), amount);

            source.sendSuccess(() -> Component.literal("Set ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(profile.name() + "'s ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("KingdomPoints to " + amount + ".")
                            .withStyle(ChatFormatting.GREEN)), true);

            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayer(profile.id());
            if (targetPlayer != null) {
                targetPlayer.sendSystemMessage(Component.literal("Your KingdomPoints balance was set to " + amount + ".")
                        .withStyle(ChatFormatting.GOLD));
            }
        }

        return profiles.size();
    }
}